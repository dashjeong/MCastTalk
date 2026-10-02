package app.guidecast.transmitter

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
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

    @Test
    fun e2bStandardSpeechToTranslationPipelineSucceeds(): Unit = runBlocking {
        assertKoreanSpeechToGemmaTranslationPipeline(
            variant = GemmaModelVariant.STANDARD,
            captureDelayNanos = 0L,
        )
    }

    @Test
    fun e4bModelOptionSpeechToTranslationPipelineSucceeds(): Unit = runBlocking {
        assertKoreanSpeechToGemmaTranslationPipeline(
            variant = GemmaModelVariant.E4B_IT,
            captureDelayNanos = 0L,
        )
    }

    @Test
    fun lastE4bSentenceTranslatesWhileInputRemainsOpenWithoutNextSpeech(): Unit = runBlocking {
        assertKoreanSpeechToGemmaTranslationPipeline(
            variant = GemmaModelVariant.E4B_IT,
            captureDelayNanos = 0L,
            rounds = 1,
            idleFinalDeadlineMillis = 10_000L,
        )
    }

    /**
     * Evaluates regression where PCM capture timestamps lag behind wallclock (e.g. 800ms)
     * while the E4B resident model is active. Note: Whether E4B CPU contention alone produced
     * this lag in production remains an unproven hypothesis; this test proves pipeline tolerance
     * to delayed capture timestamps regardless of root workload cause.
     */
    @Test
    fun e4bModelOptionWithDelayedPcmCaptureTimestampsSucceeds(): Unit = runBlocking {
        assertKoreanSpeechToGemmaTranslationPipeline(
            variant = GemmaModelVariant.E4B_IT,
            captureDelayNanos = 800_000_000L,
        )
    }

    private suspend fun assertKoreanSpeechToGemmaTranslationPipeline(
        variant: GemmaModelVariant,
        captureDelayNanos: Long,
        rounds: Int = ROUNDS,
        idleFinalDeadlineMillis: Long = 35_000L,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val provider = app.gemmaTranslationProvider
        val manager = provider.modelManager
        val initialVariant = manager.selectedVariant

        val fixture = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        val finals = CopyOnWriteArrayList<RecognizedUtterance>()
        assertEquals(224_640, fixture.size)
        assertEquals("b35aa5acf7ff72a4ec1b68415957ac9e7fe89268312cc8f5e5325a88acea841f",
            MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) })
        val translations = CopyOnWriteArrayList<String>()
        val roundsCompleted = CompletableDeferred<Unit>()
        val translationQueue = Channel<RecognizedUtterance>(capacity = 16)
        val backendLease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))

        try {
            provider.applyVerifiedModel(variant)
            assertEquals(variant, manager.appliedVariant)
            assertTrue("Selected model must be resident before speech starts", provider.hasActivePreparedWorker())
            val translator = provider.engineFor("en")

            withTimeout(300_000L) {
                withPreparedLocalSpeechRecognition(app, "ko-KR") { engine ->
                    coroutineScope {
                        var lastCaptureNanos = -1L
                        suspend fun FlowCollector<PcmAudioFrame>.sendFrame(bytes: ByteArray) {
                            val rawNanos = SystemClock.elapsedRealtimeNanos()
                            val captureNanos = (rawNanos - captureDelayNanos).coerceAtLeast(0L)
                            assertTrue("Input frame capture times must advance", rawNanos > lastCaptureNanos)
                            lastCaptureNanos = rawNanos
                            emit(PcmAudioFrame(bytes, captureNanos))
                            delay(FRAME_MILLIS)
                        }
                        val input = flow {
                            repeat(50) { sendFrame(ByteArray(FRAME_BYTES)) }
                            repeat(rounds) { round ->
                                assertEquals("Prior round must commit before next", round, finals.size)
                                var offset = 0
                                while (offset < fixture.size) {
                                    sendFrame(fixture.copyOfRange(offset, offset + FRAME_BYTES))
                                    offset += FRAME_BYTES
                                }
                                val speechEndedAt = SystemClock.elapsedRealtime()
                                withTimeout(idleFinalDeadlineMillis) {
                                    while (finals.size <= round) sendFrame(ByteArray(FRAME_BYTES))
                                }
                                Log.i("GemmaSpeechGate", "idleFinalMs=${SystemClock.elapsedRealtime() - speechEndedAt} " +
                                    "round=$round inputRemainsOpen=true noNextSpeech=true")
                                repeat(50) { sendFrame(ByteArray(FRAME_BYTES)) }
                                assertEquals("Round $round must produce semantic final", round + 1, finals.size)
                            }
                            roundsCompleted.complete(Unit)
                            while (true) sendFrame(ByteArray(FRAME_BYTES))
                        }

                        // Independent translation consumer worker prevents translation wait from blocking recognition producer
                        val translationWorker = launch {
                            for (utterance in translationQueue) {
                                val started = SystemClock.elapsedRealtime()
                                val translated = translator.translate(
                                    text = utterance.text,
                                    sourceLanguageTag = utterance.sourceLanguageTag,
                                    targetLanguageTag = "en",
                                )
                                assertTrue("Translated text must not be blank", translated.isNotBlank())
                                // Record the bundled public fixture before the semantic assertion so
                                // a failure still identifies whether STT or translation changed its meaning.
                                Log.i("GemmaSpeechGate", "model=${variant.id} delayMs=${captureDelayNanos / 1_000_000} " +
                                    "sequence=${utterance.sequence} elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                                    "source=${utterance.text} translation=$translated")
                                assertTrue(
                                    "Public safety fixture must retain compliance with signs: $translated",
                                    Regex(
                                        """\b(?:follow|obey|observe|heed|respect|comply with|adhere to|abide by)\s+(?:(?:all|the|posted|safety)\s+)*sign(?:s|age|posts)\b""",
                                    ).containsMatchIn(translated.lowercase(Locale.ROOT)),
                                )
                                translations += translated
                            }
                        }

                        val recognition = launch {
                            engine.recognize(input, SpeechRecognitionConfig("ko-KR", SAMPLE_RATE_HZ, 1)).collect { utterance ->
                                if (utterance.isFinal) {
                                    assertTrue(
                                        "Semantic final must contain required meaning words: ${utterance.text}",
                                        REQUIRED_MEANING_WORDS.all { it in utterance.text },
                                    )
                                    finals += utterance
                                    translationQueue.send(utterance)
                                }
                            }
                        }

                        try {
                            roundsCompleted.await()
                            assertEquals(rounds, finals.size)
                            assertEquals(rounds, finals.map { it.sequence }.toSet().size)
                            assertTrue(finals.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
                            // Wait for all queued translations to finish
                            withTimeout(60_000L) {
                                while (translations.size < rounds) delay(100L)
                            }
                            assertEquals(rounds, translations.size)
                            assertTrue("Input must remain open after the last translation", recognition.isActive)
                        } finally {
                            recognition.cancelAndJoin()
                            translationQueue.close()
                            translationWorker.cancelAndJoin()
                        }
                    }
                }
            }
        } finally {
            // Always safely restore initial variant
            try {
                if (manager.selectedVariant != initialVariant) {
                    provider.applyVerifiedModel(initialVariant)
                }
                assertEquals(initialVariant, manager.appliedVariant)
            } finally {
                backendLease.close()
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
