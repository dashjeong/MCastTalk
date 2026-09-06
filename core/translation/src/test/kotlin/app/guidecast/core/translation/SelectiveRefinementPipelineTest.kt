package app.guidecast.core.translation

import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectiveRefinementPipelineTest {
    @Test
    fun `successful review publishes and speaks only the reviewed translation`() = runTest {
        var observedContext: TranslationReviewContext? = null
        val result = runScenario(
            reviewer = {
                observedContext = currentCoroutineContext()[TranslationReviewContext]
                REVIEWED
            },
        )

        assertEquals(1, result.draftCalls)
        assertEquals(1, result.reviewCalls)
        assertEquals(listOf(REVIEWED), result.transcripts)
        assertEquals(listOf(REVIEWED), result.synthesizedTexts)
        assertNotNull(observedContext)
        assertEquals(ORIGINAL, observedContext!!.originalText)
        assertEquals(DRAFT, observedContext!!.draftTranslation)
        assertEquals("ko", observedContext!!.sourceLanguageTag)
        assertEquals("en", observedContext!!.targetLanguageTag)
    }

    @Test
    fun `review failure publishes and speaks the exact draft only once`() = runTest {
        val result = runScenario(reviewer = { error("review failed") })

        assertEquals(1, result.draftCalls)
        assertEquals(1, result.reviewCalls)
        assertEquals(listOf(DRAFT), result.transcripts)
        assertEquals(listOf(DRAFT), result.synthesizedTexts)
    }

    @Test
    fun `review deadline publishes and speaks the exact draft only once`() = runTest {
        val result = runScenario(
            reviewTimeoutMillis = 100L,
            reviewer = {
                delay(1_000L)
                REVIEWED
            },
        )

        assertEquals(1, result.draftCalls)
        assertEquals(1, result.reviewCalls)
        assertEquals(listOf(DRAFT), result.transcripts)
        assertEquals(listOf(DRAFT), result.synthesizedTexts)
    }

    private suspend fun TestScope.runScenario(
        reviewTimeoutMillis: Long = 800L,
        reviewer: suspend () -> String,
    ): ScenarioResult {
        var draftCalls = 0
        var reviewCalls = 0
        val transcripts = mutableListOf<String>()
        val synthesizedTexts = mutableListOf<String>()
        val utterances = MutableSharedFlow<RecognizedUtterance>(extraBufferCapacity = 1)
        val translationProvider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ ->
                    draftCalls += 1
                    DRAFT
                }
            },
            reviewerProvider = TranslationEngineProvider {
                object : ContextualTextTranslationEngine {
                    override suspend fun translateWithContext(
                        text: String,
                        contextBefore: String?,
                        sourceLanguageTag: String,
                        targetLanguageTag: String,
                    ): String {
                        reviewCalls += 1
                        return reviewer()
                    }
                }
            },
            reviewTimeoutMillis = reviewTimeoutMillis,
        )
        val running = TranslationBroadcastPipeline(
            streams = AudioStreamRegistry(),
            translationEngines = translationProvider,
            speechEngines = SpeechSynthesisEngineProvider {
                object : SpeechSynthesisEngine {
                    override fun synthesize(text: String, languageTag: String) = flow {
                        synthesizedTexts += text
                        emit(PcmAudioFrame(byteArrayOf(0, 64, 0, 64), 1L))
                    }
                }
            },
            observer = object : TranslationPipelineObserver {
                override fun onTranslationCompleted(
                    utterance: RecognizedUtterance,
                    target: TranslationTarget,
                    translatedText: String,
                    elapsedMillis: Long,
                ) {
                    transcripts += translatedText
                }
            },
        ).start(
            scope = this,
            utterances = utterances,
            targets = listOf(TranslationTarget("en", "English", "en", 16_000)),
            sourceLanguageTag = "ko",
        )
        runCurrent()

        utterances.emit(RecognizedUtterance(1L, ORIGINAL, "ko", true, 1L))
        advanceUntilIdle()
        running.close()

        return ScenarioResult(draftCalls, reviewCalls, transcripts, synthesizedTexts)
    }

    private data class ScenarioResult(
        val draftCalls: Int,
        val reviewCalls: Int,
        val transcripts: List<String>,
        val synthesizedTexts: List<String>,
    )

    private companion object {
        const val ORIGINAL = "3번 출구는 15:30에 엽니다."
        const val DRAFT = "Gate 3 opens at 15:30."
        const val REVIEWED = "Exit 3 opens at 15:30."
    }
}
