package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
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
    val isDailyBackup: Boolean = false,
)

data class TranscriptArchiveSnapshot(
    val lines: List<ArchivedTranscriptLine> = emptyList(),
    val pendingWriteCount: Int = 0,
    val warning: String? = null,
    val sessions: List<ArchivedBroadcastSession> = emptyList(),
    val revision: Long = 0L,
    val retentionPolicy: TranscriptRetentionPolicy = TranscriptRetentionPolicy.OVERWRITE_OLDEST,
)

enum class TranscriptRetentionPolicy { OVERWRITE_OLDEST, DAILY_BACKUP }

data class ArchivedBroadcastSession(
    val sessionId: Long,
    val startedAtEpochMillis: Long,
    val sourceLanguageTag: String,
    val storedLineCount: Int,
    val translationLanguages: List<String> = emptyList(),
)

data class TranscriptArchivePage(
    val lines: List<ArchivedTranscriptLine> = emptyList(),
    val totalMatchingLines: Int = 0,
)

data class TranscriptArchiveFilter(
    val sessionId: Long? = null,
    val languageTag: String? = null,
    val oldestSessionFirst: Boolean = false,
    val pageIndex: Int = 0,
    val startedFromEpochMillis: Long? = null,
    val startedBeforeEpochMillis: Long? = null,
) {
    init {
        require(pageIndex >= 0)
        require(startedFromEpochMillis == null || startedBeforeEpochMillis == null ||
            startedFromEpochMillis < startedBeforeEpochMillis)
    }
}

/**
 * Bounded, coalescing transcript persistence. Audio callbacks never touch SQLite: observer events
 * replace the newest pending value for one session/sequence and a single IO worker drains it.
 */
