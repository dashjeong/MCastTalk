package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

enum class SentenceMemoryOrigin { USER, AI }

data class SentenceMemoryEntry(
    val id: Long = 0L,
    val sourceLanguageTag: String,
    val targetLanguageTag: String,
    val translationRegister: TranslationRegister,
    val original: String,
    val corrected: String,
    val origin: SentenceMemoryOrigin,
    val updatedAtEpochMillis: Long = 0L,
) {
    override fun toString() = "SentenceMemoryEntry(id=$id, origin=$origin, text=redacted)"
}

internal interface SentenceMemoryStore {
    suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
        register: TranslationRegister, original: String): SentenceMemoryEntry?
    suspend fun upsert(entry: SentenceMemoryEntry): Boolean
    /** Deferred stores must evaluate the predicate where the actual write begins. */
    suspend fun upsertIf(entry: SentenceMemoryEntry, allowedToWrite: () -> Boolean): Boolean =
        if (allowedToWrite()) upsert(entry) else false
    suspend fun recordTeacherReport(report: TeacherLearningReport, allowedToWrite: () -> Boolean): Boolean = false
    suspend fun teacherReport(key: String): TeacherLearningReport? = null
}

/** Exact sentence retrieval, not an update to any model's weights. Contents stay in app-private SQLite. */
class SentenceTranslationMemory(context: Context, databaseName: String = "sentence-translation-memory.db") :
    SentenceMemoryStore, Closeable {
    private val database = MemoryDatabase(context.applicationContext, databaseName)
    private val closed = AtomicBoolean(false)
    private val executor = object : ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "mcasttalk-sentence-memory").apply { isDaemon = true } }, AbortPolicy()) {
        override fun terminated() { runCatching { database.close() } }
    }
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Volatile private var hints: Map<String, List<String>> = emptyMap()
    init { executor.execute { runCatching { refreshHints(database.readableDatabase) } } }

    /** A small already-loaded snapshot; never reads SQLite on the recognizer/main thread. */
    fun recognitionHints(languageTag: String): List<String> =
        runCatching { hints[normalizeMemoryLanguage(languageTag)].orEmpty() }.getOrDefault(emptyList())

    override suspend fun lookup(sourceLanguageTag: String, targetLanguageTag: String,
        register: TranslationRegister, original: String): SentenceMemoryEntry? = io {
        if (original.length !in 1..MAX_ORIGINAL_CHARS) return@io null
        if (register == TranslationRegister.AUTO) {
            val confirmed = database.readableDatabase.query("phrases", null,
                "source_language=? AND target_language=? AND normalized_original=? AND origin='USER'",
                arrayOf(normalizeMemoryLanguage(sourceLanguageTag), normalizeMemoryLanguage(targetLanguageTag), normalizeMemorySource(original)),
                null, null, "updated_at DESC,id DESC", "1").use { if (it.moveToFirst()) it.entry() else null }
            if (confirmed != null) return@io confirmed
        }
        database.readableDatabase.query("phrases", null, KEY_SELECTION,
            arrayOf(normalizeMemoryLanguage(sourceLanguageTag), normalizeMemoryLanguage(targetLanguageTag),
                register.name, normalizeMemorySource(original)), null, null, null, "1").use { cursor ->
            if (cursor.moveToFirst()) cursor.entry() else null
        }
    }

    override suspend fun upsert(entry: SentenceMemoryEntry): Boolean = upsertIf(entry) { true }

    override suspend fun upsertIf(entry: SentenceMemoryEntry, allowedToWrite: () -> Boolean): Boolean = io {
        // Recheck on the database executor: a cloud result can wait behind a user operation while
        // the operator revokes developer/cloud/learning consent. Human writes keep their contract.
        if (!allowedToWrite()) return@io false
        val db = database.writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            if (!allowedToWrite()) return@io false
            changed = upsertInternal(db, entry, preserveExistingUser = false)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) markChanged(db)
        changed
    }

    suspend fun loadPage(offset: Int = 0, limit: Int = 100, query: String = ""): List<SentenceMemoryEntry> = io {
        require(offset >= 0 && limit in 1..1_000 && query.length <= 200)
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        database.readableDatabase.query("phrases", null,
            if (escaped.isEmpty()) null else "original LIKE ? ESCAPE '\\' OR corrected LIKE ? ESCAPE '\\'",
            if (escaped.isEmpty()) null else arrayOf("%$escaped%", "%$escaped%"), null, null,
            "updated_at DESC,id DESC", "$offset,$limit").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.entry()) }
        }
    }

    suspend fun delete(id: Long) = io {
        if (database.writableDatabase.delete("phrases", "id=?", arrayOf(id.toString())) > 0) markChanged(database.readableDatabase)
    }

    override suspend fun recordTeacherReport(report: TeacherLearningReport, allowedToWrite: () -> Boolean): Boolean = io {
        validateTeacherReport(report)
        if (!allowedToWrite()) return@io false
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            if (!allowedToWrite()) return@io false
            val inserted = db.insertWithOnConflict("teacher_reports", null, ContentValues().apply {
                put("fingerprint", report.key); put("created_at", report.createdAtEpochMillis)
                put("report_json", report.toJson().toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            if (inserted == -1L) return@io false
            db.execSQL("DELETE FROM teacher_reports WHERE fingerprint IN (SELECT fingerprint FROM teacher_reports " +
                "ORDER BY created_at DESC, fingerprint DESC LIMIT -1 OFFSET 200)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        mutableRevision.value++
        true
    }

    override suspend fun teacherReport(key: String): TeacherLearningReport? = io {
        database.readableDatabase.query("teacher_reports", arrayOf("report_json"), "fingerprint=?", arrayOf(key),
            null, null, null, "1").use { if (it.moveToFirst()) TeacherLearningReport.fromJson(org.json.JSONObject(it.getString(0))) else null }
    }

    suspend fun teacherReports(): List<TeacherLearningReport> = io {
        database.readableDatabase.rawQuery("SELECT report_json FROM teacher_reports ORDER BY created_at DESC,fingerprint DESC LIMIT 200", null)
            .use { cursor -> buildList { while (cursor.moveToNext()) add(TeacherLearningReport.fromJson(org.json.JSONObject(cursor.getString(0)))) } }
    }

    suspend fun clearTeacherReports() = io {
        database.writableDatabase.delete("teacher_reports", null, null)
        mutableRevision.value++
    }

    suspend fun decideTeacherLearning(report: TeacherLearningReport, approve: Boolean): Boolean = io {
        val db = database.writableDatabase
        var changed = false
        db.beginTransaction()
        try {
            val stored = db.query("teacher_reports", arrayOf("report_json"), "fingerprint=?", arrayOf(report.key), null, null, null, "1").use {
                if (it.moveToFirst()) TeacherLearningReport.fromJson(org.json.JSONObject(it.getString(0))) else null
            } ?: return@io false
            if (stored != report || stored.outcome !in setOf(TeacherReviewOutcome.PROPOSED, TeacherReviewOutcome.HELD)) return@io false
            val appliedAt = if (approve) {
                val candidate = stored.after ?: return@io false
                if (stored.lessons.isEmpty() || !conservativeReviewAccepted(stored.original, stored.before, candidate, stored.targetLanguageTag)) return@io false
                val entry = SentenceMemoryEntry(sourceLanguageTag = stored.sourceLanguageTag, targetLanguageTag = stored.targetLanguageTag,
                    translationRegister = stored.translationRegister, original = stored.original, corrected = candidate, origin = SentenceMemoryOrigin.USER)
                // A prior confirmed sentence wins even if it was confirmed while this report was open.
                val existing = db.rawQuery("SELECT 1 FROM phrases WHERE source_language=? AND target_language=? " +
                    "AND normalized_original=? AND origin=?" + (if (entry.translationRegister == TranslationRegister.AUTO) " LIMIT 1" else " AND register_name=? LIMIT 1"),
                    (listOf(normalizeMemoryLanguage(entry.sourceLanguageTag), normalizeMemoryLanguage(entry.targetLanguageTag),
                        normalizeMemorySource(entry.original), SentenceMemoryOrigin.USER.name) +
                        if (entry.translationRegister == TranslationRegister.AUTO) emptyList() else listOf(entry.translationRegister.name)).toTypedArray()).use { it.moveToFirst() }
                if (existing || !upsertInternal(db, entry, false)) return@io false
                db.query("phrases", arrayOf("updated_at"), KEY_SELECTION, arrayOf(normalizeMemoryLanguage(entry.sourceLanguageTag),
                    normalizeMemoryLanguage(entry.targetLanguageTag), entry.translationRegister.name, normalizeMemorySource(entry.original)), null, null, null, "1").use {
                    it.moveToFirst(); it.getLong(0)
                }
            } else null
            db.update("teacher_reports", ContentValues().apply {
                put("report_json", stored.copy(outcome = if (approve) TeacherReviewOutcome.APPROVED else TeacherReviewOutcome.HELD,
                    appliedAtEpochMillis = appliedAt).toJson().toString())
            }, "fingerprint=?", arrayOf(report.key))
            db.setTransactionSuccessful(); changed = true
        } finally { db.endTransaction() }
        if (changed) markChanged(db)
        changed
    }

    suspend fun importTeacherReport(report: TeacherLearningReport): Boolean = io {
        validateTeacherReport(report)
        val db = database.writableDatabase
        // Imported metadata cannot mint a local approval/rollback receipt.
        val evidence = report.copy(outcome = TeacherReviewOutcome.IMPORTED, appliedAtEpochMillis = null)
        if (db.rawQuery("SELECT COUNT(*) FROM teacher_reports", null).use { it.moveToFirst(); it.getInt(0) } >= 200) return@io false
        val inserted = db.insertWithOnConflict("teacher_reports", null, ContentValues().apply {
            put("fingerprint", report.key); put("created_at", report.createdAtEpochMillis); put("report_json", evidence.toJson().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) mutableRevision.value++
        inserted
    }

    /** Roll back only this still-unconfirmed AI correction; never erase subsequent or human edits. */
    suspend fun undoTeacherLearning(report: TeacherLearningReport): Boolean = io {
        val db = database.writableDatabase
        db.beginTransaction()
        val removed: Boolean
        try {
            val stored = db.query("teacher_reports", arrayOf("report_json"), "fingerprint=?", arrayOf(report.key), null, null, null, "1").use {
                if (it.moveToFirst()) TeacherLearningReport.fromJson(org.json.JSONObject(it.getString(0))) else null
            }
            if (stored != report || stored.outcome !in setOf(TeacherReviewOutcome.APPROVED, TeacherReviewOutcome.LEARNED)) return@io false
            val extra = if (report.outcome == TeacherReviewOutcome.APPROVED && report.appliedAtEpochMillis != null)
                " AND updated_at=${report.appliedAtEpochMillis}" else ""
            removed = db.delete("phrases", "$KEY_SELECTION AND origin=? AND corrected=?$extra",
                arrayOf(normalizeMemoryLanguage(report.sourceLanguageTag), normalizeMemoryLanguage(report.targetLanguageTag),
                    report.translationRegister.name, normalizeMemorySource(report.original),
                    if (extra.isNotEmpty()) SentenceMemoryOrigin.USER.name else SentenceMemoryOrigin.AI.name, report.after.orEmpty())) > 0
            if (removed) db.update("teacher_reports", ContentValues().apply {
                put("report_json", report.copy(outcome = TeacherReviewOutcome.UNDONE).toJson().toString())
            }, "fingerprint=?", arrayOf(report.key))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (removed) markChanged(database.readableDatabase)
        removed
    }

    suspend fun confirm(id: Long): Boolean = io {
        val changed = database.writableDatabase.update("phrases", ContentValues().apply {
            put("origin", SentenceMemoryOrigin.USER.name); put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id.toString())) > 0
        if (changed) markChanged(database.readableDatabase)
        changed
    }

    /** Bounded portable batches. Existing human-confirmed wording always wins during import. */
    suspend fun importRecords(entries: List<SentenceMemoryEntry>): Int = io {
        require(entries.size <= 1_000)
        entries.forEach(::validateMemoryEntry)
        val db = database.writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            entries.forEach { if (upsertInternal(db, it.copy(id = 0L), preserveExistingUser = true)) changed++ }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed > 0) markChanged(db)
        changed
    }

    private fun upsertInternal(db: SQLiteDatabase, value: SentenceMemoryEntry, preserveExistingUser: Boolean): Boolean {
        validateMemoryEntry(value)
        val source = normalizeMemoryLanguage(value.sourceLanguageTag)
        val target = normalizeMemoryLanguage(value.targetLanguageTag)
        val normal = normalizeMemorySource(value.original)
        val byKey = db.query("phrases", null, KEY_SELECTION,
            arrayOf(source, target, value.translationRegister.name, normal), null, null, null, "1").use {
            if (it.moveToFirst()) it.entry() else null
        }
        val byId = if (value.id <= 0L) null else db.query("phrases", null, "id=?",
            arrayOf(value.id.toString()), null, null, null, "1").use { if (it.moveToFirst()) it.entry() else null }
        if (byKey != null && byId != null && byKey.id != byId.id) return false
        val previous = byId ?: byKey
        if (previous != null && preserveExistingUser) return false
        if (previous?.origin == SentenceMemoryOrigin.USER && (preserveExistingUser || value.origin == SentenceMemoryOrigin.AI)) return false
        if (previous == null && db.rawQuery("SELECT COUNT(*) FROM phrases", null).use { it.moveToFirst(); it.getLong(0) } >= MAX_ENTRIES) return false
        val values = ContentValues().apply {
            put("source_language", source); put("target_language", target); put("register_name", value.translationRegister.name)
            put("normalized_original", normal); put("original", value.original.trim()); put("corrected", value.corrected.trim())
            put("origin", value.origin.name); put("updated_at", System.currentTimeMillis())
        }
        if (previous == null) db.insertOrThrow("phrases", null, values)
        else db.update("phrases", values, "id=?", arrayOf(previous.id.toString()))
        return true
    }

    private fun markChanged(db: SQLiteDatabase) {
        refreshHints(db)
        mutableRevision.value++
    }
    private fun refreshHints(db: SQLiteDatabase) {
        val next = linkedMapOf<String, MutableList<String>>()
        val counts = mutableMapOf<String, Int>()
        db.rawQuery("SELECT source_language,original FROM phrases WHERE origin='USER' AND length(original)<=80 " +
            "ORDER BY updated_at DESC,id DESC LIMIT 1024", null).use { cursor ->
            while (cursor.moveToNext()) {
                val language = normalizeMemoryLanguage(cursor.getString(0))
                val text = normalizeMemorySource(cursor.getString(1))
                val length = text.codePointCount(0, text.length)
                val rows = next.getOrPut(language) { mutableListOf() }
                if (length in 1..80 && rows.size < 32 && text !in rows && (counts[language] ?: 0) + length <= 1_000) {
                    rows += text
                    counts[language] = (counts[language] ?: 0) + length
                }
            }
        }
        hints = next.mapValues { it.value.toList() }
    }

    private suspend fun <T> io(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (closed.get()) {
            continuation.resumeWithException(IllegalStateException("문장 사전이 닫혔습니다."))
            return@suspendCancellableCoroutine
        }
        val task = FutureTask {
            if (continuation.isActive) continuation.resumeWith(runCatching(block).recoverCatching {
                throw IllegalStateException("문장 사전 저장 또는 읽기에 실패했습니다.")
            })
        }
        continuation.invokeOnCancellation { task.cancel(false); executor.remove(task) }
        try {
            executor.execute(task)
            if (task.isCancelled) executor.remove(task)
        } catch (_: RuntimeException) {
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException("문장 사전 작업이 많습니다. 다시 시도하세요."))
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Closing never waits on the caller/UI thread. Existing accepted writes finish first.
            executor.shutdown()
        }
    }

    private fun Cursor.entry() = SentenceMemoryEntry(
        id = getLong(getColumnIndexOrThrow("id")), sourceLanguageTag = getString(getColumnIndexOrThrow("source_language")),
        targetLanguageTag = getString(getColumnIndexOrThrow("target_language")),
        translationRegister = TranslationRegister.valueOf(getString(getColumnIndexOrThrow("register_name"))),
        original = getString(getColumnIndexOrThrow("original")), corrected = getString(getColumnIndexOrThrow("corrected")),
        origin = SentenceMemoryOrigin.valueOf(getString(getColumnIndexOrThrow("origin"))),
        updatedAtEpochMillis = getLong(getColumnIndexOrThrow("updated_at")),
    )
    private class MemoryDatabase(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE phrases(id INTEGER PRIMARY KEY AUTOINCREMENT,source_language TEXT NOT NULL," +
                "target_language TEXT NOT NULL,register_name TEXT NOT NULL,normalized_original TEXT NOT NULL," +
                "original TEXT NOT NULL,corrected TEXT NOT NULL,origin TEXT NOT NULL,updated_at INTEGER NOT NULL," +
                "UNIQUE(source_language,target_language,register_name,normalized_original))")
            db.execSQL("CREATE INDEX phrases_recent ON phrases(updated_at DESC,id DESC)")
            db.execSQL("CREATE INDEX phrases_confirmed ON phrases(source_language,target_language,normalized_original,origin,updated_at)")
            createTeacherReports(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                createTeacherReports(db)
                db.execSQL("CREATE INDEX phrases_confirmed ON phrases(source_language,target_language,normalized_original,origin,updated_at)")
            }
        }
        private fun createTeacherReports(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE teacher_reports(fingerprint TEXT PRIMARY KEY, created_at INTEGER NOT NULL,report_json TEXT NOT NULL)")
            db.execSQL("CREATE INDEX teacher_reports_recent ON teacher_reports(created_at DESC)")
        }
    }
    companion object {
        const val MAX_ENTRIES = 50_000
        const val MAX_ORIGINAL_CHARS = 4_000
        const val MAX_CORRECTED_CHARS = 8_000
        private const val KEY_SELECTION = "source_language=? AND target_language=? AND register_name=? AND normalized_original=?"
    }
}

internal fun normalizeMemorySource(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")
internal fun normalizeMemoryLanguage(value: String): String {
    require(value.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}")))
    val parts = value.lowercase(Locale.ROOT).split('-')
    val language = parts.first()
    if (language == "zh" || language == "cmn") {
        // Explicit script wins over region; the app's unqualified zh channel is Simplified.
        return when {
            "hant" in parts -> "zh-hant"
            "hans" in parts -> "zh-hans"
            parts.any { it in setOf("tw", "hk", "mo") } -> "zh-hant"
            else -> "zh-hans"
        }
    }
    // Recognition locales (ko-KR/en-US) and translation language IDs (ko/en) share entries.
    // Preserve an explicitly requested non-Chinese script instead of conflating its alphabet.
    val script = parts.drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }
    return if (script == null || language in setOf("en", "ko", "ja", "fr", "es", "de", "vi")) language
        else "$language-$script"
}
internal fun validateMemoryEntry(value: SentenceMemoryEntry) {
    normalizeMemoryLanguage(value.sourceLanguageTag); normalizeMemoryLanguage(value.targetLanguageTag)
    require(value.original.length in 1..SentenceTranslationMemory.MAX_ORIGINAL_CHARS && value.original.isNotBlank())
    require(value.corrected.length in 1..SentenceTranslationMemory.MAX_CORRECTED_CHARS && value.corrected.isNotBlank())
    require((value.original + value.corrected).none { it == '\u0000' || (it.code < 32 && it !in "\n\r\t") })
}
