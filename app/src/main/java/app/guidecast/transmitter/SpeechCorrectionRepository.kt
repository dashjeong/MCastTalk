package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.Normalizer
import java.util.Collections
import java.util.IllformedLocaleException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SpeechCorrectionEntry(
    val id: Long,
    val profile: String,
    val languageTag: String,
    val recognizedText: String,
    val correctedText: String,
    val hint: String? = null,
    val enabled: Boolean = true,
    val sourceKey: String? = null,
    val updatedAt: Long,
)

data class SpeechCorrectionDraft(
    val profile: String,
    val languageTag: String,
    val recognizedText: String,
    val correctedText: String,
    val hint: String? = null,
    val enabled: Boolean = true,
    val sourceKey: String? = null,
)

enum class SpeechCorrectionValidationCode {
    PROFILE_REQUIRED,
    PROFILE_TOO_LONG,
    LANGUAGE_TAG_INVALID,
    RECOGNIZED_TEXT_REQUIRED,
    RECOGNIZED_TEXT_TOO_LONG,
    CORRECTED_TEXT_REQUIRED,
    CORRECTED_TEXT_TOO_LONG,
    HINT_EMPTY,
    HINT_TOO_LONG,
    SOURCE_KEY_TOO_LONG,
    QUERY_TOO_LONG,
    TEXT_NOT_NFC,
    CONTROL_CHARACTER,
    INVALID_UNICODE,
    INVALID_ID,
    INVALID_UPDATED_AT,
    MAX_ENTRIES,
    MAX_HINTS,
    MAX_HINT_CHARACTERS,
    EMPTY_BATCH,
    PROFILE_MISMATCH,
    DUPLICATE_ENTRY,
    ORIGINAL_TEXT_IMMUTABLE,
}

class SpeechCorrectionValidationException(
    val code: SpeechCorrectionValidationCode,
) : IllegalArgumentException(code.message)

private val SpeechCorrectionValidationCode.message: String
    get() = when (this) {
        SpeechCorrectionValidationCode.PROFILE_REQUIRED -> "프로필을 입력하세요."
        SpeechCorrectionValidationCode.PROFILE_TOO_LONG -> "프로필은 80자 이하여야 합니다."
        SpeechCorrectionValidationCode.LANGUAGE_TAG_INVALID -> "입력 언어 태그를 확인하세요."
        SpeechCorrectionValidationCode.RECOGNIZED_TEXT_REQUIRED -> "인식문을 입력하세요."
        SpeechCorrectionValidationCode.RECOGNIZED_TEXT_TOO_LONG -> "인식문은 2,000자 이하여야 합니다."
        SpeechCorrectionValidationCode.CORRECTED_TEXT_REQUIRED -> "교정문을 입력하세요."
        SpeechCorrectionValidationCode.CORRECTED_TEXT_TOO_LONG -> "교정문은 2,000자 이하여야 합니다."
        SpeechCorrectionValidationCode.HINT_EMPTY -> "빈 인식 힌트는 저장할 수 없습니다."
        SpeechCorrectionValidationCode.HINT_TOO_LONG -> "인식 힌트는 80자 이하여야 합니다."
        SpeechCorrectionValidationCode.SOURCE_KEY_TOO_LONG -> "원문 연결 키는 200자 이하여야 합니다."
        SpeechCorrectionValidationCode.QUERY_TOO_LONG -> "검색어는 2,000자 이하여야 합니다."
        SpeechCorrectionValidationCode.TEXT_NOT_NFC -> "텍스트를 NFC 유니코드 형식으로 입력하세요."
        SpeechCorrectionValidationCode.CONTROL_CHARACTER -> "제어 문자는 저장할 수 없습니다."
        SpeechCorrectionValidationCode.INVALID_UNICODE -> "올바르지 않은 유니코드 문자열입니다."
        SpeechCorrectionValidationCode.INVALID_ID -> "교정 기록 ID를 확인하세요."
        SpeechCorrectionValidationCode.INVALID_UPDATED_AT -> "교정 기록 시각을 확인하세요."
        SpeechCorrectionValidationCode.MAX_ENTRIES -> "교정 기록은 최대 500개까지 저장할 수 있습니다."
        SpeechCorrectionValidationCode.MAX_HINTS -> "프로필과 언어별 인식 힌트는 최대 32개입니다."
        SpeechCorrectionValidationCode.MAX_HINT_CHARACTERS -> "프로필과 언어별 인식 힌트 합계는 최대 1,000자입니다."
        SpeechCorrectionValidationCode.EMPTY_BATCH -> "빈 목록으로 프로필을 바꿀 수 없습니다."
        SpeechCorrectionValidationCode.PROFILE_MISMATCH -> "가져온 기록의 프로필이 선택한 프로필과 다릅니다."
        SpeechCorrectionValidationCode.DUPLICATE_ENTRY -> "같은 프로필·언어·인식문의 교정이 이미 있습니다."
        SpeechCorrectionValidationCode.ORIGINAL_TEXT_IMMUTABLE -> "저장된 원래 인식문은 변경할 수 없습니다."
    }

