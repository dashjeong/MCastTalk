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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

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
    val candidatesVisited: Int = 0,
)

sealed interface DomainImportResult {
    data class Success(val profile: DomainCorpusProfile) : DomainImportResult
    data class Failure(val lineNumber: Int?, val reason: String) : DomainImportResult
}

internal class ReferenceDocumentWasRemovedException : IllegalStateException()

open class DomainCorpusRepository internal constructor(
    private val dbHelper: SQLiteOpenHelper?,
    private val beforeIndexLoad: () -> Unit = {},
) {
    constructor(context: Context) : this(DomainCorpusDatabase(context.applicationContext))
    internal constructor(context: Context, databaseName: String) :
        this(DomainCorpusDatabase(context.applicationContext, databaseName))
    internal constructor(context: Context, databaseName: String, beforeIndexLoad: () -> Unit) :
        this(DomainCorpusDatabase(context.applicationContext, databaseName), beforeIndexLoad)

    private val indexDispatcherDelegate = lazy {
        Executors.newSingleThreadExecutor { task -> Thread({
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "domain-index-builder").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
    private val indexDispatcher by indexDispatcherDelegate
    private val indexScopeDelegate = lazy { CoroutineScope(SupervisorJob() + indexDispatcher) }
    private val indexScope by indexScopeDelegate
    internal fun close() {
        if (indexScopeDelegate.isInitialized()) indexScope.cancel()
        if (indexDispatcherDelegate.isInitialized()) indexDispatcher.close()
        dbHelper?.close()
    }

    private val mutex = Mutex()
    private val _revision = MutableStateFlow(0L)
    open val revision: StateFlow<Long> = _revision.asStateFlow()
    private val mutableReferenceRevision = MutableStateFlow(0L)
    internal val referenceRevision = mutableReferenceRevision.asStateFlow()

    internal suspend fun referenceDocuments(beforeId: Long? = null): List<DomainReferenceDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(dbHelper).readableDatabase.rawQuery(
                "SELECT id,title,kind,enabled,length(body) FROM domain_reference_documents" +
                    (if (beforeId == null) "" else " WHERE id < ?") + " ORDER BY id DESC LIMIT 50",
                beforeId?.let { arrayOf(it.toString()) },
            ).use { rows -> buildList {
                while (rows.moveToNext()) add(DomainReferenceDocument(rows.getLong(0), rows.getString(1),
                    ReferenceDocumentKind.valueOf(rows.getString(2)), rows.getInt(3) != 0, rows.getInt(4)))
            } }
        }
    }

    internal suspend fun referenceDocumentText(id: Long): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(dbHelper).readableDatabase.rawQuery(
                "SELECT body FROM domain_reference_documents WHERE id=?", arrayOf(id.toString()),
            ).use { rows -> require(rows.moveToFirst()); rows.getString(0) }
        }
    }

    internal suspend fun saveReferenceDocument(id: Long?, title: String, kind: ReferenceDocumentKind,
        body: String, creationRequestId: String? = null): Long = withContext(Dispatchers.IO) {
        val name = title.trim(); val text = body.trim()
        require(id == null || id > 0)
        require(id == null || creationRequestId == null)
        creationRequestId?.let { require(UUID.fromString(it).toString() == it) }
        require(name.isNotBlank() && name.length <= 80 && name.none { it.code < 32 } &&
            validContextUnicode(name) && !containsNativeContextCredentialLikeText(name))
        require(text.isNotBlank() && text.length <= MAX_REFERENCE_DOCUMENT_CHARS)
        require(!containsNativeContextCredentialLikeText(text) && validContextUnicode(text) && text.none { (it.code < 32 && it !in "\n\r\t") || it.code == 127 })
        mutex.withLock {
            val values = ContentValues().apply {
                put("title", name); put("kind", kind.name); put("body", text)
            }
            val db = requireNotNull(dbHelper).writableDatabase
            var savedId = 0L
            db.beginTransaction()
            try {
                val mappedId = if (creationRequestId != null) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS domain_reference_save_requests (request_id TEXT PRIMARY KEY NOT NULL,document_id INTEGER NOT NULL UNIQUE)")
                    db.rawQuery("SELECT document_id FROM domain_reference_save_requests WHERE request_id=?",
                        arrayOf(creationRequestId)).use { rows -> if (rows.moveToFirst()) rows.getLong(0) else null }
                } else null
                val existingId = id ?: mappedId
                if (existingId != null) {
                    if (db.update("domain_reference_documents", values, "id=?", arrayOf(existingId.toString())) != 1)
                        throw ReferenceDocumentWasRemovedException()
                    savedId = existingId
                } else {
                    values.put("enabled", 0)
                    savedId = db.insertOrThrow("domain_reference_documents", null, values)
                    check(savedId > 0)
                    if (creationRequestId != null) {
                        val request = ContentValues().apply {
                            put("request_id", creationRequestId); put("document_id", savedId)
                        }
                        check(db.insertOrThrow("domain_reference_save_requests", null, request) > 0)
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            mutableReferenceRevision.value++
            savedId
        }
    }

    internal suspend fun enableReferenceDocument(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
            require(requireNotNull(dbHelper).writableDatabase.update("domain_reference_documents", values,
                "id=?", arrayOf(id.toString())) == 1)
            mutableReferenceRevision.value++
        }
    }

    internal suspend fun removeReferenceDocument(id: Long): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireNotNull(dbHelper).writableDatabase.delete("domain_reference_documents", "id=?", arrayOf(id.toString()))
            mutableReferenceRevision.value++
        }
    }

    /** Read once before connecting; neither capture nor native audio output waits on this database. */
    internal suspend fun prepareNativeReferences(source: String, target: String, style: TranslationStyle): NativeReferenceSnapshot =
        withContext(Dispatchers.IO) {
            // Await the initial/cached index before taking the reference snapshot. Never
            // acquire the mutex first: prepareActiveIndex owns it on the index dispatcher.
            prepareActiveIndex()
            mutex.withLock {
                val db = requireNotNull(dbHelper).readableDatabase
                val documents = db.rawQuery("SELECT count(*) FROM domain_reference_documents WHERE enabled=1", null)
                    .use { it.moveToFirst(); it.getInt(0) }
                val entries = mutableListOf<NativeReferenceEntry>()
                for (kind in ReferenceDocumentKind.entries) {
                    db.rawQuery("SELECT title,kind,body FROM domain_reference_documents WHERE enabled=1 AND kind=? AND length(body)<=64000 ORDER BY id DESC LIMIT 5", arrayOf(kind.name))
                        .use { rows -> while (rows.moveToNext()) entries += NativeReferenceEntry(rows.getString(0), rows.getString(1), rows.getString(2)) }
                }
                val snapshot = publishedIndex
                val active = snapshot.profiles[normalizeSourceLanguageTag(source) to normalizeTargetLanguageTag(target)]
                    ?.takeIf { style == TranslationStyle.AUTO || it.profile.style == TranslationStyle.AUTO || it.profile.style == style }
                active?.pairs?.take(3)?.forEach { pair ->
                    val exampleStyle = pair.reviewedStyle ?: active.profile.style
                    if (style == TranslationStyle.AUTO || exampleStyle == TranslationStyle.AUTO || style == exampleStyle)
                        entries += NativeReferenceEntry(active.profile.name,
                            "TRANSLATION_EXAMPLE", "원문: ${pair.sourceText}\n번역: ${pair.targetText}")
                }
                val prepared = prepareNativeReferencePayload(entries)
                NativeReferenceSnapshot(prepared.payload, prepared.includedEntries, documents + (active?.pairs?.size ?: 0),
                    mutableReferenceRevision.value, snapshot.revision, prepared.omittedTermLines)
            }
        }

    // In-memory cache for active profiles to optimize lookup performance
    private data class CachedPair(
        val sourceText: String,
        val targetText: String,
        val sourceNfcTrimmed: String,
        val tokens: Set<String>,
        val reviewedStyle: TranslationStyle? = null,
    )

    private data class CachedActiveProfile(
        val profile: DomainCorpusProfile,
        val pairs: List<CachedPair>,
        val exactMap: Map<String, CachedPair>,
        val lexicalIndex: BoundedDomainLexicalIndex,
    )

    private var activeProfilesCache: Map<Pair<String, String>, CachedActiveProfile>? = null
    private data class PublishedIndex(val profiles: Map<Pair<String, String>, CachedActiveProfile>, val revision: Long) {
        // Built with the index, never by the real-time lookup. Stable after an unchanged restart.
        val identity = automaticExampleHash(buildString {
            profiles.entries.sortedBy { it.key.first + ":" + it.key.second }.forEach { (_, cached) ->
                append(cached.profile.toString()); append('\u0000')
                cached.pairs.forEach { pair ->
                    append(pair.sourceNfcTrimmed); append('\u0000'); append(pair.targetText); append('\u0000')
                    append(pair.reviewedStyle?.name.orEmpty()); append('\u0000')
                }
            }
        })
    }
    @Volatile private var publishedIndex = PublishedIndex(emptyMap(), 0)
    internal fun automaticExampleDomain(): Pair<Long, String> = publishedIndex.let { it.revision to it.identity }
    init {
        if (dbHelper != null) indexScope.launch {
            runCatching { prepareActiveIndex() }.onFailure {
                RuntimeDiagnosticLog.record("domain_corpus", "index_prepare_failed:${it.javaClass.simpleName}")
            }
        }
    }

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
    ): DomainImportResult = withContext(indexDispatcher) {
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
            val imported = try {
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
            publishActiveIndexLocked()
            imported
        }
    }

    /** PoC review workflow: copy the active profile; never overwrite its original rows. */
    suspend fun applyReviewedComparison(comparison: ShadowComparison, corrected: String,
        humanReviewed: Boolean, nativeAdmission: NativeComparisonCommitAdmission? = null): DomainLearningRevision = withContext(indexDispatcher) {
        require(comparison.nativeIdentity == null || nativeAdmission != null) { "중계 예문의 현재 검수 권한을 확인하세요." }
        val pair = reviewedDomainComparisonPair(comparison, corrected, humanReviewed)
        mutex.withLock {
            check(_revision.value == comparison.corpusRevision) { "비교 이후 자료가 변경됐습니다. 새 비교로 다시 검수하세요." }
            val source = normalizeSourceLanguageTag(comparison.source)
            val target = normalizeTargetLanguageTag(comparison.target)
            val active = getOrLoadActiveProfilesCacheLocked()[source to target]
                ?: error("이 언어 쌍의 활성 도메인 자료를 먼저 준비하세요.")
            check(comparison.style == TranslationStyle.AUTO || active.profile.style == TranslationStyle.AUTO || comparison.style == active.profile.style) {
                "비교 문체와 활성 자료의 문체가 다릅니다."
            }
            val pairs = active.pairs.filterNot { it.sourceNfcTrimmed == pair.normalizedSource } +
                CachedPair(pair.original, pair.corrected, pair.normalizedSource,
                    extractMeaningfulTokens(pair.normalizedSource),
                    comparison.style.takeUnless { it == TranslationStyle.AUTO })
            check(pairs.size <= DomainCorpusFormat.MAX_PAIRS)
            val serialized = pairs.joinToString("\n", postfix = "\n") { it.sourceText + "\t" + it.targetText }
            check(DomainCorpusFormat.parseAndValidate(serialized.byteInputStream()) is DomainCorpusFormat.ParseResult.Success) {
                "자료 용량 또는 예문 형식을 확인하세요."
            }
            val db = requireNotNull(dbHelper).writableDatabase
            var newId = 0L
            db.beginTransaction()
            var transactionOpen = true
            try {
                newId = db.insertOrThrow("domain_profiles", null, ContentValues().apply {
                    put("name", active.profile.name.take(70) + " · 검수")
                    put("description", active.profile.description)
                    put("source_lang", source); put("target_lang", target); put("style", active.profile.style.name)
                    put("pair_count", pairs.size); put("active", 0); put("created_at", System.currentTimeMillis())
                })
                db.compileStatement("INSERT INTO domain_pairs (profile_id, source_text, target_text, source_nfc, reviewed_style) VALUES (?, ?, ?, ?, ?)").use { statement ->
                    pairs.forEach { saved ->
                        statement.bindLong(1, newId); statement.bindString(2, saved.sourceText); statement.bindString(3, saved.targetText)
                        statement.bindString(4, saved.sourceNfcTrimmed)
                        if (saved.reviewedStyle == null) statement.bindNull(5) else statement.bindString(5, saved.reviewedStyle.name)
                        statement.executeInsert(); statement.clearBindings()
                    }
                }
                db.insertOrThrow("domain_learning_revisions", null, ContentValues().apply {
                    put("profile_id", newId); put("parent_profile_id", active.profile.id); put("created_at", System.currentTimeMillis())
                })
                val coroutine = kotlinx.coroutines.currentCoroutineContext()
                val activate = {
                    coroutine.ensureActive()
                    db.execSQL("UPDATE domain_profiles SET active = 0 WHERE source_lang = ? AND target_lang = ?", arrayOf(source, target))
                    db.execSQL("UPDATE domain_profiles SET active = 1 WHERE id = ?", arrayOf(newId))
                    db.setTransactionSuccessful()
                    transactionOpen = false
                    db.endTransaction()
                }
                if (nativeAdmission == null) activate() else nativeAdmission.commit(activate)
            } finally { if (transactionOpen) db.endTransaction() }
            publishActiveIndexLocked(excludeLanguagePair = source to target)
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

    suspend fun rollbackLearning(change: DomainLearningRevision): Unit = withContext(indexDispatcher) {
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
            publishActiveIndexLocked(excludeProfileId = change.profileId)
        }
    }

    open suspend fun activate(id: Long): Unit = withContext(indexDispatcher) {
        mutex.withLock {
            val db = requireNotNull(dbHelper).writableDatabase
            var activatedPair: Pair<String, String>? = null
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
                    activatedPair = sourceLang to targetLang
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            publishActiveIndexLocked(excludeLanguagePair = activatedPair)
        }
    }

    open suspend fun deactivate(id: Long): Unit = withContext(indexDispatcher) {
        mutex.withLock {
            val db = requireNotNull(dbHelper).writableDatabase
            db.execSQL("UPDATE domain_profiles SET active = 0 WHERE id = ?", arrayOf(id.toString()))
            publishActiveIndexLocked(excludeProfileId = id)
        }
    }

    open suspend fun remove(id: Long): Unit = withContext(indexDispatcher) {
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
            publishActiveIndexLocked(excludeProfileId = id)
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
    open suspend fun prepareActiveIndex(): Long = withContext(indexDispatcher) {
        mutex.withLock {
            publishedIndex = PublishedIndex(getOrLoadActiveProfilesCacheLocked(), _revision.value)
            publishedIndex.revision
        }
    }

    open suspend fun match(
        text: String,
        source: String,
        target: String,
        style: TranslationStyle,
    ): DomainCorpusMatch = withContext(Dispatchers.Default) {
        val snapshot = publishedIndex
        if (text.isBlank()) return@withContext DomainCorpusMatch(null, "", snapshot.revision)

        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
        val normSource = normalizeSourceLanguageTag(source)
        val normTarget = normalizeTargetLanguageTag(target)

        run {
            val active = snapshot.profiles[normSource to normTarget] ?: return@withContext DomainCorpusMatch(null, "", snapshot.revision)

            // 1. Exact substitution: only active matching domain/language + style and NFC+trim exact whole source
            // Current request style authoritative; exact only same style or request AUTO. Never fuzzy or ASR correction.
            val exact = active.exactMap[normalized]
            val exactStyle = exact?.reviewedStyle ?: active.profile.style
            if (exact != null && (style == TranslationStyle.AUTO || style == exactStyle)) {
                return@withContext DomainCorpusMatch(exactTranslation = exact.targetText, hints = "", revision = snapshot.revision)
            }

            // If request style is specific and conflicts with profile style, do not provide mismatched style hints
            if (style != TranslationStyle.AUTO && active.profile.style != TranslationStyle.AUTO && active.profile.style != style) {
                return@withContext DomainCorpusMatch(null, "", snapshot.revision)
            }

            val hintsObj = JSONObject().apply {
                put("domain", active.profile.name)
                if (active.profile.description.isNotBlank()) {
                    put("description", active.profile.description)
                }
            }

            // 2. Hints retrieval: up to 3 relevant full pairs, token/CJK overlap threshold avoids unrelated examples
            val inputTokens = extractMeaningfulTokens(normalized.take(4096)).take(64).toSet()
            val candidateIds = active.lexicalIndex.candidates(inputTokens)
            val scoredCandidates = ArrayList<Pair<CachedPair, Double>>()
            if (inputTokens.isNotEmpty()) {
                for (id in candidateIds) {
                    val cand = active.pairs[id]
                    if (style != TranslationStyle.AUTO && cand.reviewedStyle != null && cand.reviewedStyle != style) continue
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
            if (topCandidates.isEmpty()) return@withContext DomainCorpusMatch(null, "", snapshot.revision, candidateIds.size)

            // Include whole examples within the fixed realtime reference budget.
            val examplesArray = JSONArray()
            for ((cand, _) in topCandidates) {
                val obj = JSONObject().apply {
                    put("source", cand.sourceText)
                    put("translation", cand.targetText)
                }
                val testObj = JSONObject(hintsObj.toString())
                val testExamples = JSONArray(examplesArray.toString()).put(obj)
                testObj.put("examples", testExamples)
                if (testObj.toString().length <= minOf(600, DomainCorpusFormat.MAX_HINTS_LENGTH)) {
                    examplesArray.put(obj)
                } else {
                    continue
                }
            }

            if (examplesArray.length() > 0) {
                hintsObj.put("examples", examplesArray)
            }

            val hintsString = if (examplesArray.length() > 0) hintsObj.toString() else ""
            DomainCorpusMatch(exactTranslation = null, hints = hintsString, revision = snapshot.revision,
                candidatesVisited = candidateIds.size)
        }
    }

    private fun publishActiveIndexLocked(excludeProfileId: Long? = null,
        excludeLanguagePair: Pair<String, String>? = null) {
        activeProfilesCache = null
        val nextRevision = _revision.value + 1
        // Withdraw committed replacements/deletions before rebuilding, including its failure path.
        if (excludeProfileId != null || excludeLanguagePair != null) {
            publishedIndex = PublishedIndex(publishedIndex.profiles.filter { (languages, cached) ->
                cached.profile.id != excludeProfileId && languages != excludeLanguagePair
            }, nextRevision)
            _revision.value = nextRevision
        }
        val profiles = getOrLoadActiveProfilesCacheLocked()
        publishedIndex = PublishedIndex(profiles, nextRevision)
        _revision.value = nextRevision
    }

    private fun getOrLoadActiveProfilesCacheLocked(): Map<Pair<String, String>, CachedActiveProfile> {
        val existing = activeProfilesCache
        if (existing != null) return existing
        beforeIndexLoad()
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
            val exactMap = HashMap<String, CachedPair>()

            db.rawQuery(
                "SELECT source_text, target_text, source_nfc, reviewed_style FROM domain_pairs WHERE profile_id = ?",
                arrayOf(profile.id.toString()),
            ).use { pc ->
                while (pc.moveToNext()) {
                    val sText = pc.getString(0)
                    val tText = pc.getString(1)
                    val sNfc = pc.getString(2)
                    val saved = CachedPair(
                        sourceText = sText,
                        targetText = tText,
                        sourceNfcTrimmed = sNfc,
                        tokens = extractMeaningfulTokens(sNfc),
                        reviewedStyle = if (pc.isNull(3)) null else TranslationStyle.valueOf(pc.getString(3)),
                    )
                    exactMap[sNfc] = saved
                    pairs.add(saved)
                }
            }

            map[profile.sourceLanguageTag to profile.targetLanguageTag] = CachedActiveProfile(
                profile = profile,
                pairs = pairs.toList(),
                exactMap = exactMap.toMap(),
                lexicalIndex = BoundedDomainLexicalIndex(pairs.map { it.tokens }),
            )
        }

        activeProfilesCache = map.toMap()
        return requireNotNull(activeProfilesCache)
    }

    private fun extractMeaningfulTokens(text: String): Set<String> {
        val tokens = linkedSetOf<String>()
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
        val exactMap = HashMap<String, CachedPair>()
        for ((s, t) in rawPairs) {
            val sNfc = Normalizer.normalize(s.trim(), Normalizer.Form.NFC).trim()
            val tTrimmed = t.trim()
            val saved = CachedPair(
                sourceText = s.trim(),
                targetText = tTrimmed,
                sourceNfcTrimmed = sNfc,
                tokens = extractMeaningfulTokens(sNfc),
            )
            exactMap[sNfc] = saved
            pairs.add(saved)
        }
        val cached = CachedActiveProfile(profile, pairs.toList(), exactMap.toMap(), BoundedDomainLexicalIndex(pairs.map { it.tokens }))
        val current = activeProfilesCache?.toMutableMap() ?: HashMap()
        current[profile.sourceLanguageTag to profile.targetLanguageTag] = cached
        activeProfilesCache = current.toMap()
        _revision.value += 1
        publishedIndex = PublishedIndex(requireNotNull(activeProfilesCache), _revision.value)
    }

    internal fun invalidateCacheForTest() {
        activeProfilesCache = null
        _revision.value += 1
        publishedIndex = PublishedIndex(emptyMap(), _revision.value)
    }

    internal fun clearActiveProfilesForTest() {
        activeProfilesCache = emptyMap()
        _revision.value += 1
        publishedIndex = PublishedIndex(emptyMap(), _revision.value)
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
    SQLiteOpenHelper(context, databaseName, null, 4) {

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
                reviewed_style TEXT,
                FOREIGN KEY(profile_id) REFERENCES domain_profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_profiles_lang_active ON domain_profiles(source_lang, target_lang, active)")
        db.execSQL("CREATE INDEX idx_pairs_profile_id ON domain_pairs(profile_id)")
        db.execSQL("CREATE INDEX idx_pairs_nfc_lookup ON domain_pairs(profile_id, source_nfc)")
        createLearningHistory(db)
        createReferenceDocuments(db)
    }

    private fun createLearningHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE domain_learning_revisions (profile_id INTEGER PRIMARY KEY, parent_profile_id INTEGER NOT NULL, created_at INTEGER NOT NULL, FOREIGN KEY(profile_id) REFERENCES domain_profiles(id) ON DELETE CASCADE)")
    }
    private fun createReferenceDocuments(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE domain_reference_documents (id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX idx_reference_documents_active_kind ON domain_reference_documents(enabled,kind,id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion in 1..3 && newVersion == 4) {
            if (oldVersion == 1) createLearningHistory(db)
            if (oldVersion <= 2) createReferenceDocuments(db)
            db.execSQL("ALTER TABLE domain_pairs ADD COLUMN reviewed_style TEXT")
            return
        }
        throw SQLiteException("Destructive database schema changes are prohibited to preserve user domain data.")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }
}
