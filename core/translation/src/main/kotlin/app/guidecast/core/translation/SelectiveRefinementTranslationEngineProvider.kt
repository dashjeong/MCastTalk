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
        if (originalText.any(Char::isDigit)) add(SelectiveRefinementReason.NUMERIC_CONTENT)
        val finalCharacter = originalText.trimEnd().lastOrNull()
        if (originalText.codePointCount() >= longSentenceCodePoints &&
            finalCharacter != null && finalCharacter in SENTENCE_TERMINATORS) {
            add(SelectiveRefinementReason.LONG_COMPLETE_SENTENCE)
        }
    })

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
) : TranslationEngineProvider {
    init {
        require(draftTimeoutMillis in 500L..15_000L)
        require(reviewTimeoutMillis in 100L..5_000L)
        require(draftTimeoutMillis + reviewTimeoutMillis <= 15_000L)
    }

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        val configuredTargetLanguageTag = targetLanguageTag
        val draftEngine = draftProvider.engineFor(configuredTargetLanguageTag)
        return object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis: Long =
                draftTimeoutMillis + reviewTimeoutMillis

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
                val decision = policy.decide(text, glossaryHints)
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
                    withTimeoutOrNull(reviewTimeoutMillis) {
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
                } catch (_: Exception) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_FAILED)
                    return draft
                }
                if (reviewed == null) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_TIMED_OUT)
                    return draft
                }
                if (!reviewResultIsConservative(text, draft, reviewed)) {
                    report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_REJECTED)
                    return draft
                }
                report(targetLanguageTag, decision, SelectiveRefinementOutcome.REVIEW_ACCEPTED)
                return reviewed
            }
        }
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
): Boolean {
    if (reviewedTranslation.isBlank()) return false
    val originalNumbers = normalizedNumbers(originalText)
    val draftNumbers = normalizedNumbers(draftTranslation)
    val reviewedNumbers = normalizedNumbers(reviewedTranslation)
    return reviewedNumbers == originalNumbers && reviewedNumbers == draftNumbers
}

private fun normalizedNumbers(text: String): List<String> = NUMERIC_TOKEN.findAll(text)
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

private val NUMERIC_TOKEN = Regex(
    "[+\\-\\u2212]?\\p{Nd}+(?:[.,:/\\uFF0E\\uFF0C\\uFF1A]\\p{Nd}+)*(?:\\s*[%\\uFF05])?",
)

private fun String.codePointCount(): Int = codePointCount(0, length)
