package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import app.guidecast.provider.moonshine.stt.MoonshineSpeechRecognitionEngine
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real native inference, exact public PCM, normal EOF: no take/first/timeout cancellation as success. */
class FiniteSpeechInputDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeFinitePcmFlushesItsLastWordsAndCompletesTwiceWithoutCancellation(): Unit = runBlocking {
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        // This direct provider still shares the app's native service. Without its lifecycle lease,
        // the preceding app-level test's idle cleanup can shut down this otherwise active client.
        app.withTranslationBackendUse {
            val recognizer = MoonshineSpeechRecognitionEngine(instrumentation.targetContext)
            try {
                val ready = withTimeout(10 * 60_000L) {
                    recognizer.prepareLanguageAssets("ko-KR")
                    app.withProcessNativeColdLoadLease(
                        key = ProcessNativeColdLoadKeys.speechRecognition("ko-KR"),
                        serializeWithAllColdLoads = true,
                    ) { recognizer.prepareNativeLanguage("ko-KR") }
                }
                assertTrue(ready.message, ready.isReady)
                repeat(2) { assertFiniteCompletion(recognizer) }
            } finally { recognizer.close() }
        }
    }

    @Test fun applicationSemanticAssemblerCompletesFinitePcmWithNoPendingTail(): Unit = runBlocking {
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        withPreparedLocalSpeechRecognition(app, "ko-KR") { recognizer ->
            assertFiniteCompletion(recognizer)
        }
    }

    private suspend fun assertFiniteCompletion(recognizer: SpeechRecognitionEngine) {
        val pcm = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        assertEquals(224_640, pcm.size)
        assertEquals(FIXTURE_HASH, sha256(pcm))
        val fed = MessageDigest.getInstance("SHA-256")
        var consumed = 0
        val finals = linkedMapOf<Long, RecognizedUtterance>()
        val pending = linkedMapOf<Long, RecognizedUtterance>()
        var inputReachedEof = false
        var recognitionCompleted = false
        withTimeout(90_000L) {
            recognizer.recognize(flow {
                for (offset in pcm.indices step 640) {
                    val bytes = pcm.copyOfRange(offset, minOf(offset + 640, pcm.size))
                    fed.update(bytes)
                    emit(PcmAudioFrame(bytes, SystemClock.elapsedRealtimeNanos()))
                    consumed += bytes.size
                    delay(bytes.size / 32L)
                }
                // No appended endpoint silence: native EOF must perform its own final pass.
                inputReachedEof = true
            }, SpeechRecognitionConfig("ko-KR")).collect { value ->
                when {
                    value.isRetracted -> pending.remove(value.sequence)
                    value.isFinal -> {
                        assertNull("Duplicate final sequence", finals.put(value.sequence, value))
                        pending.remove(value.sequence)
                    }
                    else -> pending[value.sequence] = value
                }
            }
            recognitionCompleted = true
        }
        assertTrue("Finite input must actually reach EOF", inputReachedEof)
        assertTrue("Recognition must complete normally without collector cancellation", recognitionCompleted)
        assertEquals(pcm.size, consumed)
        assertEquals(FIXTURE_HASH, fed.digest().joinToString("") { "%02x".format(it) })
        assertTrue("Native/semantic tail remained pending at EOF: ${pending.keys}", pending.isEmpty())
        assertTrue("No final transcript reached the caller", finals.isNotEmpty())
        val text = finals.values.joinToString(" ") { it.text }
        listOf("표지판", "경고", "주의").forEach { word ->
            assertTrue("Public fixture tail word is missing at normal EOF: $word", text.contains(word))
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FIXTURE_HASH = "b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f"
    }
}