/** Pure validation used by editor and import preview before any SQLite write begins. */
object SpeechCorrectionValidation {
    const val MAX_ENTRIES = 500
    const val MAX_HINTS_PER_PROFILE_LANGUAGE = 32
    const val MAX_HINT_CODE_POINTS_PER_PROFILE_LANGUAGE = 1_000
    const val MAX_HINT_CODE_POINTS = 80
    const val MAX_TEXT_CODE_POINTS = 2_000
    const val MAX_PROFILE_CODE_POINTS = 80
    const val MAX_SOURCE_KEY_CODE_POINTS = 200

    fun draft(value: SpeechCorrectionDraft): SpeechCorrectionDraft {
        profile(value.profile)
        val canonicalLanguageTag = languageTag(value.languageTag)
        requiredText(
            value.recognizedText,
            SpeechCorrectionValidationCode.RECOGNIZED_TEXT_REQUIRED,
            SpeechCorrectionValidationCode.RECOGNIZED_TEXT_TOO_LONG,
        )
        requiredText(
            value.correctedText,
            SpeechCorrectionValidationCode.CORRECTED_TEXT_REQUIRED,
            SpeechCorrectionValidationCode.CORRECTED_TEXT_TOO_LONG,
        )
        value.hint?.let {
            text(it)
            if (it.isBlank()) fail(SpeechCorrectionValidationCode.HINT_EMPTY)
            if (codePointLength(it) > MAX_HINT_CODE_POINTS) {
                fail(SpeechCorrectionValidationCode.HINT_TOO_LONG)
            }
        }
        value.sourceKey?.let {
            text(it)
            if (codePointLength(it) > MAX_SOURCE_KEY_CODE_POINTS) {
                fail(SpeechCorrectionValidationCode.SOURCE_KEY_TOO_LONG)
            }
        }
        return if (canonicalLanguageTag == value.languageTag) value else value.copy(languageTag = canonicalLanguageTag)
    }

    fun entry(value: SpeechCorrectionEntry): SpeechCorrectionEntry {
        if (value.id <= 0L) fail(SpeechCorrectionValidationCode.INVALID_ID)
        if (value.updatedAt < 0L) fail(SpeechCorrectionValidationCode.INVALID_UPDATED_AT)
        val validDraft = draft(value.toDraft())
        return if (validDraft.languageTag == value.languageTag) value else {
            value.copy(languageTag = validDraft.languageTag)
        }
    }

    fun profile(value: String): String {
        text(value)
        if (value.isBlank()) fail(SpeechCorrectionValidationCode.PROFILE_REQUIRED)
        if (codePointLength(value) > MAX_PROFILE_CODE_POINTS) {
            fail(SpeechCorrectionValidationCode.PROFILE_TOO_LONG)
        }
        return value
    }

    fun languageTag(value: String): String {
        text(value)
        if (value.isBlank() || codePointLength(value) > 35) {
            fail(SpeechCorrectionValidationCode.LANGUAGE_TAG_INVALID)
        }
        val parsed = try {
            Locale.Builder().setLanguageTag(value).build()
        } catch (_: IllformedLocaleException) {
            fail(SpeechCorrectionValidationCode.LANGUAGE_TAG_INVALID)
        }
        if (parsed.language.isBlank() || parsed.toLanguageTag() == "und") {
            fail(SpeechCorrectionValidationCode.LANGUAGE_TAG_INVALID)
        }
        val canonical = parsed.toLanguageTag()
        // The semantic assembler intentionally emits the Korean base tag while Android recognition
        // is configured with ko-KR. They describe the same supported input in this app. Do not
        // generalize this alias to other languages, regions or scripts.
        return if (canonical.equals("ko", ignoreCase = true) ||
            canonical.equals("ko-KR", ignoreCase = true)
        ) {
            "ko-KR"
        } else {
            canonical
        }
    }

