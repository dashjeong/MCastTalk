package app.guidecast.core.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectiveRefinementTranslationEngineProviderTest {
    @Test
    fun ordinarySentenceUsesOneDraftAndDoesNotCreateReviewer() = runBlocking {
        var draftCalls = 0
        var reviewerCreations = 0
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val provider = provider(
            draft = { draftCalls += 1; "ordinary draft" },
            reviewer = { reviewerCreations += 1; "unused" },
            onDiagnostic = diagnostics::add,
        )

        val result = provider.engineFor("en").translate("안내를 시작합니다", "ko", "en")

        assertEquals("ordinary draft", result)
        assertEquals(1, draftCalls)
        assertEquals(0, reviewerCreations)
        assertEquals(SelectiveRefinementOutcome.DRAFT_ACCEPTED, diagnostics.single().outcome)
    }

    @Test
    fun glossaryAndNumbersRequestReviewWithIsolatedImmutableContext() = runBlocking {
        var received: TranslationReviewContext? = null
        val fairReviewer = FairQueuedTranslationEngineProvider(
            delegate = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ ->
                received = currentCoroutineContext()[TranslationReviewContext]
                "Gate 3 opens at 15:30 exactly"
                }
            },
            parentScope = this,
            config = FairTranslationQueueConfig(
                queueWaitTimeoutMillis = 1_000,
                inferenceTimeoutMillis = 1_000,
            ),
        )
        val provider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> "Gate 3 opens at 15:30" }
            },
            reviewerProvider = fairReviewer,
        )

        val result = try {
            withContext(TranslationGlossaryContext("제3땅굴 => Third Tunnel")) {
                provider.engineFor("en").translate("제3땅굴은 15:30에 엽니다", "ko", "en")
            }
        } finally {
            fairReviewer.close()
        }

        assertEquals("Gate 3 opens at 15:30 exactly", result)
        val context = assertNotNull(received).let { requireNotNull(received) }
        assertEquals("제3땅굴은 15:30에 엽니다", context.originalText)
        assertEquals("Gate 3 opens at 15:30", context.draftTranslation)
        assertEquals(setOf(SelectiveRefinementReason.GLOSSARY_MATCH,
            SelectiveRefinementReason.NUMERIC_CONTENT), context.reasons)
        assertNull(currentCoroutineContext()[TranslationReviewContext])
    }

    @Test
    fun reviewerFailureAndNumericCorruptionReturnExactDraftWithoutSecondDraft() = runBlocking {
        var draftCalls = 0
        val failed = provider(
            draft = { draftCalls += 1; "Bus 12 leaves at 9:05" },
            reviewer = { error("review unavailable") },
        )
        assertEquals("Bus 12 leaves at 9:05",
            failed.engineFor("en").translate("12번 버스는 9:05 출발합니다", "ko", "en"))

        val corrupt = provider(
            draft = { "Bus 12 leaves at 9:05" },
            reviewer = { "Bus 21 leaves at 9:50" },
        )
        assertEquals("Bus 12 leaves at 9:05",
            corrupt.engineFor("en").translate("12번 버스는 9:05 출발합니다", "ko", "en"))
        assertEquals(1, draftCalls)
    }

    @Test
    fun signedDecimalPercentageCorruptionAndOversizedDraftAreRejected() = runBlocking {
        val draft = "경사 -1.5%, 완료 50%"
        listOf("경사 1.5%, 완료 50%", "경사 -1/5%, 완료 50%", "경사 -1.5%, 완료 50").forEach { corrupt ->
            val provider = provider(draft = { draft }, reviewer = { corrupt })
            assertEquals(draft,
                provider.engineFor("ko").translate("Slope -1.5%, completion 50%", "en", "ko"))
        }

        var reviewerCalls = 0
        val oversizedDraft = "7" + "가".repeat(TranslationReviewContext.MAX_DRAFT_CHARACTERS)
        val oversized = provider(
            draft = { oversizedDraft },
            reviewer = { reviewerCalls += 1; "unused" },
        )
        assertEquals(oversizedDraft,
            oversized.engineFor("en").translate("7번 안내", "ko", "en"))
        assertEquals(0, reviewerCalls)
    }

    @Test
    fun unavailableReviewerKeepsDraftAndTargetMismatchFailsBeforeDraft() = runBlocking {
        var draftCalls = 0
        var reviewerCalls = 0
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val provider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> draftCalls += 1; "Draft 7" }
            },
            reviewerProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> reviewerCalls += 1; "Reviewed 7" }
            },
            reviewerAvailable = { false },
            onDiagnostic = diagnostics::add,
        )
        val engine = provider.engineFor("en")
        assertEquals("Draft 7", engine.translate("7번 안내", "ko", "en"))
        assertEquals(SelectiveRefinementOutcome.REVIEW_UNAVAILABLE, diagnostics.single().outcome)
        assertEquals(0, reviewerCalls)

        try {
            engine.translate("7번 안내", "ko", "ja")
            throw AssertionError("A cross-target call must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertEquals(1, draftCalls)
    }

    @Test(expected = AssertionError::class)
    fun fatalReviewerErrorIsNotHiddenByDraftFallback() {
        runBlocking {
            provider(
                draft = { "Draft 7" },
                reviewer = { throw AssertionError("fatal reviewer invariant") },
            ).engineFor("en").translate("7번 안내", "ko", "en")
        }
    }

    @Test
    fun reviewerDeadlineReturnsDraftAndCallerCancellationPropagates() = runBlocking {
        val timedOut = provider(
            draft = { "Draft 7" },
            reviewer = { delay(Long.MAX_VALUE); "unreachable" },
            reviewTimeoutMillis = 100,
        )
        assertEquals("Draft 7", timedOut.engineFor("en").translate("7번입니다", "ko", "en"))

        val entered = CompletableDeferred<Unit>()
        val cancelled = provider(
            draft = { "Draft 7" },
            reviewer = { entered.complete(Unit); delay(Long.MAX_VALUE); "unreachable" },
            reviewTimeoutMillis = 5_000,
        )
        val job = async { cancelled.engineFor("en").translate("7번입니다", "ko", "en") }
        entered.await()
        job.cancel()
        try {
            job.await()
            throw AssertionError("Caller cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun longCompleteSentenceIsTheOnlyLengthBasedReviewTrigger() {
        val policy = DefaultSelectiveRefinementPolicy(longSentenceCodePoints = 40)
        val incomplete = policy.decide("가".repeat(80), "")
        val complete = policy.decide("가".repeat(80) + ".", "")

        assertFalse(incomplete.reviewRequested)
        assertTrue(complete.reviewRequested)
        assertEquals(setOf(SelectiveRefinementReason.LONG_COMPLETE_SENTENCE), complete.reasons)
    }

    private fun provider(
        draft: suspend () -> String,
        reviewer: suspend () -> String,
        reviewTimeoutMillis: Long = 800,
        onDiagnostic: (SelectiveRefinementDiagnostic) -> Unit = {},
    ): SelectiveRefinementTranslationEngineProvider =
        SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> draft() }
            },
            reviewerProvider = TranslationEngineProvider {
                TextTranslationEngine { _, _, _ -> reviewer() }
            },
            reviewTimeoutMillis = reviewTimeoutMillis,
            onDiagnostic = onDiagnostic,
        )
}
