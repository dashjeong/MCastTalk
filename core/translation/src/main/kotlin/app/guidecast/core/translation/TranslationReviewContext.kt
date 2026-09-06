package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable, request-local refinement input; it is not prior-sentence translation context. */
class TranslationReviewContext(
    val originalText: String,
    val draftTranslation: String,
    val sourceLanguageTag: String,
    val targetLanguageTag: String,
    reasons: Set<SelectiveRefinementReason>,
) : AbstractCoroutineContextElement(Key) {
    val reasons: Set<SelectiveRefinementReason> = reasons.toSet()

    init {
        require(canRepresent(originalText, draftTranslation))
        require(sourceLanguageTag.isNotBlank() && targetLanguageTag.isNotBlank())
        require(sourceLanguageTag != targetLanguageTag)
        require(this.reasons.isNotEmpty())
    }

    companion object Key : CoroutineContext.Key<TranslationReviewContext> {
        const val MAX_ORIGINAL_CHARACTERS = 600
        const val MAX_DRAFT_CHARACTERS = 1_200

        fun canRepresent(originalText: String, draftTranslation: String): Boolean =
            originalText.isNotBlank() && originalText.length <= MAX_ORIGINAL_CHARACTERS &&
                draftTranslation.isNotBlank() && draftTranslation.length <= MAX_DRAFT_CHARACTERS
    }
}