    fun query(value: String): String {
        text(value)
        if (codePointLength(value) > MAX_TEXT_CODE_POINTS) {
            fail(SpeechCorrectionValidationCode.QUERY_TOO_LONG)
        }
        return value
    }

    fun batch(profile: String, values: List<SpeechCorrectionDraft>): List<SpeechCorrectionDraft> {
        profile(profile)
        if (values.isEmpty()) fail(SpeechCorrectionValidationCode.EMPTY_BATCH)
        if (values.size > MAX_ENTRIES) fail(SpeechCorrectionValidationCode.MAX_ENTRIES)
        val normalized = values.map {
            val valid = draft(it)
            if (it.profile != profile) fail(SpeechCorrectionValidationCode.PROFILE_MISMATCH)
            valid
        }
        val uniqueKeys = mutableSetOf<Triple<String, String, String>>()
        normalized.forEach {
            if (!uniqueKeys.add(Triple(it.profile, languageKey(it.languageTag), it.recognizedText))) {
                fail(SpeechCorrectionValidationCode.DUPLICATE_ENTRY)
            }
        }
        checkHintBudget(normalized)
        return normalized
    }

    fun checkHintBudget(values: Iterable<SpeechCorrectionDraft>) {
        val budgets = mutableMapOf<Pair<String, String>, HintBudget>()
        values.asSequence().filter { it.enabled && it.hint != null }.forEach { draft ->
            val key = draft.profile to languageKey(draft.languageTag)
            val budget = budgets.getOrPut(key) { HintBudget() }
            budget.count++
            budget.codePoints += codePointLength(checkNotNull(draft.hint))
            if (budget.count > MAX_HINTS_PER_PROFILE_LANGUAGE) {
                fail(SpeechCorrectionValidationCode.MAX_HINTS)
            }
            if (budget.codePoints > MAX_HINT_CODE_POINTS_PER_PROFILE_LANGUAGE) {
                fail(SpeechCorrectionValidationCode.MAX_HINT_CHARACTERS)
            }
        }
    }

    fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

    /** Canonical tags are case-insensitive; only the app's known `ko`/`ko-KR` pair is aliased. */
    fun languageKey(value: String): String = runCatching { languageTag(value) }
        .getOrElse { value.lowercase(Locale.ROOT) }
        .lowercase(Locale.ROOT)

    private fun requiredText(
        value: String,
        emptyCode: SpeechCorrectionValidationCode,
        longCode: SpeechCorrectionValidationCode,
    ) {
        text(value)
        if (value.isBlank()) fail(emptyCode)
        if (codePointLength(value) > MAX_TEXT_CODE_POINTS) fail(longCode)
    }

    private fun text(value: String) {
        if (!hasWellFormedSurrogates(value)) fail(SpeechCorrectionValidationCode.INVALID_UNICODE)
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            fail(SpeechCorrectionValidationCode.TEXT_NOT_NFC)
        }
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (Character.isISOControl(codePoint)) fail(SpeechCorrectionValidationCode.CONTROL_CHARACTER)
            if (codePoint == 0x061C || codePoint == 0x200E || codePoint == 0x200F ||
                codePoint in 0x202A..0x202E || codePoint in 0x2066..0x2069
            ) {
                fail(SpeechCorrectionValidationCode.CONTROL_CHARACTER)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun hasWellFormedSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                Character.isHighSurrogate(character) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(character) -> return false
                else -> index++
            }
        }
        return true
    }

    private fun fail(code: SpeechCorrectionValidationCode): Nothing =
        throw SpeechCorrectionValidationException(code)

    private data class HintBudget(var count: Int = 0, var codePoints: Int = 0)
}

private fun SpeechCorrectionEntry.toDraft() = SpeechCorrectionDraft(
    profile = profile,
    languageTag = languageTag,
    recognizedText = recognizedText,
    correctedText = correctedText,
    hint = hint,
    enabled = enabled,
    sourceKey = sourceKey,
)

/**
 * App-private correction storage and a bounded, immutable active-profile recognition snapshot.
 * Construction performs no disk IO. Call [refresh] from application initialization before using a
 * persisted profile. Recognition-facing methods never open SQLite and never throw.
 */
