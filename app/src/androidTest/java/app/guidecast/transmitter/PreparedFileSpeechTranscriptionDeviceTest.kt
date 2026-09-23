package app.guidecast.transmitter

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual WAV decoder -> prepared app STT -> returned script, without a platform recognizer mock. */
class PreparedFileSpeechTranscriptionDeviceTest {
    @Test fun koreanWaveFileProducesScriptThroughPublicTranscriberEntry() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val pcm = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        assertEquals("b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f",
            MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { "%02x".format(it) })
        val file = File.createTempFile("synthetic-file-stt-", ".wav", app.cacheDir)
        try {
            VoiceNoteWav(file).use { it.append(pcm, pcm.size) }
            val expectedBytes = file.readBytes()
            var observedProgress = false
            val result = withTimeout(180_000) {
                FileSpeechTranscriber.transcribe(app, Uri.fromFile(file), "ko-KR") { progress ->
                    if (progress.processedMs > 0) observedProgress = true
                }
            }
            assertTrue(observedProgress)
            assertTrue(result.segments.isNotEmpty())
            val text = result.segments.joinToString(" ") { it.text }
            assertTrue("Decoded Korean voice must produce meaningful script text",
                listOf("관계자", "조언", "표지판", "안전", "경고", "주의").count { it in text } >= 3)
            assertTrue(result.durationMs in 7_000..7_100)
            assertTrue(result.segments.all { it.endMs <= result.durationMs && it.timingEstimated })
            assertEquals("ko-KR", result.sourceLanguageTag)
            assertArrayEquals(expectedBytes, file.readBytes())
        } finally { file.delete() }
    }
}
