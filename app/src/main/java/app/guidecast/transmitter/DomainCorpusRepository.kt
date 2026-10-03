package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import app.guidecast.core.translation.TranslationStyle
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class DomainCorpusProfile(
    val id: Long,
    val name: String,
    val description: String,
    val sourceLanguageTag: String,
    val targetLanguageTag: String,
    val style: TranslationStyle,
    val pairCount: Int,
    val active: Boolean,
)

data class DomainCorpusMatch(
    val exactTranslation: String?,
    val hints: String,
    val revision: Long = 0,
)

sealed interface DomainImportResult {
    data class Success(val profile: DomainCorpusProfile) : DomainImportResult
    data class Failure(val lineNumber: Int?, val reason: String) : DomainImportResult
}

open class DomainCorpusRepository internal constructor(
    private val dbHelper: SQLiteOpenHelper?,
) {
    constructor(context: Context) : this(DomainCorpusDatabase(context.applicationContext))
    internal constructor(context: Context, databaseName: String) :
        this(DomainCorpusDatabase(context.applicationContext, databaseName))

    internal fun close() { dbHelper?.close() }

    private val mutex = Mutex()
    private val _revision = MutableStateFlow(0L)
    open val revision: StateFlow<Long> = _revision.asStateFlow()

    // In-memory cache for active profiles to optimize lookup performance
    private data class CachedPair(
        val sourceText: String,
        val targetText: String,
        val sourceNfcTrimmed: String,
        val tokens: Set<String>,
    )

    private data class CachedActiveProfile(
        val profile: DomainCorpusProfile,
        val pairs: List<CachedPair>,
        val exactMap: Map<String, String>,
    )

    private var activeProfilesCache: Map<Pair<String, String>, CachedActiveProfile>? = null

    open suspend fun profiles(): List<DomainCorpusProfile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = requireNotNull(dbHelper).readableDatabase
            val result = ArrayList<DomainCorpusProfile>()
            val cursor = db.rawQuery(
                "SELECT id, name, description, source_lang, target_lang, style, pair_count, active FROM domain_profiles ORDER BY id ASC",
                null,
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    result.add(
                        DomainCorpusProfile(
                            id = c.getLong(0),
                            name = c.getString(1),
                            description = c.getString(2),
                            sourceLanguageTag = c.getString(3),
                            targetLanguageTag = c.getString(4),
                            style = TranslationStyle.valueOf(c.getString(5)),
                            pairCount = c.getInt(6),
                            active = c.getInt(7) == 1,
                        )
                    )
                }
            }
            result
        }
    }

    open suspend fun importTxt(
        input: InputStream,
        name: String,
        description: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
        style: TranslationStyle,
    ): DomainImportResult = withContext(Dispatchers.IO) {
        val metaError = DomainCorpusFormat.validateMetadata(name, description)
        if (metaError != null) {
            return@withContext DomainImportResult.Failure(null, metaError)
        }
        val trimmedName = name.trim()
        val trimmedDesc = description.trim()

        val rawSource = sourceLanguageTag.trim().lowercase(Locale.ROOT)
        val rawTarget = targetLanguageTag.trim().lowercase(Locale.ROOT)
        if (!LANGUAGE_TAG_REGEX.matches(rawSource) || !LANGUAGE_TAG_REGEX.matches(rawTarget)) {
            return@withContext DomainImportResult.Failure(null, "유효하지 않은 언어 태그입니다.")
        }

        val normSource = normalizeSourceLanguageTag(rawSource)
        val normTarget = normalizeTargetLanguageTag(rawTarget)

        val parseResult = DomainCorpusFormat.parseAndValidate(input)
        val pairs = when (parseResult) {
            is DomainCorpusFormat.ParseResult.Success -> parseResult.pairs
            is DomainCorpusFormat.ParseResult.Error -> {
                return@withContext DomainImportResult.Failure(parseResult.lineNumber, parseResult.reason)
            }
        }

        mutex.withLock {
            val db = requireNotNull(dbHelper).writableDatabase
            db.beginTransaction()
            try {
                val profileValues = ContentValues().apply {
                    put("name", trimmedName)
                    put("description", trimmedDesc)
                    put("source_lang", normSource)
                    put("target_lang", normTarget)
                    put("style", style.name)
                    put("pair_count", pairs.size)
                    put("active", 0)
                    put("created_at", System.currentTimeMillis())
                }
                val profileId = db.insertOrThrow("domain_profiles", null, profileValues)

                val stmt = db.compileStatement(
                    "INSERT INTO domain_pairs (profile_id, source_text, target_text, source_nfc) VALUES (?, ?, ?, ?)"
                )
                try {
                    for (pair in pairs) {
                        stmt.bindLong(1, profileId)
                        stmt.bindString(2, pair.sourceText)
                        stmt.bindString(3, pair.targetText)
                        stmt.bindString(4, pair.sourceNfcTrimmed)
                        stmt.executeInsert()
                        stmt.clearBindings()
                    }
                } finally {
                    stmt.close()
                }

                db.setTransactionSuccessful()
                activeProfilesCache = null
                _revision.value += 1
                DomainImportResult.Success(
                    DomainCorpusProfile(
                        id = profileId,
                        name = trimmedName,
                        description = trimmedDesc,
                        sourceLanguageTag = normSource,
                        targetLanguageTag = normTarget,
                        style = style,
                        pairCount = pairs.size,
                        active = false,
                    )
                )
            } finally {
                db.endTransaction()
            }
        }
    }

    /** PoC review workflow: copy the active profile; never overwrite its original rows. */
    suspend fun applyReviewedComparison(comparison: ShadowComparison, corrected: String,
        humanReviewed: Boolean): DomainLearningRevision = withContext(Dispatchers.IO) {
        val pair = reviewedDomainPair(comparison.original, corrected, comparison.target, humanReviewed)
        mutex.withLock {
            check(_revision.value == comparison.corpusRevision) { "비교 이후 자료가 변경됐습니다. 새 비교로 다시 검수하세요." }
            val source = normalizeSourceLanguageTag(comparison.source)
            val target = normalizeTargetLanguageTag(comparison.target)
            val active = getOrLoadActiveProfilesCacheLocked()[source to target]
                ?: error("이 언어 쌍의 활성 도메인 자료를 먼저 준비하세요.")
            check(comparison.style == TranslationStyle.AUTO || active.profile.style == TranslationStyle.AUTO || comparison.style == active.profile.style) {
                "비교 문체와 활성 자료의 문체가 다릅니다."
            }
            val pairs = active.pairs.filterNot { it.sourceNfcTrimmed == pair.normalizedSource }
                .map { it.sourceText to it.targetText } + (pair.original to pair.corrected)
            check(pairs.size <= DomainCorpusFormat.MAX_PAIRS)
            val serialized = pairs.joinToString("\n", postfix = "\n") { it.first + "\t" + it.second }
            check(DomainCorpusFormat.parseAndValidate(serialized.byteInputStream()) is DomainCorpusFormat.ParseResult.Success) {
                "자료 용량 또는 예문 형식을 확인하세요."
            }
            val db = requireNotNull(dbHelper).writableDatabase
            var newId = 0L
            db.beginTransaction()
            try {
                newId = db.insertOrThrow("domain_profiles", null, ContentValues().apply {
                    put("name", active.profile.name.take(70) + " · 검수")
                    put("description", active.profile.description)
                    put("source_lang", source); put("target_lang", target); put("style", active.profile.style.name)
                    put("pair_count", pairs.size); put("active", 0); put("created_at", System.currentTimeMillis())
                })
                db.compileStatement("INSERT INTO domain_pairs (profile_id, source_text, target_text, source_nfc) VALUES (?, ?, ?, ?)").use { statement ->
                    pairs.forEach { (original, translated) ->
                        statement.bindLong(1, newId); statement.bindString(2, original); statement.bindString(3, translated)
                        statement.bindString(4, Normalizer.normalize(original.trim(), Normalizer.Form.NFC))
                        statement.executeInsert(); statement.clearBindings()
                    }
                }
                db.insertOrThrow("domain_learning_revisions", null, ContentValues().apply {
                    put("profile_id", newId); put("parent_profile_id", active.profile.id); put("created_at", System.currentTimeMillis())
                })
                db.execSQL("UPDATE domain_profiles SET active = 0 WHERE source_lang = ? AND target_lang = ?", arrayOf(source, target))
                db.execSQL("UPDATE domain_profiles SET active = 1 WHERE id = ?", arrayOf(newId))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            activeProfilesCache = null
            _revision.value += 1
            DomainLearningRevision(newId, active.profile.id, _revision.value)
        }
    }

    suspend fun latestLearningRevision(): DomainLearningRevision? = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(dbHelper).readableDatabase.rawQuery(
                "SELECT l.profile_id, l.parent_profile_id FROM domain_learning_revisions l JOIN domain_profiles p ON p.id=l.profile_id WHERE p.active=1 ORDER BY l.profile_id DESC LIMIT 1", null).use { cursor ->
                if (cursor.moveToFirst()) DomainLearningRevision(cursor.getLong(0), cursor.getLong(1), _revision.value) else null
            }
        }
    }

    suspend fun rollbackLearning(change: DomainLearningRevision): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(change.revision == _revision.value) { "자료가 변경됐습니다. 되돌릴 버전을 다시 확인하세요." }
            val db = requireNotNull(dbHelper).writableDatabase
            db.beginTransaction()
            try {
                db.rawQuery("SELECT p.source_lang, p.target_lang FROM domain_learning_revisions l JOIN domain_profiles p ON p.id=l.parent_profile_id JOIN domain_profiles c ON c.id=l.profile_id WHERE l.profile_id=? AND l.parent_profile_id=? AND c.active=1", arrayOf(change.profileId.toString(), change.previousProfileId.toString())).use { cursor ->
                    check(cursor.moveToFirst()) { "이전 자료가 없거나 현재 활성 버전이 아닙니다." }
                    db.execSQL("UPDATE domain_profiles SET active=0 WHERE source_lang=? AND target_lang=?", arrayOf(cursor.getString(0), cursor.getString(1)))
                    db.execSQL("UPDATE domain_profiles SET active=1 WHERE id=?", arrayOf(change.previousProfileId))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            activeProfilesCache = null
            _revision.value += 1
        }
    }

    open suspend fun activate(id: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = requireNotNull(dbHelper).writableDatabase
            db.beginTransaction()
            try {
                var sourceLang = ""
                var targetLang = ""
                db.rawQuery("SELECT source_lang, target_lang FROM domain_profiles WHERE id = ?", arrayOf(id.toString())).use { cursor ->
                    if (cursor.moveToFirst()) {
                        sourceLang = cursor.getString(0)
                        targetLang = cursor.getString(1)
                    }
                }
                if (sourceLang.isNotEmpty()) {
                    // 동일 언어쌍 활성 profile은 하나, 다른 언어쌍은 동시에 활성 가능
                    db.execSQL(
                        "UPDATE domain_profiles SET active = 0 WHERE source_lang = ? AND target_lang = ?",
                        arrayOf(sourceLang, targetLang),
                    )
                    db.execSQL("UPDATE domain_profiles SET active = 1 WHERE id = ?", arrayOf(id.toString()))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            activeProfilesCache = null
            _revision.value += 1
        }
    }

    open suspend fun deactivate(id: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = requireNotNull(dbHelper).writableDatabase
            db.execSQL("UPDATE domain_profiles SET active = 0 WHERE id = ?", arrayOf(id.toString()))
            activeProfilesCache = null
            _revision.value += 1
        }
    }

    open suspend fun remove(id: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = requireNotNull(dbHelper).writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM domain_pairs WHERE profile_id = ?", arrayOf(id.toString()))
                db.execSQL("DELETE FROM domain_profiles WHERE id = ?", arrayOf(id.toString()))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            activeProfilesCache = null
            _revision.value += 1
        }
    }

    open suspend fun exportTxt(id: Long, output: OutputStream): Unit = withContext(Dispatchers.IO) {
        // Snapshot bounded local rows while locked; a slow document provider must not
        // hold up live matching, activation or operator deletion.
        val snapshot = mutex.withLock {
            val db = requireNotNull(dbHelper).readableDatabase
            db.rawQuery("SELECT id FROM domain_profiles WHERE id = ?", arrayOf(id.toString())).use {
                require(it.moveToFirst()) { "내보낼 코퍼스를 찾을 수 없습니다." }
            }
            val cursor = db.rawQuery(
                "SELECT source_text, target_text FROM domain_pairs WHERE profile_id = ? ORDER BY id ASC",
                arrayOf(id.toString()),
            )
            val pairs = ArrayList<Pair<String, String>>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    pairs.add(c.getString(0) to c.getString(1))
                }
            }
            pairs
        }
        for ((source, target) in snapshot) {
            output.write("$source\t$target\n".toByteArray(StandardCharsets.UTF_8))
        }
        output.flush()
    }

    /** Build the active lexical index before input capture, outside the realtime request path. */
    open suspend fun prepareActiveIndex(): Long = withContext(Dispatchers.IO) {
        mutex.withLock { getOrLoadActiveProfilesCacheLocked(); _revision.value }
    }

    open suspend fun match(
        text: String,
        source: String,
        target: String,
        style: TranslationStyle,
    ): DomainCorpusMatch = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext DomainCorpusMatch(null, "", _revision.value)

        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
        val normSource = normalizeSourceLanguageTag(source)
        val normTarget = normalizeTargetLanguageTag(target)

        mutex.withLock {
            val cachedMap = getOrLoadActiveProfilesCacheLocked()
            val active = cachedMap[normSource to normTarget] ?: return@withContext DomainCorpusMatch(null, "", _revision.value)

            // 1. Exact substitution: only active matching domain/language + style and NFC+trim exact whole source
            // Current request style authoritative; exact only same style or request AUTO. Never fuzzy or ASR correction.
            if (style == TranslationStyle.AUTO || style == active.profile.style) {
                val exact = active.exactMap[normalized]
                if (exact != null) {
                    return@withContext DomainCorpusMatch(exactTranslation = exact, hints = "", revision = _revision.value)
                }
            }

            // If request style is specific and conflicts with profile style, do not provide mismatched style hints
            if (style != TranslationStyle.AUTO && active.profile.style != TranslationStyle.AUTO && active.profile.style != style) {
                return@withContext DomainCorpusMatch(null, "", _revision.value)
            }

            val hintsObj = JSONObject().apply {
                put("domain", active.profile.name)
                if (active.profile.description.isNotBlank()) {
                    put("description", active.profile.description)
                }
            }

            // 2. Hints retrieval: up to 3 relevant full pairs, token/CJK overlap threshold avoids unrelated examples
            val inputTokens = extractMeaningfulTokens(normalized)
            val scoredCandidates = ArrayList<Pair<CachedPair, Double>>()
            if (inputTokens.isNotEmpty()) {
                for (cand in active.pairs) {
                    val intersectionSize = cand.tokens.count { it in inputTokens }
                    if (intersectionSize == 0) continue

                    // Check overlap threshold to avoid unrelated examples
                    val unionSize = (inputTokens.size + cand.tokens.size - intersectionSize).coerceAtLeast(1)
                    val jaccard = intersectionSize.toDouble() / unionSize
                    val score = jaccard * 10.0 + intersectionSize
                    if (intersectionSize >= 2 || (intersectionSize == 1 && jaccard >= 0.15)) {
                        scoredCandidates.add(cand to score)
                    }
                }
            }

            scoredCandidates.sortByDescending { it.second }
            val topCandidates = scoredCandidates.take(3)
            // No retrieved evidence means no unrelated profile description in the model prompt.
            if (topCandidates.isEmpty()) return@withContext DomainCorpusMatch(null, "", _revision.value)

            // Format as JSON quoted example data, total hints <= 1200 chars, no partial pair cutting
            val examplesArray = JSONArray()
            for ((cand, _) in topCandidates) {
                val obj = JSONObject().apply {
                    put("source", cand.sourceText)
                    put("translation", cand.targetText)
                }
                val testObj = JSONObject(hintsObj.toString())
                val testExamples = JSONArray(examplesArray.toString()).put(obj)
                testObj.put("examples", testExamples)
                if (testObj.toString().length <= DomainCorpusFormat.MAX_HINTS_LENGTH) {
                    examplesArray.put(obj)
                } else {
                    break
                }
            }

            if (examplesArray.length() > 0) {
                hintsObj.put("examples", examplesArray)
            }

            val hintsString = hintsObj.toString()
            DomainCorpusMatch(exactTranslation = null, hints = hintsString, revision = _revision.value)
        }
    }

    private fun getOrLoadActiveProfilesCacheLocked(): Map<Pair<String, String>, CachedActiveProfile> {
        val existing = activeProfilesCache
        if (existing != null) return existing

        val db = requireNotNull(dbHelper).readableDatabase
        val activeProfiles = ArrayList<DomainCorpusProfile>()
        db.rawQuery(
            "SELECT id, name, description, source_lang, target_lang, style, pair_count, active FROM domain_profiles WHERE active = 1",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                activeProfiles.add(
                    DomainCorpusProfile(
                        id = c.getLong(0),
                        name = c.getString(1),
                        description = c.getString(2),
                        sourceLanguageTag = c.getString(3),
                        targetLanguageTag = c.getString(4),
                        style = TranslationStyle.valueOf(c.getString(5)),
                        pairCount = c.getInt(6),
                        active = true,
                    )
                )
            }
        }

        val map = HashMap<Pair<String, String>, CachedActiveProfile>()
        for (profile in activeProfiles) {
            val pairs = ArrayList<CachedPair>()
            val exactMap = HashMap<String, String>()

            db.rawQuery(
                "SELECT source_text, target_text, source_nfc FROM domain_pairs WHERE profile_id = ?",
                arrayOf(profile.id.toString()),
            ).use { pc ->
                while (pc.moveToNext()) {
                    val sText = pc.getString(0)
                    val tText = pc.getString(1)
                    val sNfc = pc.getString(2)
                    exactMap[sNfc] = tText
                    pairs.add(
                        CachedPair(
                            sourceText = sText,
                            targetText = tText,
                            sourceNfcTrimmed = sNfc,
                            tokens = extractMeaningfulTokens(sNfc),
                        )
                    )
                }
            }

            map[profile.sourceLanguageTag to profile.targetLanguageTag] = CachedActiveProfile(
                profile = profile,
                pairs = pairs,
                exactMap = exactMap,
            )
        }

        activeProfilesCache = map
        return map
    }

    private fun extractMeaningfulTokens(text: String): Set<String> {
        val tokens = HashSet<String>()
        val words = text.split(PUNCTUATION_REGEX)
        for (w in words) {
            val trimmed = w.trim()
            if (trimmed.isEmpty()) continue

            // Add original word if length >= 2
            if (trimmed.length >= 2) {
                tokens.add(trimmed)
                // Strip Korean particles/postpositions (조사) if remaining length >= 2
                val stripped = stripKoreanParticle(trimmed)
                if (stripped != null && stripped.length >= 2) {
                    tokens.add(stripped)
                }
            } else {
                val code = trimmed[0].code
                // CJK Ideograph or Hangul syllable single char
                if (code in 0x4E00..0x9FFF || code in 0xAC00..0xD7AF) {
                    tokens.add(trimmed)
                }
            }

            // For unspaced CJK or mixed text, extract character bigrams
            if (trimmed.length >= 2 && trimmed.any { it.code in 0x4E00..0x9FFF }) {
                for (i in 0 until trimmed.length - 1) {
                    tokens.add(trimmed.substring(i, i + 2))
                }
            }
        }
        return tokens
    }

    private fun stripKoreanParticle(word: String): String? {
        for (particle in KOREAN_PARTICLES) {
            if (word.endsWith(particle) && word.length > particle.length) {
                val stem = word.substring(0, word.length - particle.length)
                if (stem.length >= 2) return stem
            }
        }
        return null
    }

    internal fun extractTokens(text: String): Set<String> = extractMeaningfulTokens(text)

    internal fun seedActiveProfileForTest(
        profile: DomainCorpusProfile,
        rawPairs: List<Pair<String, String>>,
    ) {
        val pairs = ArrayList<CachedPair>()
        val exactMap = HashMap<String, String>()
        for ((s, t) in rawPairs) {
            val sNfc = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).trim()
            val tTrimmed = t.trim()
            exactMap[sNfc] = tTrimmed
            pairs.add(
                CachedPair(
                    sourceText = s.trim(),
                    targetText = tTrimmed,
                    sourceNfcTrimmed = sNfc,
                    tokens = extractMeaningfulTokens(sNfc),
                )
            )
        }
        val cached = CachedActiveProfile(profile, pairs, exactMap)
        val current = activeProfilesCache?.toMutableMap() ?: HashMap()
        current[profile.sourceLanguageTag to profile.targetLanguageTag] = cached
        activeProfilesCache = current
        _revision.value += 1
    }

    internal fun invalidateCacheForTest() {
        activeProfilesCache = null
        _revision.value += 1
    }

    internal fun clearActiveProfilesForTest() {
        activeProfilesCache = emptyMap()
        _revision.value += 1
    }

    companion object {
        private val LANGUAGE_TAG_REGEX = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})*$", RegexOption.IGNORE_CASE)
        private val PUNCTUATION_REGEX = Regex("[\\s.,!?'\"`~@#$%^&*()_+\\-=\\[\\]{};:/<>]+|['\"‘“’”·…]+")
        private val KOREAN_PARTICLES = listOf(
            "에서는", "보다는", "으로의", "에서의", "까지의", "부터의",
            "에서", "으로", "에게", "부터", "까지", "와의", "과의",
            "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "로", "도", "만",
        )

        fun normalizeSourceLanguageTag(tag: String): String {
            val trimmed = tag.trim().lowercase(Locale.ROOT)
            return when {
                trimmed == "ko" || trimmed.startsWith("ko-") -> "ko"
                trimmed == "en" || trimmed.startsWith("en-") -> "en"
                trimmed == "ja" || trimmed.startsWith("ja-") -> "ja"
                trimmed == "zh-hant" || trimmed == "zh-tw" || trimmed == "zh-hk" -> "zh-Hant"
                trimmed == "zh-hans" || trimmed == "zh-cn" || trimmed == "zh-sg" || trimmed == "zh" -> "zh-Hans"
                else -> trimmed
            }
        }

        fun normalizeTargetLanguageTag(tag: String): String {
            val trimmed = tag.trim().lowercase(Locale.ROOT)
            return when {
                trimmed == "zh-hant" || trimmed == "zh-tw" || trimmed == "zh-hk" -> "zh-Hant"
                trimmed == "zh-hans" || trimmed == "zh-cn" || trimmed == "zh-sg" || trimmed == "zh" -> "zh-Hans"
                trimmed == "en" || trimmed.startsWith("en-") -> "en"
                trimmed == "ja" || trimmed.startsWith("ja-") -> "ja"
                trimmed == "ko" || trimmed.startsWith("ko-") -> "ko"
                else -> trimmed
            }
        }
    }
}