class SpeechCorrectionRepository private constructor(
    private val storage: SpeechCorrectionStorage,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    constructor(context: Context) : this(
        storage = SQLiteSpeechCorrectionStorage(context.applicationContext, DATABASE_NAME),
        ioDispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )

    internal constructor(
        storage: SpeechCorrectionStorage,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(storage, Dispatchers.IO, clock)

    internal constructor(
        context: Context,
        databaseName: String,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(SQLiteSpeechCorrectionStorage(context.applicationContext, databaseName), Dispatchers.IO, clock)

    private val lock = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    private val mutableActiveProfile = MutableStateFlow(DEFAULT_PROFILE)

    @Volatile
    private var activeSnapshot = SpeechCorrectionSnapshot.empty()

    val revision: StateFlow<Long> = mutableRevision.asStateFlow()
    val activeProfile: StateFlow<String> = mutableActiveProfile.asStateFlow()

    suspend fun save(draft: SpeechCorrectionDraft, id: Long? = null): SpeechCorrectionEntry {
        val valid = SpeechCorrectionValidation.draft(draft)
        id?.let { if (it <= 0L) throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.INVALID_ID) }
        return write {
            storage.save(valid, id, clock()).also { reloadActiveSnapshotSafely() }
        }
    }

    suspend fun list(
        profile: String,
        languageTag: String? = null,
        query: String = "",
    ): List<SpeechCorrectionEntry> {
        val validProfile = SpeechCorrectionValidation.profile(profile)
        val validLanguage = languageTag?.let(SpeechCorrectionValidation::languageTag)
        val validQuery = SpeechCorrectionValidation.query(query)
        return read { storage.list(validProfile, validLanguage, validQuery) }
    }

    suspend fun profiles(): List<String> = read {
        (storage.profiles() + DEFAULT_PROFILE + mutableActiveProfile.value).distinct().sorted()
    }

    suspend fun delete(id: Long): SpeechCorrectionEntry? {
        if (id <= 0L) throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.INVALID_ID)
        return write { storage.delete(id).also { reloadActiveSnapshotSafely() } }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean): SpeechCorrectionEntry? {
        if (id <= 0L) throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.INVALID_ID)
        return write { storage.setEnabled(id, enabled, clock()).also { reloadActiveSnapshotSafely() } }
    }

    suspend fun restore(entry: SpeechCorrectionEntry): SpeechCorrectionEntry {
        val valid = SpeechCorrectionValidation.entry(entry)
        return write { storage.restore(valid).also { reloadActiveSnapshotSafely() } }
    }

    /** Returns the deleted rows so the caller can offer explicit undo. */
    suspend fun clear(profile: String): List<SpeechCorrectionEntry> {
        val valid = SpeechCorrectionValidation.profile(profile)
        return write { storage.clear(valid).also { reloadActiveSnapshotSafely() } }
    }

    /**
     * Atomic import commit for one reviewed profile. Other profiles remain unchanged. Every row and
     * aggregate bound is checked before the transaction deletes the old profile rows.
     */
    suspend fun replaceProfile(
        profile: String,
        drafts: List<SpeechCorrectionDraft>,
    ): List<SpeechCorrectionEntry> {
        val valid = SpeechCorrectionValidation.batch(profile, drafts)
        return write { storage.replaceProfile(profile, valid, clock()).also { reloadActiveSnapshotSafely() } }
    }

    suspend fun selectProfile(profile: String) {
        val valid = SpeechCorrectionValidation.profile(profile)
        write {
            storage.selectProfile(valid)
            mutableActiveProfile.value = valid
            reloadActiveSnapshotSafely()
        }
    }

    /** Reloads persisted profile and cache. A failure clears cache before being surfaced to UI. */
    suspend fun refresh() {
        withContext(ioDispatcher) {
            lock.withLock {
                try {
                    val profile = SpeechCorrectionValidation.profile(storage.activeProfile())
                    val snapshot = SpeechCorrectionSnapshot.create(storage.list(profile, null, ""))
                    mutableActiveProfile.value = profile
                    activeSnapshot = snapshot
                    bumpRevision()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failClosed()
                    throw failure
                }
            }
        }
    }

    /** Nonblocking, allocation-light STT lookup. It never performs IO or propagates DB failures. */
    fun recognitionHints(languageTag: String): List<String> = try {
        activeSnapshot.hints[SpeechCorrectionValidation.languageKey(languageTag)] ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Exact phrase candidates only. The repository never applies a correction or replays output. */
    fun correctionCandidates(
        recognizedText: String,
        languageTag: String,
    ): List<SpeechCorrectionEntry> = try {
        activeSnapshot.candidates[
            CandidateKey(SpeechCorrectionValidation.languageKey(languageTag), recognizedText)
        ] ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun <T> read(block: () -> T): T = withContext(ioDispatcher) {
        lock.withLock {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failClosed()
                throw failure
            }
        }
    }

    private suspend fun <T> write(block: () -> T): T = withContext(ioDispatcher) {
        lock.withLock {
            try {
                block().also { bumpRevision() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failClosed()
                throw failure
            }
        }
    }

    private fun reloadActiveSnapshotSafely() {
        activeSnapshot = try {
            SpeechCorrectionSnapshot.create(storage.list(mutableActiveProfile.value, null, ""))
        } catch (_: Exception) {
            SpeechCorrectionSnapshot.empty()
        }
    }

    private fun failClosed() {
        val hadRecognitionData = !activeSnapshot.isEmpty()
        activeSnapshot = SpeechCorrectionSnapshot.empty()
        if (hadRecognitionData) bumpRevision()
    }

    private fun bumpRevision() {
        mutableRevision.value = mutableRevision.value + 1L
    }

    companion object {
        const val DEFAULT_PROFILE = "기본"
        internal const val DATABASE_NAME = "speech-corrections.db"
    }
}

private data class CandidateKey(val languageTag: String, val recognizedText: String)

private data class SpeechCorrectionSnapshot(
    val hints: Map<String, List<String>>,
    val candidates: Map<CandidateKey, List<SpeechCorrectionEntry>>,
) {
    fun isEmpty(): Boolean = hints.isEmpty() && candidates.isEmpty()

    companion object {
        fun empty() = SpeechCorrectionSnapshot(emptyMap(), emptyMap())

        fun create(entries: List<SpeechCorrectionEntry>): SpeechCorrectionSnapshot {
            if (entries.size > SpeechCorrectionValidation.MAX_ENTRIES) {
                throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.MAX_ENTRIES)
            }
            entries.forEach { SpeechCorrectionValidation.entry(it) }
            SpeechCorrectionValidation.checkHintBudget(entries.map(SpeechCorrectionEntry::toDraft))

            val hints = linkedMapOf<String, MutableList<String>>()
            val candidates = linkedMapOf<CandidateKey, MutableList<SpeechCorrectionEntry>>()
            entries.asSequence().filter(SpeechCorrectionEntry::enabled).forEach { entry ->
                val language = SpeechCorrectionValidation.languageKey(entry.languageTag)
                entry.hint?.let { hint ->
                    val values = hints.getOrPut(language) { mutableListOf() }
                    if (hint !in values) values += hint
                }
                candidates.getOrPut(CandidateKey(language, entry.recognizedText)) { mutableListOf() } += entry
            }
            return SpeechCorrectionSnapshot(
                hints = immutableLists(hints),
                candidates = immutableLists(candidates),
            )
        }

        private fun <K, V> immutableLists(values: Map<K, MutableList<V>>): Map<K, List<V>> {
            val copy = LinkedHashMap<K, List<V>>(values.size)
            values.forEach { (key, list) -> copy[key] = Collections.unmodifiableList(ArrayList(list)) }
            return Collections.unmodifiableMap(copy)
        }
    }
}

