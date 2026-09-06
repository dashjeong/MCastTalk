package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranscriptArchiveKey(
    val sessionId: Long,
    val sequence: Long,
)

data class ArchivedTranscriptLine(
    val key: TranscriptArchiveKey,
    val sessionStartedAtEpochMillis: Long,
    val sourceLanguageTag: String,
    val line: TranslationTranscriptLine,
)

data class TranscriptArchiveSnapshot(
    val lines: List<ArchivedTranscriptLine> = emptyList(),
    val pendingWriteCount: Int = 0,
    val warning: String? = null,
)

/**
 * Bounded, coalescing transcript persistence. Audio callbacks never touch SQLite: observer events
 * replace the newest pending value for one session/sequence and a single IO worker drains it.
 */
class BroadcastTranscriptArchive(context: Context) : Closeable {
    private val database = ArchiveDatabase(context.applicationContext)
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "guidecast-transcript-archive").apply { isDaemon = true }
    }.apply {
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        removeOnCancelPolicy = true
    }
    private val nextSessionId = AtomicLong(System.currentTimeMillis())
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val pending = linkedMapOf<TranscriptArchiveKey, PendingLine>()
    private var drainScheduled = false
    private var drainRetryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
    private val mutableSnapshot = MutableStateFlow(TranscriptArchiveSnapshot())
    val snapshot: StateFlow<TranscriptArchiveSnapshot> = mutableSnapshot.asStateFlow()

    init {
        executor.execute(::reloadSnapshot)
    }

    fun beginSession(sourceLanguageTag: String): Long {
        val now = System.currentTimeMillis()
        val sessionId = nextSessionId.updateAndGet { previous -> maxOf(previous + 1L, now) }
        if (!closed.get()) {
            executor.execute {
                runDatabaseOperation {
                    database.writableDatabase.insertOrThrow(
                        TABLE_SESSIONS,
                        null,
                        ContentValues().apply {
                            put(COL_SESSION_ID, sessionId)
                            put(COL_STARTED_AT, now)
                            put(COL_SOURCE_LANGUAGE, sourceLanguageTag)
                        },
                    )
                }
            }
        }
        return sessionId
    }

    /** Only finalized source lines are durable; later translation/TTS fields coalesce into them. */
    fun enqueue(sessionId: Long, line: TranslationTranscriptLine) {
        if (!line.isFinal || closed.get()) return
        val key = TranscriptArchiveKey(sessionId, line.sequence)
        synchronized(lock) {
            val dropped = pending.putLatestBounded(
                key = key,
                value = PendingLine(key, line),
                maximumEntries = MAX_PENDING_LINES,
            )
            if (dropped > 0) {
                mutableSnapshot.value = mutableSnapshot.value.copy(
                    warning = "스크립트 저장 대기열이 포화되어 가장 오래된 미저장 행을 제외했습니다.",
                )
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(pendingWriteCount = pending.size)
            if (!drainScheduled) {
                drainScheduled = true
                executor.execute(::drainPending)
            }
        }
    }

    fun delete(keys: Set<TranscriptArchiveKey>) {
        if (keys.isEmpty() || closed.get()) return
        synchronized(lock) { keys.forEach(pending::remove) }
        executor.execute {
            runDatabaseOperation {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    keys.forEach { key ->
                        val args = arrayOf(key.sessionId.toString(), key.sequence.toString())
                        db.delete(TABLE_TRANSLATIONS, "$COL_SESSION_ID=? AND $COL_SEQUENCE=?", args)
                        db.delete(TABLE_LINES, "$COL_SESSION_ID=? AND $COL_SEQUENCE=?", args)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                reloadSnapshotInternal(db)
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        if (closed.get()) return
        synchronized(lock) { pending.keys.removeAll { it.sessionId == sessionId } }
        executor.execute {
            runDatabaseOperation {
                val db = database.writableDatabase
                db.delete(TABLE_SESSIONS, "$COL_SESSION_ID=?", arrayOf(sessionId.toString()))
                reloadSnapshotInternal(db)
            }
        }
    }

    fun linesForSession(sessionId: Long): List<TranslationTranscriptLine> =
        snapshot.value.lines.asSequence()
            .filter { it.key.sessionId == sessionId }
            .sortedBy { it.key.sequence }
            .map(ArchivedTranscriptLine::line)
            .toList()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        runCatching { executor.awaitTermination(2L, TimeUnit.SECONDS) }
        database.close()
    }

    private fun drainPending() {
        while (!closed.get()) {
            val batch = synchronized(lock) {
                if (pending.isEmpty()) {
                    drainScheduled = false
                    mutableSnapshot.value = mutableSnapshot.value.copy(pendingWriteCount = 0)
                    return
                }
                pending.values.toList().also { pending.clear() }
            }
            val stored = runDatabaseOperation {
                val db = database.writableDatabase
                db.beginTransaction()
                try {
                    batch.forEach { upsert(db, it) }
                    prune(db)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                reloadSnapshotInternal(db)
            }
            if (!stored) {
                synchronized(lock) {
                    // The failed batch is older than anything enqueued while SQLite was busy.
                    // Rebuild that ordering, let the newer coalesced value win, and reapply the
                    // same hard bound used by enqueue(). Without this second bound, persistent DB
                    // failure grew the queue by another batch on every retry.
                    val dropped = pending.mergeOlderEntriesBounded(
                        olderEntries = batch.map { failed -> failed.key to failed },
                        maximumEntries = MAX_PENDING_LINES,
                    )
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        pendingWriteCount = pending.size,
                        warning = if (dropped > 0) {
                            "스크립트 저장 오류가 계속되어 가장 오래된 미저장 행을 제외했습니다."
                        } else {
                            mutableSnapshot.value.warning
                        },
                    )
                    if (closed.get()) {
                        drainScheduled = false
                    } else {
                        val retryDelay = drainRetryDelayMillis
                        drainRetryDelayMillis = (drainRetryDelayMillis * 2L)
                            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                        executor.schedule(::drainPending, retryDelay, TimeUnit.MILLISECONDS)
                    }
                }
                return
            }
            synchronized(lock) { drainRetryDelayMillis = INITIAL_RETRY_DELAY_MILLIS }
        }
    }

    private fun upsert(db: SQLiteDatabase, pendingLine: PendingLine) {
        val key = pendingLine.key
        val line = pendingLine.line
        db.insertWithOnConflict(
            TABLE_LINES,
            null,
            ContentValues().apply {
                put(COL_SESSION_ID, key.sessionId)
                put(COL_SEQUENCE, key.sequence)
                put(COL_SOURCE_TEXT, line.sourceText)
                put(COL_IS_FINAL, 1)
                put(COL_CAPTURED_AT, line.capturedAtElapsedRealtimeNanos)
                put(COL_RECORDED_AT, System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        line.translations.forEach { (languageTag, translatedText) ->
            db.insertWithOnConflict(
                TABLE_TRANSLATIONS,
                null,
                ContentValues().apply {
                    put(COL_SESSION_ID, key.sessionId)
                    put(COL_SEQUENCE, key.sequence)
                    put(COL_LANGUAGE, languageTag)
                    put(COL_TRANSLATED_TEXT, translatedText)
                    line.translationLatencyMillis[languageTag]?.let { put(COL_TRANSLATE_MS, it) }
                    line.firstAudioLatencyMillis[languageTag]?.let { put(COL_FIRST_AUDIO_MS, it) }
                    line.synthesisLatencyMillis[languageTag]?.let { put(COL_SYNTHESIS_MS, it) }
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    private fun prune(db: SQLiteDatabase) {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        db.delete(TABLE_SESSIONS, "$COL_STARTED_AT < ?", arrayOf(cutoff.toString()))
        db.execSQL(
            "DELETE FROM $TABLE_LINES WHERE rowid NOT IN (" +
                "SELECT rowid FROM $TABLE_LINES ORDER BY $COL_RECORDED_AT DESC LIMIT $MAX_STORED_LINES)",
        )
        db.execSQL(
            "DELETE FROM $TABLE_TRANSLATIONS WHERE NOT EXISTS (" +
                "SELECT 1 FROM $TABLE_LINES l WHERE l.$COL_SESSION_ID=$TABLE_TRANSLATIONS.$COL_SESSION_ID " +
                "AND l.$COL_SEQUENCE=$TABLE_TRANSLATIONS.$COL_SEQUENCE)",
        )
    }

    private fun reloadSnapshot() = runDatabaseOperation {
        reloadSnapshotInternal(database.readableDatabase)
    }

    private fun reloadSnapshotInternal(db: SQLiteDatabase) {
        val entries = linkedMapOf<TranscriptArchiveKey, MutableArchivedLine>()
        db.rawQuery(
            "SELECT s.$COL_SESSION_ID,s.$COL_STARTED_AT,s.$COL_SOURCE_LANGUAGE," +
                "l.$COL_SEQUENCE,l.$COL_SOURCE_TEXT,l.$COL_CAPTURED_AT," +
                "t.$COL_LANGUAGE,t.$COL_TRANSLATED_TEXT,t.$COL_TRANSLATE_MS," +
                "t.$COL_FIRST_AUDIO_MS,t.$COL_SYNTHESIS_MS " +
                "FROM (SELECT * FROM $TABLE_LINES ORDER BY $COL_RECORDED_AT DESC " +
                "LIMIT $MAX_SNAPSHOT_LINES) l " +
                "JOIN $TABLE_SESSIONS s ON s.$COL_SESSION_ID=l.$COL_SESSION_ID " +
                "LEFT JOIN $TABLE_TRANSLATIONS t ON t.$COL_SESSION_ID=l.$COL_SESSION_ID " +
                "AND t.$COL_SEQUENCE=l.$COL_SEQUENCE " +
                "ORDER BY s.$COL_STARTED_AT DESC,l.$COL_SEQUENCE DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sessionId = cursor.getLong(0)
                val sequence = cursor.getLong(3)
                val key = TranscriptArchiveKey(sessionId, sequence)
                val mutable = entries.getOrPut(key) {
                    MutableArchivedLine(
                        sessionStartedAtEpochMillis = cursor.getLong(1),
                        sourceLanguageTag = cursor.getString(2),
                        sequence = sequence,
                        sourceText = cursor.getString(4),
                        capturedAtElapsedRealtimeNanos = cursor.getLong(5),
                    )
                }
                if (!cursor.isNull(6)) {
                    val language = cursor.getString(6)
                    mutable.translations[language] = cursor.getString(7)
                    if (!cursor.isNull(8)) mutable.translationMillis[language] = cursor.getLong(8)
                    if (!cursor.isNull(9)) mutable.firstAudioMillis[language] = cursor.getLong(9)
                    if (!cursor.isNull(10)) mutable.synthesisMillis[language] = cursor.getLong(10)
                }
            }
        }
        val pendingCount = synchronized(lock) { pending.size }
        mutableSnapshot.value = TranscriptArchiveSnapshot(
            lines = entries.map { (key, value) -> value.freeze(key) },
            pendingWriteCount = pendingCount,
            warning = null,
        )
    }

    private inline fun runDatabaseOperation(block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (error: Throwable) {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                warning = "스크립트 저장 오류: " +
                    (error.message?.take(160) ?: error.javaClass.simpleName),
            )
            return false
        }
    }

    private data class PendingLine(
        val key: TranscriptArchiveKey,
        val line: TranslationTranscriptLine,
    )

    private data class MutableArchivedLine(
        val sessionStartedAtEpochMillis: Long,
        val sourceLanguageTag: String,
        val sequence: Long,
        val sourceText: String,
        val capturedAtElapsedRealtimeNanos: Long,
        val translations: MutableMap<String, String> = linkedMapOf(),
        val translationMillis: MutableMap<String, Long> = linkedMapOf(),
        val firstAudioMillis: MutableMap<String, Long> = linkedMapOf(),
        val synthesisMillis: MutableMap<String, Long> = linkedMapOf(),
    ) {
        fun freeze(key: TranscriptArchiveKey) = ArchivedTranscriptLine(
            key = key,
            sessionStartedAtEpochMillis = sessionStartedAtEpochMillis,
            sourceLanguageTag = sourceLanguageTag,
            line = TranslationTranscriptLine(
                sequence = sequence,
                sourceText = sourceText,
                capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
                isFinal = true,
                translations = translations.toMap(),
                translationLatencyMillis = translationMillis.toMap(),
                firstAudioLatencyMillis = firstAudioMillis.toMap(),
                synthesisLatencyMillis = synthesisMillis.toMap(),
                sourceLanguageTag = sourceLanguageTag,
            ),
        )
    }

    private class ArchiveDatabase(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_SESSIONS (" +
                    "$COL_SESSION_ID INTEGER PRIMARY KEY," +
                    "$COL_STARTED_AT INTEGER NOT NULL," +
                    "$COL_SOURCE_LANGUAGE TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE $TABLE_LINES (" +
                    "$COL_SESSION_ID INTEGER NOT NULL," +
                    "$COL_SEQUENCE INTEGER NOT NULL," +
                    "$COL_SOURCE_TEXT TEXT NOT NULL," +
                    "$COL_IS_FINAL INTEGER NOT NULL," +
                    "$COL_CAPTURED_AT INTEGER NOT NULL," +
                    "$COL_RECORDED_AT INTEGER NOT NULL," +
                    "PRIMARY KEY($COL_SESSION_ID,$COL_SEQUENCE)," +
                    "FOREIGN KEY($COL_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE)",
            )
            db.execSQL(
                "CREATE TABLE $TABLE_TRANSLATIONS (" +
                    "$COL_SESSION_ID INTEGER NOT NULL," +
                    "$COL_SEQUENCE INTEGER NOT NULL," +
                    "$COL_LANGUAGE TEXT NOT NULL," +
                    "$COL_TRANSLATED_TEXT TEXT NOT NULL," +
                    "$COL_TRANSLATE_MS INTEGER," +
                    "$COL_FIRST_AUDIO_MS INTEGER," +
                    "$COL_SYNTHESIS_MS INTEGER," +
                    "PRIMARY KEY($COL_SESSION_ID,$COL_SEQUENCE,$COL_LANGUAGE)," +
                    "FOREIGN KEY($COL_SESSION_ID,$COL_SEQUENCE) REFERENCES $TABLE_LINES(" +
                    "$COL_SESSION_ID,$COL_SEQUENCE) ON DELETE CASCADE)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "broadcast-transcripts.db"
        const val DATABASE_VERSION = 1
        const val TABLE_SESSIONS = "sessions"
        const val TABLE_LINES = "transcript_lines"
        const val TABLE_TRANSLATIONS = "translations"
        const val COL_SESSION_ID = "session_id"
        const val COL_STARTED_AT = "started_at_epoch_ms"
        const val COL_SOURCE_LANGUAGE = "source_language"
        const val COL_SEQUENCE = "sequence_id"
        const val COL_SOURCE_TEXT = "source_text"
        const val COL_IS_FINAL = "is_final"
        const val COL_CAPTURED_AT = "captured_at_elapsed_ns"
        const val COL_RECORDED_AT = "recorded_at_epoch_ms"
        const val COL_LANGUAGE = "language_tag"
        const val COL_TRANSLATED_TEXT = "translated_text"
        const val COL_TRANSLATE_MS = "translate_ms"
        const val COL_FIRST_AUDIO_MS = "first_audio_ms"
        const val COL_SYNTHESIS_MS = "synthesis_ms"
        const val MAX_PENDING_LINES = 256
        const val INITIAL_RETRY_DELAY_MILLIS = 250L
        const val MAX_RETRY_DELAY_MILLIS = 5_000L
        const val MAX_STORED_LINES = 5_000
        const val MAX_SNAPSHOT_LINES = 1_000
        const val RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1_000L
    }
}

/**
 * Adds the newest value for [key], moves that key to the newest position and drops only the
 * oldest entries beyond [maximumEntries]. Returns the number dropped.
 */
internal fun <K, V> LinkedHashMap<K, V>.putLatestBounded(
    key: K,
    value: V,
    maximumEntries: Int,
): Int {
    require(maximumEntries > 0)
    remove(key)
    this[key] = value
    return trimOldestEntries(maximumEntries)
}

/**
 * Requeues a failed older batch in front of values that arrived during the failed write. The
 * existing/newer value wins for duplicate keys and the result is bounded after every retry.
 */
internal fun <K, V> LinkedHashMap<K, V>.mergeOlderEntriesBounded(
    olderEntries: Iterable<Pair<K, V>>,
    maximumEntries: Int,
): Int {
    require(maximumEntries > 0)
    val newerEntries = entries.map { entry -> entry.key to entry.value }
    clear()
    olderEntries.forEach { (key, value) ->
        remove(key)
        this[key] = value
    }
    newerEntries.forEach { (key, value) ->
        remove(key)
        this[key] = value
    }
    return trimOldestEntries(maximumEntries)
}

private fun <K, V> LinkedHashMap<K, V>.trimOldestEntries(maximumEntries: Int): Int {
    var dropped = 0
    while (size > maximumEntries) {
        remove(entries.first().key)
        dropped++
    }
    return dropped
}

internal fun mergeTranscriptLines(
    archivedFinalLines: List<TranslationTranscriptLine>,
    currentLines: List<TranslationTranscriptLine>,
    maximumLines: Int = 1_000,
): List<TranslationTranscriptLine> {
    require(maximumLines > 0)
    val merged = linkedMapOf<Long, TranslationTranscriptLine>()
    archivedFinalLines.forEach { merged[it.sequence] = it }
    currentLines.forEach { merged[it.sequence] = it }
    return merged.values.sortedBy(TranslationTranscriptLine::sequence).takeLast(maximumLines)
}
