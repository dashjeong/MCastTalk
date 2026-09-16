package app.guidecast.transmitter

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Real restart orchestration with synthetic provider output; no physical ASR/quality claim. */
class RecognitionReconnectDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun frames() = flow {
        while (currentCoroutineContext().isActive) {
            emit(PcmAudioFrame(ByteArray(640) { if (it % 4 == 1) 32 else 0 }, SystemClock.elapsedRealtimeNanos()))
            delay(20)
        }
    }

    @Test fun operatorReconnectKeepsOutputIdsAndPendingTailWithoutReplayingCommittedText(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val fake = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                val attempt = attempts.incrementAndGet()
                emit(RecognizedUtterance(0, "Attempt $attempt first sentence. Attempt $attempt second sentence.",
                    "en-US", true, SystemClock.elapsedRealtimeNanos()))
                awaitCancellation()
            }
        }
        val engine = GalaxySpeechRecognitionEngine(context, recognitionEngineOverride = fake)
        val finals = mutableListOf<RecognizedUtterance>()
        try {
            withTimeout(15_000L) {
                engine.recognize(frames(), SpeechRecognitionConfig("en-US")).filter { it.isFinal }.take(4).collect {
                    finals += it
                    if (finals.size == 1 || finals.size == 3) assertTrue(engine.requestReconnect())
                }
            }
            assertEquals(listOf("Attempt 1 first sentence.", "Attempt 1 second sentence.",
                "Attempt 2 first sentence.", "Attempt 2 second sentence."), finals.map { it.text })
            assertEquals(4, finals.map { it.sequence }.toSet().size)
            assertTrue(finals.zipWithNext().all { (a,b) -> a.sequence < b.sequence })
            assertFalse(engine.requestReconnect())
        } finally { engine.close() }
    }

    @Test fun repeatedProviderFailuresWaitForOperatorThenResumeInTheSameFlow(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val fake = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow {
                if (attempts.incrementAndGet() <= 3) throw IllegalStateException("Synthetic provider failure")
                emit(RecognizedUtterance(0, "Recovered first sentence. Recovered second sentence.", "en-US", true,
                    SystemClock.elapsedRealtimeNanos()))
                awaitCancellation()
            }
        }
        val engine = GalaxySpeechRecognitionEngine(context, recognitionEngineOverride = fake)
        val inputFrames = AtomicInteger()
        try {
            withTimeout(15_000L) {
                val result = async { engine.recognize(frames().onEach { inputFrames.incrementAndGet() },
                    SpeechRecognitionConfig("en-US")).first { it.isFinal } }
                engine.status.first { !it.isReady && it.message.contains("통역 다시 연결") }
                val countAtWait = inputFrames.get()
                withTimeout(3_000L) {
                    while (inputFrames.get() < countAtWait + 64) delay(20)
                }
                assertFalse(result.isCompleted)
                assertEquals(3, attempts.get())
                assertTrue(engine.requestReconnect())
                assertEquals("Recovered first sentence.", result.await().text)
                assertEquals(4, attempts.get())
                assertFalse(engine.requestReconnect())
            }
        } finally { engine.close() }
    }

    @Test fun providerFailureWaitDrainsFiniteInputAndClosesAtEof(): Unit = runBlocking {
        val attempts = AtomicInteger()
        val inputFrames = AtomicInteger()
        val fake = object : SpeechRecognitionEngine {
            override fun recognize(frames: Flow<PcmAudioFrame>, config: SpeechRecognitionConfig) = flow<RecognizedUtterance> {
                attempts.incrementAndGet()
                throw IllegalStateException("Synthetic persistent provider failure")
            }
        }
        val engine = GalaxySpeechRecognitionEngine(context, recognitionEngineOverride = fake)
        try {
            val output = withTimeout(15_000L) {
                engine.recognize(frames().take(200).onEach { inputFrames.incrementAndGet() },
                    SpeechRecognitionConfig("en-US")).toList()
            }
            assertTrue(output.isEmpty())
            assertEquals(200, inputFrames.get())
            assertEquals(3, attempts.get())
            assertFalse(engine.requestReconnect())
        } finally { engine.close() }
    }
}