internal interface SpeechCorrectionStorage {
    fun save(draft: SpeechCorrectionDraft, id: Long?, updatedAt: Long): SpeechCorrectionEntry
    fun list(profile: String, languageTag: String?, query: String): List<SpeechCorrectionEntry>
    fun profiles(): List<String>
    fun delete(id: Long): SpeechCorrectionEntry?
    fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): SpeechCorrectionEntry?
    fun restore(entry: SpeechCorrectionEntry): SpeechCorrectionEntry
    fun clear(profile: String): List<SpeechCorrectionEntry>
    fun replaceProfile(
        profile: String,
        drafts: List<SpeechCorrectionDraft>,
        updatedAt: Long,
    ): List<SpeechCorrectionEntry>
    fun activeProfile(): String
    fun selectProfile(profile: String)
}

private class SQLiteSpeechCorrectionStorage(
    context: Context,
    databaseName: String,
) : SpeechCorrectionStorage {
    private val helper = SpeechCorrectionDatabase(context, databaseName)

    override fun save(
        draft: SpeechCorrectionDraft,
        id: Long?,
        updatedAt: Long,
    ): SpeechCorrectionEntry = transaction { db ->
        val existing = if (id == null) null else requireEntry(db, id)
        if (existing == null) ensureEntryCapacity(db, additional = 1)
        else if (existing.recognizedText != draft.recognizedText) {
            throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.ORIGINAL_TEXT_IMMUTABLE)
        }
        ensureNoDuplicate(db, draft, excludingId = id)
        if (draft.enabled && draft.hint != null) ensureHintCapacity(db, draft, excludingId = id)
        val values = draft.values(updatedAt)
        val storedId = if (id == null) {
            db.insertOrThrow(TABLE_CORRECTIONS, null, values)
        } else {
            val count = db.update(TABLE_CORRECTIONS, values, "$COL_ID=?", arrayOf(id.toString()))
            check(count == 1) { "Correction entry is missing" }
            id
        }
        requireEntry(db, storedId)
    }

    override fun list(
        profile: String,
        languageTag: String?,
        query: String,
    ): List<SpeechCorrectionEntry> {
        val conditions = mutableListOf("$COL_PROFILE=?")
        val arguments = mutableListOf(profile)
        languageTag?.let {
            conditions += "$COL_LANGUAGE=? COLLATE NOCASE"
            arguments += it
        }
        if (query.isNotEmpty()) {
            val escaped = query.escapeLike()
            conditions += "($COL_RECOGNIZED LIKE ? ESCAPE '\\' OR $COL_CORRECTED LIKE ? ESCAPE '\\' OR $COL_HINT LIKE ? ESCAPE '\\')"
            repeat(3) { arguments += "%$escaped%" }
        }
        return helper.readableDatabase.rawQuery(
            "SELECT * FROM $TABLE_CORRECTIONS WHERE ${conditions.joinToString(" AND ")} " +
                "ORDER BY $COL_UPDATED_AT DESC,$COL_ID DESC LIMIT ${SpeechCorrectionValidation.MAX_ENTRIES}",
            arguments.toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry()) } }
    }

    override fun profiles(): List<String> = helper.readableDatabase.rawQuery(
        "SELECT DISTINCT $COL_PROFILE FROM $TABLE_CORRECTIONS ORDER BY $COL_PROFILE",
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    override fun delete(id: Long): SpeechCorrectionEntry? = transaction { db ->
        val existing = findEntry(db, id) ?: return@transaction null
        check(db.delete(TABLE_CORRECTIONS, "$COL_ID=?", arrayOf(id.toString())) == 1)
        existing
    }

    override fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long): SpeechCorrectionEntry? =
        transaction { db ->
            val existing = findEntry(db, id) ?: return@transaction null
            val updated = existing.copy(enabled = enabled, updatedAt = updatedAt)
            if (enabled && updated.hint != null) ensureHintCapacity(db, updated.toDraft(), excludingId = id)
            val count = db.update(
                TABLE_CORRECTIONS,
                ContentValues().apply {
                    put(COL_ENABLED, if (enabled) 1 else 0)
                    put(COL_UPDATED_AT, updatedAt)
                },
                "$COL_ID=?",
                arrayOf(id.toString()),
            )
            check(count == 1)
            updated
        }

    override fun restore(entry: SpeechCorrectionEntry): SpeechCorrectionEntry = transaction { db ->
        ensureEntryCapacity(db, additional = 1)
        ensureNoDuplicate(db, entry.toDraft(), excludingId = null)
        if (entry.enabled && entry.hint != null) ensureHintCapacity(db, entry.toDraft(), excludingId = null)
        val values = entry.toDraft().values(entry.updatedAt).apply { put(COL_ID, entry.id) }
        db.insertOrThrow(TABLE_CORRECTIONS, null, values)
        requireEntry(db, entry.id)
    }

    override fun clear(profile: String): List<SpeechCorrectionEntry> = transaction { db ->
        val entries = queryProfile(db, profile)
        db.delete(TABLE_CORRECTIONS, "$COL_PROFILE=?", arrayOf(profile))
        entries
    }

    override fun replaceProfile(
        profile: String,
        drafts: List<SpeechCorrectionDraft>,
        updatedAt: Long,
    ): List<SpeechCorrectionEntry> = transaction { db ->
        val otherCount = db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_CORRECTIONS WHERE $COL_PROFILE<>?",
            arrayOf(profile),
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        if (otherCount + drafts.size > SpeechCorrectionValidation.MAX_ENTRIES) {
            throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.MAX_ENTRIES)
        }
        db.delete(TABLE_CORRECTIONS, "$COL_PROFILE=?", arrayOf(profile))
        drafts.map { draft ->
            val id = db.insertOrThrow(TABLE_CORRECTIONS, null, draft.values(updatedAt))
            SpeechCorrectionEntry(
                id = id,
                profile = draft.profile,
                languageTag = draft.languageTag,
                recognizedText = draft.recognizedText,
                correctedText = draft.correctedText,
                hint = draft.hint,
                enabled = draft.enabled,
                sourceKey = draft.sourceKey,
                updatedAt = updatedAt,
            )
        }
    }

    override fun activeProfile(): String = helper.readableDatabase.rawQuery(
        "SELECT $COL_SETTING_VALUE FROM $TABLE_SETTINGS WHERE $COL_SETTING_KEY=?",
        arrayOf(SETTING_ACTIVE_PROFILE),
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else SpeechCorrectionRepository.DEFAULT_PROFILE
    }

    override fun selectProfile(profile: String) {
        helper.writableDatabase.insertWithOnConflict(
            TABLE_SETTINGS,
            null,
            ContentValues().apply {
                put(COL_SETTING_KEY, SETTING_ACTIVE_PROFILE)
                put(COL_SETTING_VALUE, profile)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { check(it != -1L) }
    }

    private fun ensureEntryCapacity(db: SQLiteDatabase, additional: Int) {
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE_CORRECTIONS", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (count + additional > SpeechCorrectionValidation.MAX_ENTRIES) {
            throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.MAX_ENTRIES)
        }
    }

    private fun ensureHintCapacity(
        db: SQLiteDatabase,
        draft: SpeechCorrectionDraft,
        excludingId: Long?,
    ) {
        val excluding = if (excludingId == null) "" else " AND $COL_ID<>?"
        val arguments = mutableListOf(draft.profile, draft.languageTag)
        excludingId?.let { arguments += it.toString() }
        val budget = db.rawQuery(
            "SELECT COUNT(*),COALESCE(SUM(LENGTH($COL_HINT)),0) FROM $TABLE_CORRECTIONS " +
                "WHERE $COL_PROFILE=? AND $COL_LANGUAGE=? COLLATE NOCASE AND $COL_ENABLED=1 " +
                "AND $COL_HINT IS NOT NULL$excluding",
            arguments.toTypedArray(),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) to cursor.getInt(1)
        }
        if (budget.first + 1 > SpeechCorrectionValidation.MAX_HINTS_PER_PROFILE_LANGUAGE) {
            throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.MAX_HINTS)
        }
        val newTotal = budget.second + SpeechCorrectionValidation.codePointLength(checkNotNull(draft.hint))
        if (newTotal > SpeechCorrectionValidation.MAX_HINT_CODE_POINTS_PER_PROFILE_LANGUAGE) {
            throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.MAX_HINT_CHARACTERS)
        }
    }

    private fun ensureNoDuplicate(
        db: SQLiteDatabase,
        draft: SpeechCorrectionDraft,
        excludingId: Long?,
    ) {
        val excluding = if (excludingId == null) "" else " AND $COL_ID<>?"
        val arguments = mutableListOf(draft.profile, draft.languageTag, draft.recognizedText)
        excludingId?.let { arguments += it.toString() }
        val duplicate = db.rawQuery(
            "SELECT 1 FROM $TABLE_CORRECTIONS WHERE $COL_PROFILE=? " +
                "AND $COL_LANGUAGE=? COLLATE NOCASE AND $COL_RECOGNIZED=?$excluding LIMIT 1",
            arguments.toTypedArray(),
        ).use(Cursor::moveToFirst)
        if (duplicate) {
            throw SpeechCorrectionValidationException(SpeechCorrectionValidationCode.DUPLICATE_ENTRY)
        }
    }

    private fun queryProfile(db: SQLiteDatabase, profile: String): List<SpeechCorrectionEntry> =
        db.rawQuery(
            "SELECT * FROM $TABLE_CORRECTIONS WHERE $COL_PROFILE=? ORDER BY $COL_UPDATED_AT DESC,$COL_ID DESC",
            arrayOf(profile),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.entry()) } }

    private fun findEntry(db: SQLiteDatabase, id: Long): SpeechCorrectionEntry? = db.rawQuery(
        "SELECT * FROM $TABLE_CORRECTIONS WHERE $COL_ID=?",
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.entry() else null }

    private fun requireEntry(db: SQLiteDatabase, id: Long): SpeechCorrectionEntry =
        checkNotNull(findEntry(db, id)) { "Correction entry is missing" }

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            block(db).also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun SpeechCorrectionDraft.values(updatedAt: Long) = ContentValues().apply {
        put(COL_PROFILE, profile)
        put(COL_LANGUAGE, languageTag)
        put(COL_RECOGNIZED, recognizedText)
        put(COL_CORRECTED, correctedText)
        if (hint == null) putNull(COL_HINT) else put(COL_HINT, hint)
        put(COL_ENABLED, if (enabled) 1 else 0)
        if (sourceKey == null) putNull(COL_SOURCE_KEY) else put(COL_SOURCE_KEY, sourceKey)
        put(COL_UPDATED_AT, updatedAt)
    }

    private fun Cursor.entry() = SpeechCorrectionEntry(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        profile = getString(getColumnIndexOrThrow(COL_PROFILE)),
        languageTag = getString(getColumnIndexOrThrow(COL_LANGUAGE)),
        recognizedText = getString(getColumnIndexOrThrow(COL_RECOGNIZED)),
        correctedText = getString(getColumnIndexOrThrow(COL_CORRECTED)),
        hint = getColumnIndexOrThrow(COL_HINT).let { index -> if (isNull(index)) null else getString(index) },
        enabled = getInt(getColumnIndexOrThrow(COL_ENABLED)) == 1,
        sourceKey = getColumnIndexOrThrow(COL_SOURCE_KEY).let { index -> if (isNull(index)) null else getString(index) },
        updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
    )

    private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

