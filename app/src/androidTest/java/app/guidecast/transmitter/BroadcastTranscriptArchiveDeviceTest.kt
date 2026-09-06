package app.guidecast.transmitter

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BroadcastTranscriptArchiveDeviceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun finalizedTranslationSurvivesReopenAndSelectedDelete() {
        val first = BroadcastTranscriptArchive(context)
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

        val reopened = BroadcastTranscriptArchive(context)
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

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 4_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25L)
        }
        check(predicate()) { "Timed out waiting for transcript archive state" }
    }

    private companion object {
        const val DATABASE_NAME = "broadcast-transcripts.db"
    }
}