private class DomainCorpusDatabase(context: Context, databaseName: String = "domain_corpus.db") :
    SQLiteOpenHelper(context, databaseName, null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE domain_profiles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                source_lang TEXT NOT NULL,
                target_lang TEXT NOT NULL,
                style TEXT NOT NULL,
                pair_count INTEGER NOT NULL,
                active INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE domain_pairs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id INTEGER NOT NULL,
                source_text TEXT NOT NULL,
                target_text TEXT NOT NULL,
                source_nfc TEXT NOT NULL,
                FOREIGN KEY(profile_id) REFERENCES domain_profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_profiles_lang_active ON domain_profiles(source_lang, target_lang, active)")
        db.execSQL("CREATE INDEX idx_pairs_profile_id ON domain_pairs(profile_id)")
        db.execSQL("CREATE INDEX idx_pairs_nfc_lookup ON domain_pairs(profile_id, source_nfc)")
        createLearningHistory(db)
    }

    private fun createLearningHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE domain_learning_revisions (profile_id INTEGER PRIMARY KEY, parent_profile_id INTEGER NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(profile_id) REFERENCES domain_profiles(id) ON DELETE CASCADE)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) { createLearningHistory(db); return }
        throw SQLiteException("Destructive database schema changes are prohibited to preserve user domain data.")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
