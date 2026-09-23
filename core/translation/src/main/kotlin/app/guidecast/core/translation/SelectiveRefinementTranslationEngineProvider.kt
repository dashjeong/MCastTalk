package app.guidecast.core.translation

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class SelectiveRefinementReason {
    GLOSSARY_MATCH,
    NUMERIC_CONTENT,
    LONG_COMPLETE_SENTENCE,
    STYLE_REQUESTED,
    SOURCE_AMBIGUITY,
}

data class SelectiveRefinementDecision(
    val reasons: Set<SelectiveRefinementReason>,
) {
    val reviewRequested: Boolean
        get() = reasons.isNotEmpty()
}

fun interface SelectiveRefinementPolicy {
    /** Decisions may inspect only the original and its already-matched glossary hints. */
    fun decide(originalText: String, glossaryHints: String): SelectiveRefinementDecision

    /** Old custom SAM policies remain authoritative; language-aware evidence is opt-in. */
    fun decide(originalText: String, glossaryHints: String, sourceLanguageTag: String): SelectiveRefinementDecision =
        decide(originalText, glossaryHints)
}

class DefaultSelectiveRefinementPolicy(
    private val longSentenceCodePoints: Int = DEFAULT_LONG_SENTENCE_CODE_POINTS,
) : SelectiveRefinementPolicy {
    init {
        require(longSentenceCodePoints in 40..400)
    }

    override fun decide(
        originalText: String,
        glossaryHints: String,
    ): SelectiveRefinementDecision = SelectiveRefinementDecision(buildSet {
        if (glossaryHints.isNotBlank()) add(SelectiveRefinementReason.GLOSSARY_MATCH)
        if (KoreanNumericQuantities.normalizeForTranslation(originalText, "ko").any(Char::isDigit)) {
            add(SelectiveRefinementReason.NUMERIC_CONTENT)
        }
        val finalCharacter = originalText.trimEnd().lastOrNull()
        if (originalText.codePointCount() >= longSentenceCodePoints &&
            finalCharacter != null && finalCharacter in SENTENCE_TERMINATORS) {
            add(SelectiveRefinementReason.LONG_COMPLETE_SENTENCE)
        }
    })

    override fun decide(
        originalText: String,
        glossaryHints: String,
        sourceLanguageTag: String,
    ): SelectiveRefinementDecision {
        val decision = decide(originalText, glossaryHints)
        return if (SourceSemanticEvidence.classify(sourceLanguageTag, originalText).isNotEmpty()) {
            decision.copy(reasons = decision.reasons + SelectiveRefinementReason.SOURCE_AMBIGUITY)
        } else decision
    }

    private companion object {
        const val DEFAULT_LONG_SENTENCE_CODE_POINTS = 80
        val SENTENCE_TERMINATORS = setOf('.', '?', '!', '\u3002', '\uFF1F', '\uFF01')
    }
}

enum class SelectiveRefinementOutcome {
    DRAFT_ACCEPTED,
    REVIEW_ACCEPTED,
    REVIEW_FAILED,
    REVIEW_TIMED_OUT,
    REVIEW_REJECTED,
    REVIEW_UNAVAILABLE,
}

data class SelectiveRefinementDiagnostic(
    val targetLanguageTag: String,
    val reasons: Set<SelectiveRefinementReason>,
    val outcome: SelectiveRefinementOutcome,
)

/**
 * Produces one ML Kit-style draft and optionally asks a second engine to refine that exact draft.
 * Reviewer failure never runs the draft engine again and never merges two outputs.
 */
