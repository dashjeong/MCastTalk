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
    fun spokenIntegerQuantityTriggersReviewAndCanRepairWrongDraftNumber() = runBlocking {
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val repaired = provider(draft = { "The contract lasts 8 months." },
            reviewer = { "The contract lasts 18 months." }, onDiagnostic = diagnostics::add)
        assertEquals("The contract lasts 18 months.",
            repaired.engineFor("en").translate("계약은 십팔 개월입니다.", "ko", "en"))
        assertEquals(setOf(SelectiveRefinementReason.NUMERIC_CONTENT), diagnostics.single().reasons)
        assertEquals(SelectiveRefinementOutcome.REVIEW_ACCEPTED, diagnostics.single().outcome)
        assertFalse(reviewResultIsConservative("계약은 십팔 개월입니다.", "The contract lasts 8 months.",
            "The contract lasts 80 months."))
        assertFalse(DefaultSelectiveRefinementPolicy().decide("이 분은 천명을 따릅니다.", "").reviewRequested)
    }

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
    fun explicitAutoStyleRequestsMeaningReviewAndRetainsNumericReasons() = runBlocking {
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        var reviewerCalls = 0
        var draft = "We meet here."
        val provider = provider(draft = { draft }, reviewer = {
            reviewerCalls++
            assertEquals(TranslationStyle.AUTO, currentCoroutineContext()[TranslationStyleContext]?.style)
            if (requireNotNull(currentCoroutineContext()[TranslationReviewContext]).originalText == "여기서 만납니다.") {
                "Let us meet here."
            } else "We will meet at gate 3."
        }, onDiagnostic = diagnostics::add)
        withContext(TranslationStyleContext(TranslationStyle.AUTO)) {
            assertEquals("Let us meet here.", provider.engineFor("en").translate("여기서 만납니다.", "ko", "en"))
            assertEquals(1, reviewerCalls)
            draft = "We meet at gate 3."
            assertEquals("We will meet at gate 3.", provider.engineFor("en").translate("3번 출구에서 만납니다.", "ko", "en"))
        }
        assertEquals(2, reviewerCalls)
        assertEquals(SelectiveRefinementOutcome.REVIEW_ACCEPTED, diagnostics.first().outcome)
        assertEquals(setOf(SelectiveRefinementReason.STYLE_REQUESTED), diagnostics.first().reasons)
        assertEquals(setOf(SelectiveRefinementReason.NUMERIC_CONTENT, SelectiveRefinementReason.STYLE_REQUESTED),
            diagnostics.last().reasons)
        assertEquals(SelectiveRefinementOutcome.REVIEW_ACCEPTED, diagnostics.last().outcome)
    }

    @Test
    fun confidentShortSourceCueReviewsWithoutAStyleOverrideOrRewritingSourceOrPriorContext() = runBlocking {
        val original = "여러 분께서 복도에 서 계십니다."
        val before = "안내원이 회의실 문을 열었습니다."
        val draft = "They have been standing in the hall for several minutes."
        val reviewed = "Several people are standing in the hallway."
        var calls = 0
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val provider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider { TextTranslationEngine { _, _, _ -> draft } },
            reviewerProvider = TranslationEngineProvider { object : ContextualTextTranslationEngine {
                override suspend fun translateWithContext(text: String, contextBefore: String?,
                    sourceLanguageTag: String, targetLanguageTag: String): String {
                    calls++
                    assertEquals(original, text)
                    assertEquals(before, contextBefore)
                    assertEquals("ko-KR", sourceLanguageTag)
                    val review = requireNotNull(currentCoroutineContext()[TranslationReviewContext])
                    assertEquals(original, review.originalText)
                    assertEquals(draft, review.draftTranslation)
                    assertEquals(setOf(SelectiveRefinementReason.SOURCE_AMBIGUITY), review.reasons)
                    assertNull(currentCoroutineContext()[TranslationStyleContext])
                    return reviewed
                }
            } },
            onDiagnostic = diagnostics::add,
        )
        assertEquals(reviewed,
            (provider.engineFor("en") as ContextualTextTranslationEngine)
                .translateWithContext(original, before, "ko-KR", "en")
        )
        assertEquals(1, calls)
        assertEquals(SelectiveRefinementOutcome.REVIEW_ACCEPTED, diagnostics.single().outcome)
    }

    @Test
    fun uncertainGrammarAndPriorTopicAloneDoNotRequestReviewWithoutAStyleOverride() = runBlocking {
        var calls = 0
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val provider = provider(draft = { "Unchanged draft." }, reviewer = { calls++; "Unexpected review." },
            onDiagnostic = diagnostics::add)
        val engine = provider.engineFor("en") as ContextualTextTranslationEngine
        listOf(
            "몇 분이 지나자 선생님께서 계십니다.",
            "몇 분이 계시록을 읽고 있습니다.",
            "선생님께서 몇 분 동안 문 앞에 서 계셨습니다.",
            "오늘은 맑습니다.",
        ).forEach { text ->
            assertEquals("Unchanged draft.", engine.translateWithContext(text, "몇 분이 서 계십니다.", "ko", "en"))
        }
        assertEquals("Unchanged draft.",
            engine.translateWithContext("여러 분께서 복도에 서 계십니다.", null, "ja", "en"))
        assertEquals(0, calls)
        assertEquals(5, diagnostics.size)
        assertTrue(diagnostics.all { it.outcome == SelectiveRefinementOutcome.DRAFT_ACCEPTED && it.reasons.isEmpty() })
        // Numeric selection may still apply to mixed time quantities, but never the grammar cue.
        val mixed = DefaultSelectiveRefinementPolicy().decide("몇 분이 서 계셨고 삼십 분이 남아 있었습니다.", "", "ko")
        assertFalse(SelectiveRefinementReason.SOURCE_AMBIGUITY in mixed.reasons)
    }

    @Test
    fun existingCustomSamPolicyRemainsAuthoritativeWhenLanguageIsSupplied() = runBlocking {
        val original = "여러 분께서 복도에 서 계십니다."
        val observed = mutableListOf<Pair<String, String>>()
        var calls = 0
        val provider = SelectiveRefinementTranslationEngineProvider(
            TranslationEngineProvider { TextTranslationEngine { _, _, _ -> "User policy draft." } },
            TranslationEngineProvider { TextTranslationEngine { _, _, _ -> calls++; "Unexpected review." } },
            policy = SelectiveRefinementPolicy { text, hints ->
                observed += text to hints
                SelectiveRefinementDecision(emptySet())
            },
        )
        assertEquals("User policy draft.", provider.engineFor("en").translate(original, "ko", "en"))
        assertEquals(listOf(original to ""), observed)
        assertEquals(0, calls)
    }

    @Test
    fun explicitStyleReviewsShortSentencesAndDoesNotLeakIntoLaterOrdinarySentences() = runBlocking {
        val styles = mutableListOf<TranslationStyle>()
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        var draftCalls = 0
        val provider = provider(
            draft = { draftCalls++; "We start now." },
            reviewer = {
                val style = requireNotNull(currentCoroutineContext()[TranslationStyleContext]).style
                val review = requireNotNull(currentCoroutineContext()[TranslationReviewContext])
                assertEquals("지금 시작합니다.", review.originalText)
                assertEquals("We start now.", review.draftTranslation)
                assertEquals(setOf(SelectiveRefinementReason.STYLE_REQUESTED), review.reasons)
                styles += style
                if (style == TranslationStyle.FORMAL) "We will begin now." else "Let's get started."
            },
            onDiagnostic = diagnostics::add,
        )
        val engine = provider.engineFor("en")
        assertEquals("We will begin now.", withContext(TranslationStyleContext(TranslationStyle.FORMAL)) {
            engine.translate("지금 시작합니다.", "ko", "en")
        })
        assertEquals("Let's get started.", withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL)) {
            engine.translate("지금 시작합니다.", "ko", "en")
        })
        assertEquals("We start now.", engine.translate("지금 시작합니다.", "ko", "en"))
        assertEquals(listOf(TranslationStyle.FORMAL, TranslationStyle.CONVERSATIONAL), styles)
        assertEquals(3, draftCalls)
        assertEquals(listOf(SelectiveRefinementOutcome.REVIEW_ACCEPTED, SelectiveRefinementOutcome.REVIEW_ACCEPTED,
            SelectiveRefinementOutcome.DRAFT_ACCEPTED), diagnostics.map { it.outcome })
        assertNull(currentCoroutineContext()[TranslationStyleContext])
        assertNull(currentCoroutineContext()[TranslationReviewContext])
    }

    @Test
    fun explicitStyleCannotBypassReviewerAvailabilityOrNumericQualityGuard() = runBlocking {
        var reviewerCreations = 0
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        val unavailable = SelectiveRefinementTranslationEngineProvider(
            draftProvider = TranslationEngineProvider { TextTranslationEngine { _, _, _ -> "We start now." } },
            reviewerProvider = TranslationEngineProvider {
                reviewerCreations++
                error("Unavailable reviewer must not be created")
            },
            reviewerAvailable = { false },
            onDiagnostic = diagnostics::add,
        )
        withContext(TranslationStyleContext(TranslationStyle.CONVERSATIONAL)) {
            assertEquals("We start now.", unavailable.engineFor("en").translate("지금 시작합니다.", "ko", "en"))
            val corrupt = provider(draft = { "Bus 12 leaves now." }, reviewer = { "Bus 21 leaves now." },
                onDiagnostic = diagnostics::add)
            assertEquals("Bus 12 leaves now.", corrupt.engineFor("en").translate("12번 버스가 출발합니다.", "ko", "en"))
        }
        assertEquals(0, reviewerCreations)
        assertEquals(SelectiveRefinementOutcome.REVIEW_UNAVAILABLE, diagnostics[0].outcome)
        assertEquals(setOf(SelectiveRefinementReason.STYLE_REQUESTED), diagnostics[0].reasons)
        assertEquals(SelectiveRefinementOutcome.REVIEW_REJECTED, diagnostics[1].outcome)
        assertEquals(setOf(SelectiveRefinementReason.NUMERIC_CONTENT, SelectiveRefinementReason.STYLE_REQUESTED),
            diagnostics[1].reasons)
    }

    @Test
    fun explicitStyleKeepsTheExistingReviewDeadlineForShortSentences() = runBlocking {
        val diagnostics = mutableListOf<SelectiveRefinementDiagnostic>()
        var reviewerCalls = 0
        val provider = provider(
            draft = { "We start now." },
            reviewer = { reviewerCalls++; delay(Long.MAX_VALUE); "unreachable" },
            reviewTimeoutMillis = 100L,
            onDiagnostic = diagnostics::add,
        )
        assertEquals("We start now.", withContext(TranslationStyleContext(TranslationStyle.FORMAL)) {
            provider.engineFor("en").translate("지금 시작합니다.", "ko", "en")
        })
        assertEquals(1, reviewerCalls)
        assertEquals(SelectiveRefinementOutcome.REVIEW_TIMED_OUT, diagnostics.single().outcome)
        assertEquals(setOf(SelectiveRefinementReason.STYLE_REQUESTED), diagnostics.single().reasons)
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
