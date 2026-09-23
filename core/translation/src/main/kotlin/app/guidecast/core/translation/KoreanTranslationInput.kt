package app.guidecast.core.translation

/**
 * An input copy for a context-free translator, never an ASR/UI correction.
 * Only a confirmed person classifier changes; its quantifier, particle and surrounding
 * utterance remain intact. This supplies no guessed count, context or replacement translation.
 */
object KoreanTranslationInput {
    fun normalizeForTranslation(
        text: String,
        sourceLanguageTag: String,
        maximumOutputLength: Int = Int.MAX_VALUE,
    ): String {
        require(maximumOutputLength >= 0)
        val subject = SourceSemanticEvidence.humanSubject(sourceLanguageTag, text)
        // Fused 여러분 is an audience pronoun, not an instruction to replace a counter.
        val fusedAudiencePronoun = subject != null && subject.quantifier == "여러" &&
            subject.classifierRange.first == subject.subjectRange.first + subject.quantifier.length
        val personInput = if (subject != null && !fusedAudiencePronoun) {
            text.replaceRange(subject.classifierRange, "사람")
        } else text
        val normalized = KoreanNumericQuantities.normalizeForTranslation(personInput, sourceLanguageTag)
        // Apply one budget to the composed result. Neither stage may truncate the original or
        // leave a partial normalization when expansion exceeds the provider's validated limit.
        return normalized.takeIf { it.length <= maximumOutputLength } ?: text
    }
}