class SelectiveRefinementTranslationEngineProvider(
    private val draftProvider: TranslationEngineProvider,
    private val reviewerProvider: TranslationEngineProvider,
    private val draftTimeoutMillis: Long = DEFAULT_DRAFT_TIMEOUT_MILLIS,
    private val reviewTimeoutMillis: Long = DEFAULT_REVIEW_TIMEOUT_MILLIS,
    private val policy: SelectiveRefinementPolicy = DefaultSelectiveRefinementPolicy(),
    private val reviewerAvailable: (targetLanguageTag: String) -> Boolean = { true },
    private val onDiagnostic: (SelectiveRefinementDiagnostic) -> Unit = {},
    /**
     * Opt-in ceiling for a reviewer that declares its own bounded queue and inference budget.
     * Ordinary reviewers and callers omitting this value retain the short review deadline.
     * The ceiling is not extra inference time: the fair queue still enforces its own deadlines.
     */
    private val queuedReviewTimeoutMillis: Long? = null,
) : TranslationEngineProvider {
    init {
        require(draftTimeoutMillis in 500L..15_000L)
        require(reviewTimeoutMillis in 100L..5_000L)
        require(draftTimeoutMillis + reviewTimeoutMillis <= 15_000L)
        require(queuedReviewTimeoutMillis == null || queuedReviewTimeoutMillis in 100L..60_000L)
    }

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        val configuredTargetLanguageTag = targetLanguageTag
        val draftEngine = draftProvider.engineFor(configuredTargetLanguageTag)
        return object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis: Long =
                draftTimeoutMillis + (queuedReviewTimeoutMillis?.plus(QUEUE_COMPLETION_ALLOWANCE_MILLIS)
                    ?: reviewTimeoutMillis)

            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                require(targetLanguageTag == configuredTargetLanguageTag) {
                    "Selective refinement engine was requested for a different target language"
                }
                val draft = withTimeout(draftTimeoutMillis) {
                    draftEngine.translatePreservingSourceContext(
                        text,
                        contextBefore,
                        sourceLanguageTag,
                        targetLanguageTag,
                    )
                }
                require(draft.isNotBlank()) { "Draft translator returned an empty result" }

                val glossaryHints = currentCoroutineContext()[TranslationGlossaryContext]?.hints.orEmpty()
                val policyDecision = policy.decide(text, glossaryHints, sourceLanguageTag)
                // An explicitly selected style, including AUTO, requests a meaning/register
                // review even for short drafts. Keep every availability, quality and deadline guard.
                val style = currentCoroutineContext()[TranslationStyleContext]?.style
                val decision = if (style != null) {
                    policyDecision.copy(reasons = policyDecision.reasons + SelectiveRefinementReason.STYLE_REQUESTED)
                } else policyDecision
                if (!decision.reviewRequested) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.DRAFT_ACCEPTED)
                    return draft
                }

                if (!TranslationReviewContext.canRepresent(text, draft)) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_REJECTED)
                    return draft
                }
                val available = try {
                    reviewerAvailable(targetLanguageTag)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: Error) {
                    throw fatal
                } catch (_: Exception) {
                    false
                }
                if (!available) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_UNAVAILABLE)
                    return draft
                }

                val reviewContext = TranslationReviewContext(
                    originalText = text,
                    draftTranslation = draft,
                    sourceLanguageTag = sourceLanguageTag,
                    targetLanguageTag = targetLanguageTag,
                    reasons = decision.reasons,
                )
                val reviewed = try {
                    val reviewerEngine = reviewerProvider.engineFor(targetLanguageTag)
                    withTimeoutOrNull(reviewCallTimeoutMillis(reviewerEngine)) {
                        withContext(reviewContext) {
                            reviewerEngine.translatePreservingSourceContext(
                                text,
                                contextBefore,
                                sourceLanguageTag,
                                targetLanguageTag,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (fatal: Error) {
                    throw fatal
                } catch (_: TranslationQueueWaitTimeoutException) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
                    return draft
                } catch (_: TranslationInferenceTimeoutException) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
                    return draft
                } catch (_: Exception) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_FAILED)
                    return draft
                }
                if (reviewed == null) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
                    return draft
                }
                if (!reviewResultIsConservative(text, draft, reviewed, sourceLanguageTag, targetLanguageTag)) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_REJECTED)
                    return draft
                }
                report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_ACCEPTED)
                return reviewed
            }
        }
    }

    private fun reviewCallTimeoutMillis(engine: TextTranslationEngine): Long {
        val ceiling = queuedReviewTimeoutMillis ?: return reviewTimeoutMillis
        val bounded = engine as? BoundedQueuedTranslationEngine ?: return reviewTimeoutMillis
        val declared = bounded.maximumCallDurationMillis
        check(declared in 100L..ceiling) { "Reviewer queue budget exceeds the configured bound" }
        // Let the queue report its own timeout before the outer safety watchdog cancels it.
        return declared + QUEUE_COMPLETION_ALLOWANCE_MILLIS
    }

    private fun report(
        targetLanguageTag: String,
        decision: SelectiveRefinementDecision,
        outcome: SelectiveRefinementOutcome,
    ) {
        runCatching {
            onDiagnostic(SelectiveRefinementDiagnostic(targetLanguageTag, decision.reasons, outcome))
        }
    }

    private companion object {
        const val DEFAULT_DRAFT_TIMEOUT_MILLIS = 3_000L
        const val DEFAULT_REVIEW_TIMEOUT_MILLIS = 800L
        const val QUEUE_COMPLETION_ALLOWANCE_MILLIS = 100L
    }
}