private class SpeechCorrectionDatabase(context: Context, databaseName: String) :
    SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE_CORRECTIONS (" +
                "$COL_ID INTEGER PRIMARY KEY AUTOINCREMENT," +
                "$COL_PROFILE TEXT NOT NULL," +
                "$COL_LANGUAGE TEXT NOT NULL COLLATE NOCASE," +
                "$COL_RECOGNIZED TEXT NOT NULL," +
                "$COL_CORRECTED TEXT NOT NULL," +
                "$COL_HINT TEXT," +
                "$COL_ENABLED INTEGER NOT NULL CHECK($COL_ENABLED IN (0,1))," +
                "$COL_SOURCE_KEY TEXT," +
                "$COL_UPDATED_AT INTEGER NOT NULL CHECK($COL_UPDATED_AT>=0))",
        )
        db.execSQL(
            "CREATE INDEX correction_profile_language ON $TABLE_CORRECTIONS(" +
                "$COL_PROFILE,$COL_LANGUAGE,$COL_ENABLED,$COL_UPDATED_AT)",
        )
        db.execSQL(
            "CREATE INDEX correction_exact_phrase ON $TABLE_CORRECTIONS(" +
                "$COL_PROFILE,$COL_LANGUAGE,$COL_RECOGNIZED,$COL_ENABLED)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX correction_unique_phrase ON $TABLE_CORRECTIONS(" +
                "$COL_PROFILE,$COL_LANGUAGE,$COL_RECOGNIZED)",
        )
        db.execSQL(
            "CREATE TABLE $TABLE_SETTINGS (" +
                "$COL_SETTING_KEY TEXT PRIMARY KEY,$COL_SETTING_VALUE TEXT NOT NULL)",
        )
        db.insertOrThrow(
            TABLE_SETTINGS,
            null,
            ContentValues().apply {
                put(COL_SETTING_KEY, SETTING_ACTIVE_PROFILE)
                put(COL_SETTING_VALUE, SpeechCorrectionRepository.DEFAULT_PROFILE)
            },
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

private const val DATABASE_VERSION = 1
private const val TABLE_CORRECTIONS = "corrections"
private const val TABLE_SETTINGS = "settings"
private const val COL_ID = "id"
private const val COL_PROFILE = "profile"
private const val COL_LANGUAGE = "language_tag"
private const val COL_RECOGNIZED = "recognized_text"
private const val COL_CORRECTED = "corrected_text"
private const val COL_HINT = "hint"
private const val COL_ENABLED = "enabled"
private const val COL_SOURCE_KEY = "source_key"
private const val COL_UPDATED_AT = "updated_at_epoch_ms"
private const val COL_SETTING_KEY = "setting_key"
private const val COL_SETTING_VALUE = "setting_value"
private const val SETTING_ACTIVE_PROFILE = "active_profile"