class BroadcastTranscriptArchive(
    context: Context,
    databaseName: String = DATABASE_NAME,
    private val maximumStoredLines: Int = MAX_STORED_LINES,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val database = ArchiveDatabase(context.applicationContext, databaseName)
    private val backupDirectory = File(context.noBackupFilesDir, "transcript-backups/$databaseName")
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
    // Ignore late translation/TTS updates after an explicit operator deletion.
    private val deletedKeys = linkedSetOf<TranscriptArchiveKey>()
    private val deletedSessions = linkedSetOf<Long>()
    private var drainScheduled = false
    private var drainRetryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
    private var activeLineCount = 0L
    private var retentionPolicy = TranscriptRetentionPolicy.OVERWRITE_OLDEST
    private var maintenanceWarning: String? = null
    private var nextMaintenanceRetryAt = 0L
    private var maintenanceScheduled = false
    private val mutableSnapshot = MutableStateFlow(TranscriptArchiveSnapshot())
    val snapshot: StateFlow<TranscriptArchiveSnapshot> = mutableSnapshot.asStateFlow()

    init {
        require(maximumStoredLines > 0)
        require(File(databaseName).name == databaseName)
        executor.execute {
            runDatabaseOperation {
                val db = database.writableDatabase
                activeLineCount = scalarLong(db, "SELECT COUNT(*) FROM $TABLE_LINES")
                retentionPolicy = db.rawQuery("SELECT value FROM archive_settings WHERE name='retention_policy'", null)
                    .use { cursor ->
                        if (cursor.moveToFirst()) runCatching { TranscriptRetentionPolicy.valueOf(cursor.getString(0)) }
                            .getOrDefault(TranscriptRetentionPolicy.OVERWRITE_OLDEST)
                        else TranscriptRetentionPolicy.OVERWRITE_OLDEST
                    }
                reloadSnapshotInternal(db)
                scheduleMaintenance()
            }
        }
        executor.scheduleWithFixedDelay({ scheduleMaintenance() }, 60L, 60L, TimeUnit.SECONDS)
    }

    fun setRetentionPolicy(policy: TranscriptRetentionPolicy) {
        if (closed.get()) return
        executor.execute {
            runDatabaseOperation {
                database.writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO archive_settings(name,value) VALUES('retention_policy',?)",
                    arrayOf(policy.name),
                )
                retentionPolicy = policy
                nextMaintenanceRetryAt = 0L
                reloadSnapshotInternal(database.readableDatabase)
                scheduleMaintenance()
            }
        }
    }

    fun beginSession(sourceLanguageTag: String): Long {
        val now = clockMillis()
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
                    reloadSnapshotInternal(database.readableDatabase)
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
            if (key in deletedKeys || sessionId in deletedSessions) return
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
        synchronized(lock) {
            deletedKeys.addAll(keys)
            while (deletedKeys.size > MAX_RECENT_DELETIONS) deletedKeys.remove(deletedKeys.first())
            keys.forEach(pending::remove)
        }
        executor.execute {
            runDatabaseOperation {
                val db = database.writableDatabase
                db.beginTransaction()
                var removedActive = 0L
                try {
                    keys.forEach { key ->
                        val args = arrayOf(key.sessionId.toString(), key.sequence.toString())
                        db.execSQL("INSERT OR IGNORE INTO archive_tombstones VALUES(?,?)", args)
                        db.delete(TABLE_TRANSLATIONS, "$COL_SESSION_ID=? AND $COL_SEQUENCE=?", args)
                        removedActive += db.delete(TABLE_LINES, "$COL_SESSION_ID=? AND $COL_SEQUENCE=?", args)
                        removeCatalogEntry(db, key)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                activeLineCount -= removedActive
                reloadSnapshotInternal(db)
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        if (closed.get()) return
        synchronized(lock) {
            deletedSessions.add(sessionId)
            while (deletedSessions.size > MAX_RECENT_DELETIONS) deletedSessions.remove(deletedSessions.first())
            pending.keys.removeAll { it.sessionId == sessionId }
        }
        executor.execute {
            runDatabaseOperation {
                val db = database.writableDatabase
                val removedActive = scalarLong(db, "SELECT COUNT(*) FROM $TABLE_LINES WHERE $COL_SESSION_ID=?",
                    arrayOf(sessionId.toString()))
                inTransaction(db) {
                    db.execSQL("INSERT OR IGNORE INTO archive_deleted_sessions VALUES(?)", arrayOf(sessionId))
                    db.delete(TABLE_SESSIONS, "$COL_SESSION_ID=?", arrayOf(sessionId.toString()))
                }
                activeLineCount -= removedActive
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

    /** A bounded browser page; the live listener snapshot remains independent of UI filters. */
    suspend fun loadPage(filter: TranscriptArchiveFilter): TranscriptArchivePage =
        suspendCancellableCoroutine { continuation ->
            if (closed.get()) {
                continuation.resumeWithException(IllegalStateException("스크립트 보관함이 닫혔습니다."))
                return@suspendCancellableCoroutine
            }
            executor.execute {
                if (!continuation.isActive) return@execute
                try {
                    val db = database.readableDatabase
                    val where = mutableListOf<String>()
                    val args = mutableListOf<String>()
                    filter.sessionId?.let {
                        where += "l.$COL_SESSION_ID=?"
                        args += it.toString()
                    }
                    filter.languageTag?.let {
                        where += "EXISTS (SELECT 1 FROM archive_languages f WHERE " +
                            "f.$COL_SESSION_ID=l.$COL_SESSION_ID AND f.$COL_SEQUENCE=l.$COL_SEQUENCE " +
                            "AND f.$COL_LANGUAGE=?)"
                        args += it
                    }
                    filter.startedFromEpochMillis?.let {
                        where += "l.session_started_at_epoch_ms>=?"
                        args += it.toString()
                    }
                    filter.startedBeforeEpochMillis?.let {
                        where += "l.session_started_at_epoch_ms<?"
                        args += it.toString()
                    }
                    val selection = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
                    val from = "FROM archive_index l"
                    val count = scalarLong(db, "SELECT COUNT(*) $from$selection", args.toTypedArray())
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val direction = if (filter.oldestSessionFirst) "ASC" else "DESC"
                    val order = if (filter.sessionId != null) "l.$COL_SEQUENCE ASC"
                        else "l.session_started_at_epoch_ms $direction,l.$COL_SESSION_ID $direction,l.$COL_SEQUENCE ASC"
                    val selectedLines = "SELECT l.$COL_SESSION_ID,l.$COL_SEQUENCE,l.backup_day $from$selection " +
                        "ORDER BY $order LIMIT $ARCHIVE_PAGE_SIZE OFFSET " +
                        (filter.pageIndex.toLong() * ARCHIVE_PAGE_SIZE)
                    val locations = db.rawQuery(selectedLines, args.toTypedArray()).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(LocatedLine(
                                TranscriptArchiveKey(cursor.getLong(0), cursor.getLong(1)),
                                if (cursor.isNull(2)) null else cursor.getString(2),
                            ))
                        }
                    }
                    val byKey = mutableMapOf<TranscriptArchiveKey, ArchivedTranscriptLine>()
                    var unreadableBackup = false
                    locations.groupBy(LocatedLine::backupDay).forEach { (day, entries) ->
                        if (day == null) readLocatedLines(db, entries, byKey)
                        else try {
                            SQLiteDatabase.openDatabase(backupFile(day).path, null, SQLiteDatabase.OPEN_READONLY)
                                .use { backup -> readLocatedLines(backup, entries, byKey) }
                        } catch (_: Exception) { unreadableBackup = true }
                    }
                    val lines = locations.mapNotNull {
                        byKey[it.key]?.copy(isDailyBackup = it.backupDay != null).also { row ->
                            if (row == null) unreadableBackup = true
                        }
                    }
                    if (unreadableBackup) mutableSnapshot.value = mutableSnapshot.value.copy(
                        warning = "일부 일일 백업을 읽지 못했습니다. 다른 기록은 표시하며, 해당 백업의 보관 위치와 저장공간을 확인하세요.",
                    )
                    if (continuation.isActive) continuation.resume(TranscriptArchivePage(lines, count))
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

    private data class LocatedLine(val key: TranscriptArchiveKey, val backupDay: String?)

    /** Keyset export yields the worker between pages; missing shards fail the whole backup. */
    internal suspend fun exportPortablePage(after: TranscriptArchiveKey?): List<ArchivedTranscriptLine> = onWorker {
        if (after == null) flushBeforeExport()
        val db = database.readableDatabase
        val where = if (after == null) "" else "WHERE ($COL_SESSION_ID>? OR ($COL_SESSION_ID=? AND $COL_SEQUENCE>?))"
        val args = after?.let { arrayOf(it.sessionId.toString(), it.sessionId.toString(), it.sequence.toString()) }
        val locations = db.rawQuery("SELECT $COL_SESSION_ID,$COL_SEQUENCE,backup_day FROM archive_index $where " +
            "ORDER BY $COL_SESSION_ID,$COL_SEQUENCE LIMIT 200", args).use { c -> buildList {
            while (c.moveToNext()) add(LocatedLine(TranscriptArchiveKey(c.getLong(0), c.getLong(1)), c.getString(2)))
        } }
        val found = mutableMapOf<TranscriptArchiveKey, ArchivedTranscriptLine>()
        locations.groupBy(LocatedLine::backupDay).forEach { (day, entries) ->
            if (day == null) readLocatedLines(db, entries, found)
            else SQLiteDatabase.openDatabase(backupFile(day).path, null, SQLiteDatabase.OPEN_READONLY)
                .use { readLocatedLines(it, entries, found) }
        }
        locations.map { location -> (found[location.key] ?: error("일일 백업 일부를 읽지 못해 전체 내보내기를 중단했습니다."))
            .copy(isDailyBackup = location.backupDay != null) }
    }

    internal suspend fun exportSessionsPage(afterId: Long?): List<ArchivedBroadcastSession> = onWorker {
        if (afterId == null) flushBeforeExport()
        database.readableDatabase.rawQuery("SELECT $COL_SESSION_ID,$COL_STARTED_AT,$COL_SOURCE_LANGUAGE FROM $TABLE_SESSIONS " +
            "WHERE $COL_SESSION_ID>? AND $COL_SESSION_ID NOT IN (SELECT $COL_SESSION_ID FROM archive_deleted_sessions) " +
            "ORDER BY $COL_SESSION_ID LIMIT 1000", arrayOf((afterId ?: Long.MIN_VALUE).toString())).use { c -> buildList {
            while (c.moveToNext()) add(ArchivedBroadcastSession(c.getLong(0), c.getLong(1), c.getString(2), 0))
        } }
    }

    internal suspend fun importPortableSessions(sessions: List<ArchivedBroadcastSession>): Int = onWorker {
        require(sessions.size <= 1_000)
        val db = database.writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            sessions.forEach { session ->
                if (scalarLong(db, "SELECT EXISTS(SELECT 1 FROM archive_deleted_sessions WHERE $COL_SESSION_ID=?)",
                        arrayOf(session.sessionId.toString())) == 0L) {
                    if (db.insertWithOnConflict(TABLE_SESSIONS, null, sessionValues(session), SQLiteDatabase.CONFLICT_IGNORE) != -1L) inserted++
                    nextSessionId.updateAndGet { maxOf(it, session.sessionId) }
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        reloadSnapshotInternal(db)
        inserted
    }

    private fun flushBeforeExport() {
        drainPending()
        check(synchronized(lock) { pending.isEmpty() }) { "아직 저장하지 못한 문장이 있어 전체 백업을 준비하지 못했습니다. 저장공간을 확인하세요." }
    }

    /** Imported history is placed in dated private shards so the active 500k cap cannot erase it. */
    internal suspend fun importPortableLines(rows: List<ArchivedTranscriptLine>): Int = onWorker {
        require(rows.size <= 200)
        val db = database.writableDatabase
        var inserted = 0
        rows.groupBy { archiveDay(it.sessionStartedAtEpochMillis) }.forEach { (day, entries) ->
            val file = backupFile(day)
            file.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { backup ->
                if (scalarLong(backup, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='$TABLE_SESSIONS'") == 0L)
                    ArchiveDatabase.createContentTables(backup)
                db.beginTransaction()
                backup.beginTransaction()
                try {
                    entries.forEach { row ->
                        val key = row.key
                        val args = arrayOf(key.sessionId.toString(), key.sequence.toString(), key.sessionId.toString(), key.sequence.toString(), key.sessionId.toString())
                        if (scalarLong(db, "SELECT EXISTS(SELECT 1 FROM archive_index WHERE $COL_SESSION_ID=? AND $COL_SEQUENCE=?) " +
                                "OR EXISTS(SELECT 1 FROM archive_tombstones WHERE $COL_SESSION_ID=? AND $COL_SEQUENCE=?) " +
                                "OR EXISTS(SELECT 1 FROM archive_deleted_sessions WHERE $COL_SESSION_ID=?)", args) != 0L) return@forEach
                        val session = ArchivedBroadcastSession(key.sessionId, row.sessionStartedAtEpochMillis, row.sourceLanguageTag, 0)
                        db.insertWithOnConflict(TABLE_SESSIONS, null, sessionValues(session), SQLiteDatabase.CONFLICT_IGNORE)
                        // A reused session ID from a different device must never mix two broadcasts.
                        val sameSession = db.rawQuery("SELECT 1 FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID=? AND $COL_STARTED_AT=? AND $COL_SOURCE_LANGUAGE=?",
                            arrayOf(key.sessionId.toString(), row.sessionStartedAtEpochMillis.toString(), row.sourceLanguageTag)).use { it.moveToFirst() }
                        if (!sameSession) return@forEach
                        backup.insertWithOnConflict(TABLE_SESSIONS, null, sessionValues(session), SQLiteDatabase.CONFLICT_IGNORE)
                        backup.insertWithOnConflict(TABLE_LINES, null, ContentValues().apply {
                            put(COL_SESSION_ID, key.sessionId); put(COL_SEQUENCE, key.sequence); put(COL_SOURCE_TEXT, row.line.sourceText)
                            put(COL_IS_FINAL, 1); put(COL_CAPTURED_AT, row.line.capturedAtElapsedRealtimeNanos)
                            put(COL_RECORDED_AT, row.sessionStartedAtEpochMillis)
                        }, SQLiteDatabase.CONFLICT_IGNORE)
                        row.line.translations.forEach { (language, text) ->
                            backup.insertWithOnConflict(TABLE_TRANSLATIONS, null, ContentValues().apply {
                                put(COL_SESSION_ID, key.sessionId); put(COL_SEQUENCE, key.sequence); put(COL_LANGUAGE, language); put(COL_TRANSLATED_TEXT, text)
                                row.line.translationLatencyMillis[language]?.let { put(COL_TRANSLATE_MS, it) }
                                row.line.firstAudioLatencyMillis[language]?.let { put(COL_FIRST_AUDIO_MS, it) }
                                row.line.synthesisLatencyMillis[language]?.let { put(COL_SYNTHESIS_MS, it) }
                            }, SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        db.execSQL("INSERT INTO archive_index VALUES(?,?,?,?,?,?)", arrayOf<Any>(key.sessionId, key.sequence,
                            row.sessionStartedAtEpochMillis, day, day, row.sessionStartedAtEpochMillis))
                        updateSessionCount(db, key.sessionId, 1)
                        row.line.translations.keys.forEach { language ->
                            db.execSQL("INSERT INTO archive_languages VALUES(?,?,?)", arrayOf<Any>(key.sessionId, key.sequence, language))
                            updateLanguageCount(db, key.sessionId, language, 1)
                        }
                        nextSessionId.updateAndGet { maxOf(it, key.sessionId) }
                        inserted++
                    }
                    // If process death occurs between commits, only an unindexed private copy remains.
                    // The catalog is committed only after durable content, and retries reuse its key.
                    backup.setTransactionSuccessful()
                    backup.endTransaction()
                    db.setTransactionSuccessful()
                } finally {
                    if (backup.inTransaction()) backup.endTransaction()
                    db.endTransaction()
                }
            }
        }
        reloadSnapshotInternal(db)
        inserted
    }

    private fun sessionValues(session: ArchivedBroadcastSession) = ContentValues().apply {
        put(COL_SESSION_ID, session.sessionId); put(COL_STARTED_AT, session.startedAtEpochMillis)
        put(COL_SOURCE_LANGUAGE, session.sourceLanguageTag)
    }

    private suspend fun <T> onWorker(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        if (closed.get()) { continuation.resumeWithException(IllegalStateException("보관함이 닫혔습니다.")); return@suspendCancellableCoroutine }
        executor.execute {
            if (!continuation.isActive) return@execute
            try { val result = block(); if (continuation.isActive) continuation.resume(result) }
            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
        }
    }

    private fun readLocatedLines(
        db: SQLiteDatabase,
        locations: List<LocatedLine>,
        output: MutableMap<TranscriptArchiveKey, ArchivedTranscriptLine>,
    ) {
        // Stay below Android SQLite's bind limit, regardless of translations per line.
        locations.chunked(200).forEach { chunk ->
            val where = chunk.joinToString(" OR ") { "($COL_SESSION_ID=? AND $COL_SEQUENCE=?)" }
            val args = chunk.flatMap { listOf(it.key.sessionId.toString(), it.key.sequence.toString()) }.toTypedArray()
            readLines(db, "SELECT * FROM $TABLE_LINES WHERE $where", args, "l.$COL_SESSION_ID,l.$COL_SEQUENCE")
                .forEach { output[it.key] = it }
        }
    }

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
                var addedActiveLines = 0L
                db.beginTransaction()
                try {
                    batch.forEach { line ->
                        val deleted = synchronized(lock) {
                            line.key in deletedKeys || line.key.sessionId in deletedSessions
                        }
                        if (!deleted && upsert(db, line)) addedActiveLines++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                activeLineCount += addedActiveLines
                reloadSnapshotInternal(db)
                scheduleMaintenance()
            }
            if (!stored) {
                synchronized(lock) {
                    // The failed batch is older than anything enqueued while SQLite was busy.
                    // Rebuild that ordering, let the newer coalesced value win, and reapply the
                    // same hard bound used by enqueue(). Without this second bound, persistent DB
                    // failure grew the queue by another batch on every retry.
                    val dropped = pending.mergeOlderEntriesBounded(
                        olderEntries = batch.filterNot {
                            it.key in deletedKeys || it.key.sessionId in deletedSessions
                        }.map { failed -> failed.key to failed },
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

    private fun upsert(db: SQLiteDatabase, pendingLine: PendingLine): Boolean {
        val key = pendingLine.key
        val line = pendingLine.line
        val keyArgs = arrayOf(key.sessionId.toString(), key.sequence.toString())
        // Recent tombstones are cached for fast callback rejection. Older deletions remain in
        // indexed SQLite tables, keeping both RAM and late-callback behavior bounded over time.
        if (scalarLong(db, "SELECT EXISTS(SELECT 1 FROM archive_tombstones WHERE $COL_SESSION_ID=? AND $COL_SEQUENCE=?) " +
                "OR EXISTS(SELECT 1 FROM archive_deleted_sessions WHERE $COL_SESSION_ID=?)",
                arrayOf(key.sessionId.toString(), key.sequence.toString(), key.sessionId.toString())) != 0L) return false
        var recordedAt = clockMillis()
        var day = archiveDay(recordedAt)
        var alreadyActive = false
        var alreadyIndexed = false
        db.rawQuery("SELECT $COL_RECORDED_AT,archive_day,backup_day FROM archive_index " +
            "WHERE $COL_SESSION_ID=? AND $COL_SEQUENCE=?", keyArgs).use { cursor ->
            if (cursor.moveToFirst()) {
                alreadyIndexed = true
                recordedAt = cursor.getLong(0)
                day = cursor.getString(1)
                alreadyActive = cursor.isNull(2)
            }
        }
        val previousLanguages = languagesForKey(db, key)
        db.insertWithOnConflict(
            TABLE_LINES,
            null,
            ContentValues().apply {
                put(COL_SESSION_ID, key.sessionId)
                put(COL_SEQUENCE, key.sequence)
                put(COL_SOURCE_TEXT, line.sourceText)
                put(COL_IS_FINAL, 1)
                put(COL_CAPTURED_AT, line.capturedAtElapsedRealtimeNanos)
                put(COL_RECORDED_AT, recordedAt)
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
        db.execSQL("INSERT OR REPLACE INTO archive_index(" +
            "$COL_SESSION_ID,$COL_SEQUENCE,$COL_RECORDED_AT,archive_day,backup_day,session_started_at_epoch_ms) " +
            "VALUES(?,?,?,?,NULL,(SELECT $COL_STARTED_AT FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID=?))",
            arrayOf<Any>(key.sessionId, key.sequence, recordedAt, day, key.sessionId))
        if (!alreadyIndexed) updateSessionCount(db, key.sessionId, 1)
        val nextLanguages = line.translations.keys
        (previousLanguages - nextLanguages).forEach { updateLanguageCount(db, key.sessionId, it, -1) }
        (nextLanguages - previousLanguages).forEach { updateLanguageCount(db, key.sessionId, it, 1) }
        nextLanguages.forEach { language ->
            db.execSQL("INSERT OR IGNORE INTO archive_languages VALUES(?,?,?)",
                arrayOf<Any>(key.sessionId, key.sequence, language))
        }
        return !alreadyActive
    }

    private fun languagesForKey(db: SQLiteDatabase, key: TranscriptArchiveKey): Set<String> =
        db.rawQuery("SELECT $COL_LANGUAGE FROM archive_languages WHERE $COL_SESSION_ID=? AND $COL_SEQUENCE=?",
            arrayOf(key.sessionId.toString(), key.sequence.toString())).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun updateSessionCount(db: SQLiteDatabase, sessionId: Long, delta: Int) {
        db.execSQL("INSERT OR IGNORE INTO session_counts VALUES(?,0)", arrayOf(sessionId))
        db.execSQL("UPDATE session_counts SET line_count=line_count+? WHERE $COL_SESSION_ID=?", arrayOf<Any>(delta, sessionId))
    }

    private fun updateLanguageCount(db: SQLiteDatabase, sessionId: Long, language: String, delta: Int) {
        db.execSQL("INSERT OR IGNORE INTO session_languages VALUES(?,?,0)", arrayOf<Any>(sessionId, language))
        db.execSQL("UPDATE session_languages SET line_count=line_count+? WHERE $COL_SESSION_ID=? AND $COL_LANGUAGE=?",
            arrayOf<Any>(delta, sessionId, language))
        db.execSQL("DELETE FROM session_languages WHERE $COL_SESSION_ID=? AND $COL_LANGUAGE=? AND line_count<=0",
            arrayOf<Any>(sessionId, language))
    }

    private fun removeCatalogEntry(db: SQLiteDatabase, key: TranscriptArchiveKey) {
        val args = arrayOf(key.sessionId.toString(), key.sequence.toString())
        val languages = languagesForKey(db, key)
        if (db.delete("archive_index", "$COL_SESSION_ID=? AND $COL_SEQUENCE=?", args) > 0) {
            updateSessionCount(db, key.sessionId, -1)
            languages.forEach { updateLanguageCount(db, key.sessionId, it, -1) }
        }
    }

    /** One bounded transaction per turn lets writes and user queries run between backup batches. */
    private fun scheduleMaintenance() {
        if (closed.get() || maintenanceScheduled || clockMillis() < nextMaintenanceRetryAt) return
        maintenanceScheduled = true
        executor.execute {
            maintenanceScheduled = false
            if (closed.get()) return@execute
            try {
                val changed = maintainOneBatch(database.writableDatabase)
                maintenanceWarning = null
                nextMaintenanceRetryAt = 0L
                if (changed) {
                    reloadSnapshotInternal(database.readableDatabase)
                    scheduleMaintenance()
                } else if (mutableSnapshot.value.warning != null) reloadSnapshotInternal(database.readableDatabase)
            } catch (error: Exception) {
                activeLineCount = runCatching {
                    scalarLong(database.readableDatabase, "SELECT COUNT(*) FROM $TABLE_LINES")
                }.getOrDefault(activeLineCount)
                // A full or inaccessible backup must never turn into an automatic source deletion.
                maintenanceWarning = "스크립트 백업·보관 정리에 실패해 원본을 유지합니다. " +
                    "저장 공간을 확인하세요. (${error.javaClass.simpleName})"
                nextMaintenanceRetryAt = clockMillis() + MAINTENANCE_RETRY_MILLIS
                mutableSnapshot.value = mutableSnapshot.value.copy(warning = maintenanceWarning)
            }
        }
    }

    private fun maintainOneBatch(db: SQLiteDatabase): Boolean {
        val excess = (activeLineCount - maximumStoredLines).coerceAtLeast(0L)
        val today = archiveDay(clockMillis())
        val day = if (retentionPolicy == TranscriptRetentionPolicy.DAILY_BACKUP) {
            db.rawQuery("SELECT archive_day FROM archive_index WHERE backup_day IS NULL " +
                (if (excess > 0L) "" else "AND archive_day<? ") +
                "ORDER BY archive_day,$COL_RECORDED_AT LIMIT 1",
                if (excess > 0L) null else arrayOf(today)).use { if (it.moveToFirst()) it.getString(0) else null }
        } else null
        if (excess == 0L && day == null) return false
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS retention_batch(" +
            "$COL_SESSION_ID INTEGER,$COL_SEQUENCE INTEGER,PRIMARY KEY($COL_SESSION_ID,$COL_SEQUENCE))")
        db.execSQL("DELETE FROM retention_batch")
        val limit = if (day != null && day < today) RETENTION_BATCH_SIZE
            else minOf(excess, RETENTION_BATCH_SIZE.toLong()).toInt()
        if (limit == 0) return false
        val dayWhere = if (day == null) "" else " AND archive_day=?"
        db.execSQL("INSERT INTO retention_batch SELECT $COL_SESSION_ID,$COL_SEQUENCE FROM archive_index " +
            "WHERE backup_day IS NULL$dayWhere ORDER BY $COL_RECORDED_AT,$COL_SESSION_ID,$COL_SEQUENCE LIMIT $limit",
            if (day == null) emptyArray() else arrayOf(day))
        val count = scalarLong(db, "SELECT COUNT(*) FROM retention_batch")
        if (count == 0L) return false
        if (day != null) backupBatch(db, day, count)
        else inTransaction(db) {
            // Reading only the selected keys keeps retention memory independent of the 500k cap.
            val keys = db.rawQuery("SELECT $COL_SESSION_ID,$COL_SEQUENCE FROM retention_batch", null).use { cursor ->
                buildList { while (cursor.moveToNext()) add(TranscriptArchiveKey(cursor.getLong(0), cursor.getLong(1))) }
            }
            keys.forEach { removeCatalogEntry(db, it) }
            deleteActiveBatch(db)
        }
        activeLineCount -= count
        return true
    }

    private fun backupBatch(db: SQLiteDatabase, day: String, expectedCount: Long) {
        val file = backupFile(day)
        check(backupDirectory.isDirectory || backupDirectory.mkdirs()) { "Backup directory unavailable" }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { backup ->
            backup.setForeignKeyConstraintsEnabled(true)
            if (backup.version == 0) inTransaction(backup) {
                ArchiveDatabase.createContentTables(backup)
                backup.version = 1
            }
            check(backup.version == 1) { "Unsupported backup schema" }
        }
        db.execSQL("ATTACH DATABASE ? AS daily_backup", arrayOf(file.path))
        try {
            db.execSQL("PRAGMA daily_backup.synchronous=FULL")
            // Commit the backup before changing the authoritative location. A crash between these
            // commits leaves a duplicate copy; archive_index still points to exactly one version.
            inTransaction(db) {
                db.execSQL("INSERT OR IGNORE INTO daily_backup.$TABLE_SESSIONS SELECT s.* FROM $TABLE_SESSIONS s " +
                    "WHERE EXISTS(SELECT 1 FROM retention_batch b WHERE b.$COL_SESSION_ID=s.$COL_SESSION_ID)")
                db.execSQL("INSERT OR REPLACE INTO daily_backup.$TABLE_LINES SELECT l.* FROM $TABLE_LINES l " +
                    "JOIN retention_batch b ON b.$COL_SESSION_ID=l.$COL_SESSION_ID AND b.$COL_SEQUENCE=l.$COL_SEQUENCE")
                db.execSQL("INSERT OR REPLACE INTO daily_backup.$TABLE_TRANSLATIONS SELECT t.* FROM $TABLE_TRANSLATIONS t " +
                    "JOIN retention_batch b ON b.$COL_SESSION_ID=t.$COL_SESSION_ID AND b.$COL_SEQUENCE=t.$COL_SEQUENCE")
                check(scalarLong(db, "SELECT COUNT(*) FROM daily_backup.$TABLE_LINES l JOIN retention_batch b " +
                    "ON b.$COL_SESSION_ID=l.$COL_SESSION_ID AND b.$COL_SEQUENCE=l.$COL_SEQUENCE") == expectedCount)
            }
            inTransaction(db) {
                db.execSQL("UPDATE archive_index SET backup_day=archive_day WHERE ($COL_SESSION_ID,$COL_SEQUENCE) " +
                    "IN (SELECT $COL_SESSION_ID,$COL_SEQUENCE FROM retention_batch)")
                deleteActiveBatch(db)
            }
        } finally { db.execSQL("DETACH DATABASE daily_backup") }
    }

    private fun deleteActiveBatch(db: SQLiteDatabase) = db.execSQL(
        "DELETE FROM $TABLE_LINES WHERE ($COL_SESSION_ID,$COL_SEQUENCE) " +
            "IN (SELECT $COL_SESSION_ID,$COL_SEQUENCE FROM retention_batch)",
    )

    private fun backupFile(day: String): File {
        require(day.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
        return File(backupDirectory, "$day.db")
    }

    private fun archiveDay(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private inline fun inTransaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun scalarLong(db: SQLiteDatabase, query: String, args: Array<String>? = null): Long =
        db.rawQuery(query, args).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }

    private fun reloadSnapshot() = runDatabaseOperation {
        reloadSnapshotInternal(database.readableDatabase)
    }

    private fun reloadSnapshotInternal(db: SQLiteDatabase) {
        val sessions = mutableListOf<ArchivedBroadcastSession>()
        db.rawQuery(
            "SELECT s.$COL_SESSION_ID,s.$COL_STARTED_AT,s.$COL_SOURCE_LANGUAGE," +
                "COALESCE(c.line_count,0)," +
                "(SELECT GROUP_CONCAT(t.$COL_LANGUAGE) FROM session_languages t " +
                "WHERE t.$COL_SESSION_ID=s.$COL_SESSION_ID) " +
                "FROM $TABLE_SESSIONS s LEFT JOIN session_counts c ON c.$COL_SESSION_ID=s.$COL_SESSION_ID " +
                "ORDER BY s.$COL_STARTED_AT DESC,s.$COL_SESSION_ID DESC LIMIT $MAX_SNAPSHOT_SESSIONS",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                sessions += ArchivedBroadcastSession(
                    sessionId = cursor.getLong(0),
                    startedAtEpochMillis = cursor.getLong(1),
                    sourceLanguageTag = cursor.getString(2),
                    storedLineCount = cursor.getInt(3),
                    translationLanguages = if (cursor.isNull(4)) emptyList() else cursor.getString(4).split(',').sorted(),
                )
            }
        }
        val lines = readLines(
            db,
            "SELECT * FROM $TABLE_LINES ORDER BY $COL_RECORDED_AT DESC LIMIT $MAX_SNAPSHOT_LINES",
            null,
            "s.$COL_STARTED_AT DESC,l.$COL_SEQUENCE DESC",
        )
        val pendingCount = synchronized(lock) { pending.size }
        mutableSnapshot.value = TranscriptArchiveSnapshot(
            lines = lines,
            pendingWriteCount = pendingCount,
            warning = maintenanceWarning,
            sessions = sessions,
            revision = mutableSnapshot.value.revision + 1L,
            retentionPolicy = retentionPolicy,
        )
    }

    private fun readLines(
        db: SQLiteDatabase,
        selectedLines: String,
        selectionArgs: Array<String>?,
        orderBy: String,
    ): List<ArchivedTranscriptLine> {
        val entries = linkedMapOf<TranscriptArchiveKey, MutableArchivedLine>()
        db.rawQuery(
            "SELECT s.$COL_SESSION_ID,s.$COL_STARTED_AT,s.$COL_SOURCE_LANGUAGE," +
                "l.$COL_SEQUENCE,l.$COL_SOURCE_TEXT,l.$COL_CAPTURED_AT," +
                "t.$COL_LANGUAGE,t.$COL_TRANSLATED_TEXT,t.$COL_TRANSLATE_MS," +
                "t.$COL_FIRST_AUDIO_MS,t.$COL_SYNTHESIS_MS " +
                "FROM ($selectedLines) l " +
                "JOIN $TABLE_SESSIONS s ON s.$COL_SESSION_ID=l.$COL_SESSION_ID " +
                "LEFT JOIN $TABLE_TRANSLATIONS t ON t.$COL_SESSION_ID=l.$COL_SESSION_ID " +
                "AND t.$COL_SEQUENCE=l.$COL_SEQUENCE " +
                "ORDER BY $orderBy",
            selectionArgs,
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
        return entries.map { (key, value) -> value.freeze(key) }
    }

    private inline fun runDatabaseOperation(block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (error: Throwable) {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                warning = "스크립트 저장 오류: " +
                    error.javaClass.simpleName,
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

    private class ArchiveDatabase(context: Context, databaseName: String) :
        SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            createContentTables(db)
            createCatalog(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createCatalog(db)
        }

        private fun createCatalog(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE archive_settings(name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE archive_tombstones($COL_SESSION_ID INTEGER NOT NULL,$COL_SEQUENCE INTEGER NOT NULL," +
                "PRIMARY KEY($COL_SESSION_ID,$COL_SEQUENCE))")
            db.execSQL("CREATE TABLE archive_deleted_sessions($COL_SESSION_ID INTEGER PRIMARY KEY)")
            db.execSQL("CREATE TABLE archive_index($COL_SESSION_ID INTEGER NOT NULL,$COL_SEQUENCE INTEGER NOT NULL," +
                "$COL_RECORDED_AT INTEGER NOT NULL,archive_day TEXT NOT NULL,backup_day TEXT," +
                "session_started_at_epoch_ms INTEGER NOT NULL," +
                "PRIMARY KEY($COL_SESSION_ID,$COL_SEQUENCE)," +
                "FOREIGN KEY($COL_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE archive_languages($COL_SESSION_ID INTEGER NOT NULL,$COL_SEQUENCE INTEGER NOT NULL," +
                "$COL_LANGUAGE TEXT NOT NULL,PRIMARY KEY($COL_SESSION_ID,$COL_SEQUENCE,$COL_LANGUAGE)," +
                "FOREIGN KEY($COL_SESSION_ID,$COL_SEQUENCE) REFERENCES archive_index($COL_SESSION_ID,$COL_SEQUENCE) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE session_counts($COL_SESSION_ID INTEGER PRIMARY KEY,line_count INTEGER NOT NULL," +
                "FOREIGN KEY($COL_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE session_languages($COL_SESSION_ID INTEGER NOT NULL,$COL_LANGUAGE TEXT NOT NULL," +
                "line_count INTEGER NOT NULL,PRIMARY KEY($COL_SESSION_ID,$COL_LANGUAGE)," +
                "FOREIGN KEY($COL_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX sessions_started ON $TABLE_SESSIONS($COL_STARTED_AT,$COL_SESSION_ID)")
            db.execSQL("CREATE INDEX lines_recorded ON $TABLE_LINES($COL_RECORDED_AT)")
            db.execSQL("CREATE INDEX archive_active_recorded ON archive_index(backup_day,$COL_RECORDED_AT,$COL_SESSION_ID,$COL_SEQUENCE)")
            db.execSQL("CREATE INDEX archive_active_day ON archive_index(backup_day,archive_day,$COL_RECORDED_AT)")
            db.execSQL("CREATE INDEX archive_page_newest ON archive_index(session_started_at_epoch_ms DESC,$COL_SESSION_ID DESC,$COL_SEQUENCE ASC)")
            db.execSQL("CREATE INDEX archive_page_oldest ON archive_index(session_started_at_epoch_ms ASC,$COL_SESSION_ID ASC,$COL_SEQUENCE ASC)")
            db.execSQL("CREATE INDEX archive_language_filter ON archive_languages($COL_LANGUAGE,$COL_SESSION_ID,$COL_SEQUENCE)")
            // One SQL migration, preserving old sessions, final text and all latency fields.
            db.execSQL("INSERT INTO archive_index SELECT l.$COL_SESSION_ID,l.$COL_SEQUENCE,l.$COL_RECORDED_AT," +
                "strftime('%Y-%m-%d',l.$COL_RECORDED_AT/1000,'unixepoch','localtime'),NULL,s.$COL_STARTED_AT " +
                "FROM $TABLE_LINES l JOIN $TABLE_SESSIONS s ON s.$COL_SESSION_ID=l.$COL_SESSION_ID")
            db.execSQL("INSERT INTO archive_languages SELECT $COL_SESSION_ID,$COL_SEQUENCE,$COL_LANGUAGE FROM $TABLE_TRANSLATIONS")
            db.execSQL("INSERT INTO session_counts SELECT $COL_SESSION_ID,COUNT(*) FROM archive_index GROUP BY $COL_SESSION_ID")
            db.execSQL("INSERT INTO session_languages SELECT $COL_SESSION_ID,$COL_LANGUAGE,COUNT(*) FROM archive_languages " +
                "GROUP BY $COL_SESSION_ID,$COL_LANGUAGE")
        }

        companion object {
        fun createContentTables(db: SQLiteDatabase) {
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
        }
    }

    private companion object {
        const val DATABASE_NAME = "broadcast-transcripts.db"
        const val DATABASE_VERSION = 2
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
        const val MAX_RECENT_DELETIONS = 4_096
        const val INITIAL_RETRY_DELAY_MILLIS = 250L
        const val MAX_RETRY_DELAY_MILLIS = 5_000L
        const val MAX_STORED_LINES = 500_000
        const val MAX_SNAPSHOT_LINES = 1_000
        const val MAX_SNAPSHOT_SESSIONS = 1_000
        const val RETENTION_BATCH_SIZE = 1_000
        const val MAINTENANCE_RETRY_MILLIS = 60_000L
    }
}

internal const val ARCHIVE_PAGE_SIZE = 1_000

/** Hidden selections cannot leak into a delete operation after a filter or page change. */
internal fun visibleArchiveSelection(
    selected: Set<TranscriptArchiveKey>,
    visibleLines: List<ArchivedTranscriptLine>,
): Set<TranscriptArchiveKey> = selected.intersect(visibleLines.mapTo(mutableSetOf()) { it.key })

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