private suspend fun TextTranslationEngine.translatePreservingSourceContext(
    text: String,
    contextBefore: String?,
    sourceLanguageTag: String,
    targetLanguageTag: String,
): String = if (this is ContextualTextTranslationEngine) {
    translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
} else {
    translate(text, sourceLanguageTag, targetLanguageTag)
}

internal fun reviewResultIsConservative(
    originalText: String,
    draftTranslation: String,
    reviewedTranslation: String,
    sourceLanguageTag: String = "ko",
    targetLanguageTag: String = "en",
): Boolean {
    if (reviewedTranslation.isBlank()) return false
    val originalNumbers = normalizedNumbers(originalText, sourceLanguageTag)
    if (originalNumbers.isNotEmpty() &&
        targetLanguageTag.replace('_', '-').substringBefore('-').equals("en", ignoreCase = true)) {
        val comparison = EnglishCardinalNumbers.normalizeForComparison(reviewedTranslation) ?: return false
        // The source is authoritative. Never let a malformed draft prevent repair of its number.
        return originalNumbers.map(::canonicalIntegerGrouping) ==
            normalizedNumbers(comparison, targetLanguageTag).map(::canonicalIntegerGrouping)
    }
    val draftNumbers = normalizedNumbers(draftTranslation, targetLanguageTag)
    val reviewedNumbers = normalizedNumbers(reviewedTranslation, targetLanguageTag)
    // A draft can contain the numeric mistake being repaired. Explicit source quantities are
    // authoritative; when none can be parsed, retain the earlier conservative draft guard.
    return reviewedNumbers == originalNumbers &&
        (originalNumbers.isNotEmpty() || reviewedNumbers == draftNumbers)
}

private fun normalizedNumbers(text: String, languageTag: String): List<String> = NUMERIC_TOKEN.findAll(
    KoreanNumericQuantities.normalizeForTranslation(text, languageTag),
)
    .map { match ->
        buildString {
            match.value.codePoints().forEach { codePoint ->
                val digit = Character.digit(codePoint, 10)
                when {
                    digit >= 0 -> append(digit)
                    codePoint == 0x2212 -> append('-')
                    codePoint == 0xFF05 -> append('%')
                    codePoint == 0xFF0E -> append('.')
                    codePoint == 0xFF0C -> append(',')
                    codePoint == 0xFF1A -> append(':')
                    !Character.isWhitespace(codePoint) -> appendCodePoint(codePoint)
                }
            }
        }
    }
    .toList()

private fun canonicalIntegerGrouping(token: String): String =
    if (GROUPED_INTEGER.matches(token)) token.replace(",", "") else token

private val GROUPED_INTEGER = Regex("[+\\-]?[1-9][0-9]{0,2}(?:,[0-9]{3})+")

private val NUMERIC_TOKEN = Regex(
    "[+\\-\\u2212]?\\p{Nd}+(?:[.,:/\\uFF0E\\uFF0C\\uFF1A]\\p{Nd}+)*(?:\\s*[%\\uFF05])?",
)

private fun String.codePointCount(): Int = codePointCount(0, length)
