package app.guidecast.transmitter

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

class BroadcastTranscriptArchiveDeviceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME)
        backupDirectory().deleteRecursively()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
        backupDirectory().deleteRecursively()
    }

    @Test
    fun finalizedTranslationSurvivesReopenAndSelectedDelete() {
        val first = BroadcastTranscriptArchive(context, DATABASE_NAME)
        val sessionId = first.beginSession("ko-KR")
        val key = TranscriptArchiveKey(sessionId, 7L)
        first.enqueue(
            sessionId,
            TranslationTranscriptLine(
                sequence = 7L,
                sourceText = "안녕하세요",
                capturedAtElapsedRealtimeNanos = 77L,
                isFinal = true,
                translations = mapOf("en" to "Hello"),
                firstAudioLatencyMillis = mapOf("en" to 820L),
            ),
        )
        waitFor { first.snapshot.value.lines.any { it.key == key } }
        first.close()

        val reopened = BroadcastTranscriptArchive(context, DATABASE_NAME)
        waitFor { reopened.snapshot.value.lines.any { it.key == key } }
        val restored = reopened.snapshot.value.lines.single { it.key == key }
        assertEquals("ko-KR", restored.line.sourceLanguageTag)
        assertEquals("Hello", restored.line.translations["en"])
        assertEquals(820L, restored.line.firstAudioLatencyMillis["en"])

        reopened.delete(setOf(key))
        waitFor { reopened.snapshot.value.lines.none { it.key == key } }
        assertNull(reopened.snapshot.value.warning)
        reopened.close()
    }

    @Test
    fun emptyBroadcastStartIsVisibleAndSurvivesReopen() {
        val startedAt = System.currentTimeMillis()
        val first = BroadcastTranscriptArchive(context, DATABASE_NAME)
        val sessionId = first.beginSession("ja-JP")
        waitFor { first.snapshot.value.sessions.any { it.sessionId == sessionId } }
        assertEquals(0, first.snapshot.value.sessions.single().storedLineCount)
        assertTrue(first.snapshot.value.sessions.single().startedAtEpochMillis >= startedAt)
        first.close()
        BroadcastTranscriptArchive(context, DATABASE_NAME).use { reopened ->
            waitFor { reopened.snapshot.value.sessions.any { it.sessionId == sessionId } }
            assertEquals("ja-JP", reopened.snapshot.value.sessions.single().sourceLanguageTag)
            assertTrue(reopened.snapshot.value.lines.isEmpty())
        }
    }

    @Test
    fun legacyDatabaseKeepsSessionsAndPagesOlderThanLiveSnapshot() = runBlocking {
        seedVersionOneArchive()
        BroadcastTranscriptArchive(context, DATABASE_NAME).use { archive ->
            waitFor { archive.snapshot.value.sessions.size == 2 }
            assertEquals(1_000, archive.snapshot.value.lines.size)
            assertTrue(archive.snapshot.value.lines.none { it.key.sessionId == 1L })
            val oldPage = archive.loadPage(TranscriptArchiveFilter(sessionId = 1L))
            assertEquals(1, oldPage.totalMatchingLines)
            assertEquals("older source", oldPage.lines.single().line.sourceText)
            assertEquals("older translation", oldPage.lines.single().line.translations["en"])
            val oldest = archive.loadPage(TranscriptArchiveFilter(oldestSessionFirst = true))
            assertEquals(1L, oldest.lines.first().key.sessionId)
            val newest = archive.loadPage(TranscriptArchiveFilter())
            assertEquals(2L, newest.lines.first().key.sessionId)
            assertEquals(1L, newest.lines.first().key.sequence)
            val next = archive.loadPage(TranscriptArchiveFilter(sessionId = 2L, pageIndex = 1))
            assertEquals(1_001L, next.lines.first().key.sequence)
            assertEquals(5, next.lines.size)
            val language = archive.loadPage(TranscriptArchiveFilter(languageTag = "en"))
            assertEquals(1, language.totalMatchingLines)
            assertEquals(1L, language.lines.single().key.sessionId)
            // Browsing old sessions must not replace the independent live listener snapshot.
            assertTrue(archive.snapshot.value.lines.none { it.key.sessionId == 1L })
            assertNull(archive.snapshot.value.warning)
        }
    }

    @Test
    fun dailyBackupSurvivesReopenPeriodQueryAndLateUpdateWithoutDuplicate() = runBlocking {
        val dayStart = LocalDate.now().minusDays(3).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = AtomicLong(dayStart + 12L * 60 * 60 * 1_000)
        var firstSession = 0L
        val line = TranslationTranscriptLine(1L, "synthetic archived source", 1L, true,
            translations = mapOf("en" to "first translation"), firstAudioLatencyMillis = mapOf("en" to 80L))
        BroadcastTranscriptArchive(context, DATABASE_NAME, clockMillis = now::get).use { archive ->
            firstSession = archive.beginSession("ko-KR")
            archive.enqueue(firstSession, line)
            waitFor { archive.snapshot.value.lines.size == 1 }
            archive.setRetentionPolicy(TranscriptRetentionPolicy.DAILY_BACKUP)
            waitFor { archive.snapshot.value.retentionPolicy == TranscriptRetentionPolicy.DAILY_BACKUP }
            now.addAndGet(24L * 60 * 60 * 1_000)
            val nextSession = archive.beginSession("ja-JP")
            archive.enqueue(nextSession, line.copy(sourceText = "next day source"))
            waitFor { runBlocking { archive.loadPage(TranscriptArchiveFilter(sessionId = firstSession)) }
                .lines.singleOrNull()?.isDailyBackup == true }
            archive.enqueue(firstSession, line.copy(translations = mapOf("en" to "late final translation")))
            waitFor { runBlocking { archive.loadPage(TranscriptArchiveFilter(sessionId = firstSession)) }
                .lines.singleOrNull()?.let { it.isDailyBackup && it.line.translations["en"] == "late final translation" } == true }
            val page = archive.loadPage(TranscriptArchiveFilter(startedFromEpochMillis = dayStart,
                startedBeforeEpochMillis = dayStart + 24L * 60 * 60 * 1_000, languageTag = "en"))
            assertEquals(1, page.totalMatchingLines)
            assertEquals(firstSession, page.lines.single().key.sessionId)
            assertEquals(80L, page.lines.single().line.firstAudioLatencyMillis["en"])
            assertNull(archive.snapshot.value.warning)
        }
        BroadcastTranscriptArchive(context, DATABASE_NAME, clockMillis = now::get).use { reopened ->
            waitFor { reopened.snapshot.value.retentionPolicy == TranscriptRetentionPolicy.DAILY_BACKUP }
            assertEquals(2, reopened.loadPage(TranscriptArchiveFilter()).totalMatchingLines)
            val key = TranscriptArchiveKey(firstSession, 1L)
            reopened.delete(setOf(key))
            waitFor { runBlocking { reopened.loadPage(TranscriptArchiveFilter()) }.totalMatchingLines == 1 }
            reopened.enqueue(firstSession, line)
            assertTrue(reopened.loadPage(TranscriptArchiveFilter(sessionId = firstSession)).lines.isEmpty())
            // Removing a backed-up item hides its catalog entry; the existing backup stays intact.
            assertTrue(backupDirectory().listFiles().orEmpty().any { it.extension == "db" })
            val backupFile = backupDirectory().listFiles().orEmpty().single { it.extension == "db" }
            android.database.sqlite.SQLiteDatabase.openDatabase(backupFile.path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { backup ->
                backup.rawQuery("SELECT COUNT(*) FROM transcript_lines WHERE session_id=? AND sequence_id=1",
                    arrayOf(firstSession.toString())).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
            }
        }
        BroadcastTranscriptArchive(context, DATABASE_NAME, clockMillis = now::get).use { reopened ->
            reopened.enqueue(firstSession, line.copy(translations = mapOf("en" to "late after reopen")))
            assertTrue(reopened.loadPage(TranscriptArchiveFilter(sessionId = firstSession)).lines.isEmpty())
        }
    }

    @Test
    fun backupFailureKeepsOriginalBeyondCapacityAndDoesNotStopNewWrites() = runBlocking {
        // A regular file at the dedicated directory reliably injects a backup write failure.
        backupDirectory().parentFile!!.mkdirs()
        backupDirectory().writeText("synthetic obstruction")
        BroadcastTranscriptArchive(context, DATABASE_NAME, maximumStoredLines = 2).use { archive ->
            archive.setRetentionPolicy(TranscriptRetentionPolicy.DAILY_BACKUP)
            val session = archive.beginSession("ko-KR")
            for (sequence in 1L..3L) archive.enqueue(session,
                TranslationTranscriptLine(sequence, "synthetic $sequence", sequence, true))
            waitFor { archive.snapshot.value.warning?.contains("원본을 유지") == true }
            assertEquals(3, archive.loadPage(TranscriptArchiveFilter()).totalMatchingLines)
            archive.enqueue(session, TranslationTranscriptLine(4L, "new input still persists", 4L, true))
            waitFor { archive.snapshot.value.lines.size == 4 }
            assertEquals(4, archive.loadPage(TranscriptArchiveFilter()).totalMatchingLines)
            assertTrue(archive.snapshot.value.warning?.contains("원본을 유지") == true)
        }
    }

    @Test
    fun sameDayOverflowBacksUpOldestAndKeepsAllRowsQueryable() = runBlocking {
        BroadcastTranscriptArchive(context, DATABASE_NAME, maximumStoredLines = 2).use { archive ->
            archive.setRetentionPolicy(TranscriptRetentionPolicy.DAILY_BACKUP)
            val session = archive.beginSession("ko-KR")
            for (sequence in 1L..3L) archive.enqueue(session,
                TranslationTranscriptLine(sequence, "synthetic $sequence", sequence, true))
            waitFor { runBlocking { archive.loadPage(TranscriptArchiveFilter()) }.lines.count { it.isDailyBackup } == 1 }
            val all = archive.loadPage(TranscriptArchiveFilter())
            assertEquals(3, all.totalMatchingLines)
            assertEquals(1L, all.lines.single { it.isDailyBackup }.key.sequence)
            assertEquals(2, archive.snapshot.value.lines.size)
            assertEquals(3, archive.snapshot.value.sessions.single().storedLineCount)
            assertNull(archive.snapshot.value.warning)
        }
    }

    @Test
    fun halfMillionRowsUseBoundedPagesAndOverwriteOnlyOldestOverflow() = runBlocking {
        seedVersionOneArchive()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DELETE FROM translations")
            db.execSQL("DELETE FROM transcript_lines")
            db.execSQL("WITH RECURSIVE rows(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM rows WHERE n<500002) " +
                "INSERT INTO transcript_lines SELECT 2,n,'synthetic scale source',1,n,?+n FROM rows",
                arrayOf(System.currentTimeMillis() - 500_002L))
        }
        BroadcastTranscriptArchive(context, DATABASE_NAME).use { archive ->
            waitFor(30_000L) { archive.snapshot.value.sessions.any { it.sessionId == 2L && it.storedLineCount == 500_000 } }
            assertEquals(1_000, archive.snapshot.value.lines.size)
            val first = archive.loadPage(TranscriptArchiveFilter(sessionId = 2L))
            assertEquals(500_000, first.totalMatchingLines)
            assertEquals(1_000, first.lines.size)
            assertEquals(3L, first.lines.first().key.sequence)
            val last = archive.loadPage(TranscriptArchiveFilter(sessionId = 2L, pageIndex = 499))
            assertEquals(1_000, last.lines.size)
            assertEquals(500_002L, last.lines.last().key.sequence)
            assertTrue(archive.loadPage(TranscriptArchiveFilter(sessionId = 2L, pageIndex = 500)).lines.isEmpty())
            assertNull(archive.snapshot.value.warning)
        }
    }

    private fun backupDirectory() = File(context.noBackupFilesDir, "transcript-backups/$DATABASE_NAME")

    @Test
    fun deleteScopeDoesNotCrossSessionsAndLateCallbacksCannotRestoreDeletedRows() = runBlocking {
        BroadcastTranscriptArchive(context, DATABASE_NAME).use { archive ->
            val first = archive.beginSession("ko-KR")
            val second = archive.beginSession("ko-KR")
            val line = TranslationTranscriptLine(1L, "synthetic source", 1L, true,
                translations = mapOf("en" to "synthetic translation"))
            archive.enqueue(first, line)
            archive.enqueue(second, line)
            waitFor { archive.snapshot.value.lines.size == 2 }
            val key = TranscriptArchiveKey(first, 1L)
            archive.delete(setOf(key))
            archive.enqueue(first, line.copy(translations = mapOf("en" to "late update")))
            waitFor { archive.snapshot.value.lines.size == 1 }
            assertEquals(second, archive.snapshot.value.lines.single().key.sessionId)
            assertTrue(archive.loadPage(TranscriptArchiveFilter(sessionId = first)).lines.isEmpty())
            archive.deleteSession(second)
            archive.enqueue(second, line.copy(sequence = 2L))
            waitFor { archive.snapshot.value.sessions.none { it.sessionId == second } }
            assertTrue(archive.loadPage(TranscriptArchiveFilter(sessionId = second)).lines.isEmpty())
            assertNull(archive.snapshot.value.warning)
        }
    }

    /** Exact v1 schema fixture; opening newer code must preserve all existing records. */
    private fun seedVersionOneArchive() {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE sessions (session_id INTEGER PRIMARY KEY,started_at_epoch_ms INTEGER NOT NULL,source_language TEXT NOT NULL)")
            db.execSQL("CREATE TABLE transcript_lines (session_id INTEGER NOT NULL,sequence_id INTEGER NOT NULL,source_text TEXT NOT NULL,is_final INTEGER NOT NULL,captured_at_elapsed_ns INTEGER NOT NULL,recorded_at_epoch_ms INTEGER NOT NULL,PRIMARY KEY(session_id,sequence_id),FOREIGN KEY(session_id) REFERENCES sessions(session_id) ON DELETE CASCADE)")
            db.execSQL("CREATE TABLE translations (session_id INTEGER NOT NULL,sequence_id INTEGER NOT NULL,language_tag TEXT NOT NULL,translated_text TEXT NOT NULL,translate_ms INTEGER,first_audio_ms INTEGER,synthesis_ms INTEGER,PRIMARY KEY(session_id,sequence_id,language_tag),FOREIGN KEY(session_id,sequence_id) REFERENCES transcript_lines(session_id,sequence_id) ON DELETE CASCADE)")
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO sessions VALUES(1,?, 'ko-KR')", arrayOf(now - 60_000L))
                db.execSQL("INSERT INTO sessions VALUES(2,?, 'ko-KR')", arrayOf(now))
                db.execSQL("INSERT INTO transcript_lines VALUES(1,1,'older source',1,1,?)", arrayOf(now - 60_000L))
                db.execSQL("INSERT INTO translations VALUES(1,1,'en','older translation',10,20,30)")
                for (sequence in 1..1_005) {
                    db.execSQL("INSERT INTO transcript_lines VALUES(2,?,'recent source',1,1,?)", arrayOf<Any>(sequence, now + sequence))
                }
                db.version = 1
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }

    private fun waitFor(timeoutMillis: Long = 4_000L, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25L)
        }
        check(predicate()) { "Timed out waiting for transcript archive state" }
    }

    private companion object {
        const val DATABASE_NAME = "broadcast-transcripts-regression.db"
    }
}
