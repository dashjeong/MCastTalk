package app.guidecast.core.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedSelectiveRefinementTest {
    @Test fun fiveQueuedLanguagesFinishReviewWithTheirOwnDraftAndContext() = runTest {
        val observed = mutableListOf<Pair<String, String?>>()
        val calls = mutableListOf<String>()
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        var drafts = 0
        val config = FairTranslationQueueConfig(maxPendingPerLanguage = 2,
            queueWaitTimeoutMillis = 30_000L, inferenceTimeoutMillis = 15_000L)
        val fair = queue(config) { target, context ->
            calls += target
            observed += target to context
            val review = requireNotNull(currentCoroutineContext()[TranslationReviewContext])
            assertEquals(ORIGINAL, review.originalText)
            assertEquals("$target $DRAFT", review.draftTranslation)
            assertEquals(target, review.targetLanguageTag)
            delay(2_000L)
            "$target $REVIEWED"
        }
        try {
            val provider = SelectiveRefinementTranslationEngineProvider(
                draftProvider = TranslationEngineProvider { target -> TextTranslationEngine { _, _, _ -> drafts++; "$target $DRAFT" } },
                reviewerProvider = fair, queuedReviewTimeoutMillis = config.maximumCallDurationMillis,
                onDiagnostic = diagnostics::add,
            )
            val languages = listOf("en", "ja", "zh", "fr", "de")
            val work = languages.map { target -> async {
                (provider.engineFor(target) as ContextualTextTranslationEngine)
                    .translateWithContext(ORIGINAL, "Previous complete sentence for $target.", "ko", target)
            } }
            advanceTimeBy(801L)
            runCurrent()
            assertTrue(work.none { it.isCompleted })
            advanceUntilIdle()
            assertEquals(languages.map { "$it $REVIEWED" }, work.awaitAll())
            assertEquals(5, drafts)
            assertEquals(5, calls.size)
            assertEquals(languages.toSet(), calls.toSet())
            assertTrue(observed.all { (target, context) -> context == "Previous complete sentence for $target." })
            assertEquals(List(5) { SelectiveRefinementOutcome.REVIEW_ACCEPTED }, diagnostics.map { it.outcome })
            assertEquals(10_000L, testScheduler.currentTime)
            assertEquals(48_100L, (provider.engineFor("en") as BoundedQueuedTranslationEngine).maximumCallDurationMillis)
        } finally { fair.close() }
    }

    @Test fun omittingQueueBudgetRetainsTheLegacyEightHundredMillisecondFallback() = runTest {
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val fair = queue { _, _ -> delay(2_000L); REVIEWED }
        try {
            val provider = selective(fair, diagnostics = diagnostics)
            assertEquals(DRAFT, provider.engineFor("en").translate(ORIGINAL, "ko", "en"))
            assertEquals(800L, testScheduler.currentTime)
            assertEquals(SelectiveRefinementOutcome.REVIEW_TIMED_OUT, diagnostics.single().outcome)
        } finally { fair.close() }
    }

    @Test fun qualityCeilingDoesNotExtendAnUnboundedReviewersDeadline() = runTest {
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val provider = selective(TranslationEngineProvider {
            TextTranslationEngine { _, _, _ -> delay(2_000L); REVIEWED }
        }, ceiling = 45_000L, diagnostics = diagnostics)
        assertEquals(DRAFT, provider.engineFor("en").translate(ORIGINAL, "ko", "en"))
        assertEquals(800L, testScheduler.currentTime)
        assertEquals(SelectiveRefinementOutcome.REVIEW_TIMED_OUT, diagnostics.single().outcome)
    }

    @Test fun queueAdmissionTimeoutPreservesItsDraftAndCallerCancellationDoesNotPublishAnother() = runTest {
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val entered = mutableListOf<String>()
        val config = FairTranslationQueueConfig(queueWaitTimeoutMillis = 500L, inferenceTimeoutMillis = 3_000L)
        val fair = queue(config) { target, _ ->
            entered += target
            if (target == "en") awaitCancellation()
            REVIEWED
        }
        try {
            val provider = selective(fair, config.maximumCallDurationMillis, diagnostics)
            val active = async { provider.engineFor("en").translate(ORIGINAL, "ko", "en") }
            runCurrent()
            val waiting = async { provider.engineFor("ja").translate(ORIGINAL, "ko", "ja") }
            runCurrent()
            advanceTimeBy(501L)
            runCurrent()
            assertEquals(DRAFT, waiting.await())
            assertFalse(active.isCompleted)
            assertEquals(listOf("en"), entered)
            assertEquals(SelectiveRefinementOutcome.REVIEW_TIMED_OUT, diagnostics.single().outcome)
            active.cancel()
            try { active.await(); throw AssertionError("Cancellation must propagate") }
            catch (_: CancellationException) { }
            runCurrent()
            assertEquals(REVIEWED, provider.engineFor("fr").translate(ORIGINAL, "ko", "fr"))
            assertEquals(listOf("en", "fr"), entered)
            assertEquals(2, diagnostics.size)
        } finally { fair.close() }
    }

    @Test fun theQueuesInferenceDeadlineStillAppliesInsideTheLongerOuterBudget() = runTest {
        val config = FairTranslationQueueConfig(queueWaitTimeoutMillis = 2_000L, inferenceTimeoutMillis = 500L)
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val fair = queue(config) { _, _ -> awaitCancellation() }
        try {
            assertEquals(DRAFT, selective(fair, config.maximumCallDurationMillis, diagnostics)
                .engineFor("en").translate(ORIGINAL, "ko", "en"))
            assertEquals(500L, testScheduler.currentTime)
            assertEquals(SelectiveRefinementOutcome.REVIEW_TIMED_OUT, diagnostics.single().outcome)
        } finally { fair.close() }
    }

    @Test fun invalidOrExcessiveProviderBudgetCannotBypassTheCeiling() = runTest {
        val draft = TranslationEngineProvider { TextTranslationEngine { _, _, _ -> DRAFT } }
        for (invalid in listOf(-1L, 0L, 60_001L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                SelectiveRefinementTranslationEngineProvider(draft, draft, queuedReviewTimeoutMillis = invalid)
            }
        }
        for (advertised in listOf(-1L, 0L, 5_001L, Long.MAX_VALUE)) {
            var calls = 0
            val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
            val reviewer = TranslationEngineProvider { object : BoundedQueuedTranslationEngine {
                override val maximumCallDurationMillis = advertised
                override suspend fun translateWithContext(text: String, contextBefore: String?,
                    sourceLanguageTag: String, targetLanguageTag: String): String { calls++; return REVIEWED }
            } }
            assertEquals(DRAFT, selective(reviewer, 5_000L, diagnostics).engineFor("en").translate(ORIGINAL, "ko", "en"))
            assertEquals(0, calls)
            assertEquals(SelectiveRefinementOutcome.REVIEW_FAILED, diagnostics.single().outcome)
        }
    }

    private fun selective(reviewer: TranslationEngineProvider, ceiling: Long? = null,
        diagnostics: MutableList<SelectiveRefinementDiagnostic>): SelectiveRefinementTranslationEngineProvider =
        SelectiveRefinementTranslationEngineProvider(
            TranslationEngineProvider { TextTranslationEngine { _, _, _ -> DRAFT } }, reviewer,
            queuedReviewTimeoutMillis = ceiling, onDiagnostic = diagnostics::add)

    private fun TestScope.queue(
        config: FairTranslationQueueConfig = FairTranslationQueueConfig(),
        block: suspend (String, String?) -> String,
    ) = FairQueuedTranslationEngineProvider(TranslationEngineProvider {
        object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(text: String, contextBefore: String?,
                sourceLanguageTag: String, targetLanguageTag: String) = block(targetLanguageTag, contextBefore)
        }
    }, this, config, nanoTime = { testScheduler.currentTime * 1_000_000L })

    private companion object {
        const val ORIGINAL = "3번 출구로 갑니다."
        const val DRAFT = "Go to gate 3."
        const val REVIEWED = "Please use exit 3."
    }
}
