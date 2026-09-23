package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One real native STT session receives the same public sentence three times at natural speed.
 * Digital PCM does not prove physical-microphone quality, human translation equivalence or a soak.
 */
class RepeatedSemanticSpeechDeviceTest {
    @Test
    fun repeatedPublicKoreanSpeechKeepsMeaningWordsInEachLiveSentence(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val fixture = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        assertEquals(224_640, fixture.size)
        assertEquals(
            "b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f",
            MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) },
        )
        assertTrue("The public speech fixture must contain audible S16 PCM", fixture.pcmS16LeSignalStats().rms > 0.002f)
        assertEquals("16 kHz mono S16 speech must contain complete 20 ms frames", 0, fixture.size % FRAME_BYTES)

        val finals = CopyOnWriteArrayList<RecognizedUtterance>()
        val previews = CopyOnWriteArrayList<RecognizedUtterance>()
        val roundsCompleted = CompletableDeferred<Unit>()
        withTimeout(240_000) {
            withPreparedLocalSpeechRecognition(app, "ko-KR") { engine ->
                coroutineScope {
                    var lastCaptureNanos = -1L
                    suspend fun FlowCollector<PcmAudioFrame>.sendFrame(bytes: ByteArray) {
                        assertEquals(FRAME_BYTES, bytes.size)
                        val captureNanos = SystemClock.elapsedRealtimeNanos()
                        assertTrue("Input frame capture times must advance", captureNanos > lastCaptureNanos)
                        lastCaptureNanos = captureNanos
                        emit(PcmAudioFrame(bytes, captureNanos))
                        delay(FRAME_MILLIS)
                    }
                    val input = flow {
                        repeat(50) { sendFrame(ByteArray(FRAME_BYTES)) }
                        repeat(ROUNDS) { round ->
                            assertEquals("A prior round must commit exactly once", round, finals.size)
                            var offset = 0
                            while (offset < fixture.size) {
                                sendFrame(fixture.copyOfRange(offset, offset + FRAME_BYTES))
                                offset += FRAME_BYTES
                            }
                            // Keep the stream live. A manual stop/EOF must not manufacture this final.
                            withTimeout(35_000) {
                                while (finals.size <= round) sendFrame(ByteArray(FRAME_BYTES))
                            }
                            repeat(50) { sendFrame(ByteArray(FRAME_BYTES)) }
                            assertEquals("One spoken sentence must produce one semantic final", round + 1, finals.size)
                        }
                        roundsCompleted.complete(Unit)
                        while (true) sendFrame(ByteArray(FRAME_BYTES))
                    }
                    val recognition = launch {
                        engine.recognize(input, SpeechRecognitionConfig("ko-KR", SAMPLE_RATE_HZ, 1)).collect { utterance ->
                            if (utterance.isFinal) {
                                assertTrue("No semantic final may be fabricated after the requested three rounds", finals.size < ROUNDS)
                                assertTrue(
                                    "Each real final must preserve the fixture's sign/warning/attention meaning together: ${utterance.text}",
                                    REQUIRED_MEANING_WORDS.all { it in utterance.text },
                                )
                                assertTrue("A completed sentence cannot retract its source", !utterance.isRetracted)
                                finals += utterance
                            } else if (!utterance.isRetracted) {
                                previews += utterance
                            }
                        }
                    }
                    try {
                        roundsCompleted.await()
                        assertTrue("The recognizer must remain active after all three sentences", recognition.isActive)
                        assertEquals(ROUNDS, finals.size)
                        assertEquals("Repeated legitimate speech needs independent identities", ROUNDS, finals.map { it.sequence }.toSet().size)
                        assertTrue("Semantic finals must preserve source order", finals.zipWithNext().all { (a, b) ->
                            a.sequence < b.sequence &&
                                a.capturedAtElapsedRealtimeNanos <= b.capturedAtElapsedRealtimeNanos
                        })
                        assertTrue("Live revisable text must be available before sentence completion", previews.isNotEmpty())
                    } finally {
                        recognition.cancelAndJoin()
                    }
                }
            }
        }
    }

    private companion object {
        const val ROUNDS = 3
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_MILLIS = 20L
        const val FRAME_BYTES = 640
        val REQUIRED_MEANING_WORDS = listOf("표지판", "경고", "주의")
    }
}
