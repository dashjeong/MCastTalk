package app.guidecast.core.translation

import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionMemoryPipelineTest {

    private fun fixtureSpeech() = object : SpeechSynthesisEngine {
        override fun synthesize(text: String, languageTag: String) =
            flowOf(PcmAudioFrame(ByteArray(640) { 1 }, 1L))
    }

    @Test
    fun `pipeline injects session memory context into translation engine and records successful final pairs`() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val targets = listOf(TranslationTarget("en", "English", "en", 24_000))

        val observedContexts = mutableListOf<TranslationSessionMemoryContext>()
        val engine = TextTranslationEngine { text, _, _ ->
            val memoryContext = currentCoroutineContext()[TranslationSessionMemoryContext]
            if (memoryContext != null) {
                observedContexts.add(memoryContext)
            }
            "Trans: $text"
        }

        val speechEngine = fixtureSpeech()

        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = { engine },
            speechEngines = { speechEngine },
        ).start(this, source, targets)
        runCurrent()

        // Utterance 1
        source.emit(RecognizedUtterance(1, "첫 문장", "ko", true, 1L))
        advanceUntilIdle()

        assertEquals(1, observedContexts.size)
        // First utterance should see empty session memory
        assertEquals("", observedContexts[0].memory)
        assertTrue(observedContexts[0].pairs.isEmpty())

        // Utterance 2
        source.emit(RecognizedUtterance(2, "두 번째 문장", "ko", true, 2L))
        advanceUntilIdle()

        assertEquals(2, observedContexts.size)
        // Second utterance should see the first completed pair
        val secondCallMemory = observedContexts[1]
        assertEquals(1, secondCallMemory.pairs.size)
        assertEquals("첫 문장", secondCallMemory.pairs[0].sourceText)
        assertEquals("Trans: 첫 문장", secondCallMemory.pairs[0].translatedText)
        assertTrue(secondCallMemory.memory.contains("\"source\":\"첫 문장\""))
        assertTrue(secondCallMemory.memory.contains("\"translation\":\"Trans: 첫 문장\""))

        running.close()
    }

    @Test
    fun `failed translation is not recorded into session memory`() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val targets = listOf(TranslationTarget("en", "English", "en", 24_000))

        var shouldFail = false
        val observedContexts = mutableListOf<TranslationSessionMemoryContext>()
        val engine = TextTranslationEngine { text, _, _ ->
            currentCoroutineContext()[TranslationSessionMemoryContext]?.let { observedContexts.add(it) }
            if (shouldFail) {
                throw IllegalStateException("Translation provider down")
            }
            "OK: $text"
        }

        val speechEngine = fixtureSpeech()

        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = { engine },
            speechEngines = { speechEngine },
        ).start(this, source, targets)
        runCurrent()

        // Utterance 1 succeeds
        source.emit(RecognizedUtterance(1, "문장 1", "ko", true, 1L))
        advanceUntilIdle()

        // Utterance 2 fails
        shouldFail = true
        source.emit(RecognizedUtterance(2, "실패할 문장 2", "ko", true, 2L))
        advanceUntilIdle()

        // Utterance 3 succeeds
        shouldFail = false
        source.emit(RecognizedUtterance(3, "문장 3", "ko", true, 3L))
        advanceUntilIdle()

        assertEquals(3, observedContexts.size)
        // Utterance 3 should only see "문장 1", NOT "실패할 문장 2"
        val memoryForThird = observedContexts[2]
        assertEquals(1, memoryForThird.pairs.size)
        assertEquals("문장 1", memoryForThird.pairs[0].sourceText)

        running.close()
    }

    @Test
    fun `non-final interim utterances are not recorded into session memory`() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val targets = listOf(TranslationTarget("en", "English", "en", 24_000))

        val observedContexts = mutableListOf<TranslationSessionMemoryContext>()
        val engine = TextTranslationEngine { text, _, _ ->
            currentCoroutineContext()[TranslationSessionMemoryContext]?.let { observedContexts.add(it) }
            "Result: $text"
        }

        val speechEngine = fixtureSpeech()

        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = { engine },
            speechEngines = { speechEngine },
        ).start(this, source, targets)
        runCurrent()

        // Interim non-final utterance (isFinal = false)
        source.emit(RecognizedUtterance(1, "미확정 문장", "ko", false, 1L))
        advanceUntilIdle()

        // Final utterance
        source.emit(RecognizedUtterance(2, "확정 문장", "ko", true, 2L))
        advanceUntilIdle()

        // Second utterance must see empty memory because first was non-final
        assertEquals(1, observedContexts.size)
        assertEquals("", observedContexts[0].memory)
        assertTrue(observedContexts[0].pairs.isEmpty())

        running.close()
    }

    @Test
    fun `pipeline close clears session memory references`() = runTest {
        val streams = AudioStreamRegistry()
        val source = MutableSharedFlow<RecognizedUtterance>()
        val targets = listOf(TranslationTarget("en", "English", "en", 24_000))

        val memory = BroadcastSessionBilingualMemory()
        val engine = TextTranslationEngine { text, _, _ -> "OK: $text" }
        val speechEngine = fixtureSpeech()

        val running = TranslationBroadcastPipeline(
            streams = streams,
            translationEngines = { engine },
            speechEngines = { speechEngine },
        ).start(this, source, targets, sessionMemory = memory)
        runCurrent()

        source.emit(RecognizedUtterance(1, "문장 1", "ko", true, 1L))
        advanceUntilIdle()

        assertEquals(1, memory.buildContext("ko", "en").pairs.size)

        // Close pipeline
        running.close()

        // Memory must be cleared
        assertEquals("", memory.buildContext("ko", "en").memory)
        assertTrue(memory.buildContext("ko", "en").pairs.isEmpty())
    }

    @Test
    fun `independent pipeline runs have isolated session memories`() = runTest {
        val streams1 = AudioStreamRegistry()
        val streams2 = AudioStreamRegistry()
        val source1 = MutableSharedFlow<RecognizedUtterance>()
        val source2 = MutableSharedFlow<RecognizedUtterance>()
        val targets = listOf(TranslationTarget("en", "English", "en", 24_000))

        val engine = TextTranslationEngine { text, _, _ -> "OK: $text" }
        val speechEngine = fixtureSpeech()

        val pipeline1 = TranslationBroadcastPipeline(
            streams = streams1,
            translationEngines = { engine },
            speechEngines = { speechEngine },
        ).start(this, source1, targets)

        val pipeline2 = TranslationBroadcastPipeline(
            streams = streams2,
            translationEngines = { engine },
            speechEngines = { speechEngine },
        ).start(this, source2, targets)
        runCurrent()

        source1.emit(RecognizedUtterance(1, "세션 1 문장", "ko", true, 1L))
        advanceUntilIdle()

        val memory1 = requireNotNull(pipeline1.sessionMemory)
        val memory2 = requireNotNull(pipeline2.sessionMemory)

        assertEquals(1, memory1.buildContext("ko", "en").pairs.size)
        // Pipeline 2 memory has zero pairs from pipeline 1
        assertEquals(0, memory2.buildContext("ko", "en").pairs.size)

        pipeline1.close()
        pipeline2.close()
    }
}
