package app.guidecast.transmitter

import app.guidecast.transmitter.DomainCorpusRepository.Companion.normalizeTargetLanguageTag

/** A separate, memory-only purpose grant; Live audio permission never implies text teaching consent. */
internal data class DeferredGoogleTeacherPermit(
    val generation: Long,
    val liveOptions: TranslationApiOptions,
    val textOptions: TranslationApiOptions,
    val targets: List<String>,
    val grantedAtElapsedNanos: Long,
) {
    override fun toString() = "DeferredGoogleTeacherPermit(generation=$generation, content=redacted)"
    fun comparisonGrant() = DeferredTeacherGrant(generation, targets, extraTextAndCostConfirmed = true)
}

internal fun validDeferredGoogleTeacherChoice(live: TranslationApiOptions,
    selectedText: TranslationApiOptions, targets: List<String>): Boolean =
    live.provider == TranslationApiProvider.GEMINI_LIVE && live.usesNativeLiveAudio &&
        selectedText.provider == TranslationApiProvider.GEMINI && !selectedText.usesNativeLiveAudio &&
        validTranslationApiOptions(live) && validTranslationApiOptions(selectedText) &&
        live.credentialScope == selectedText.credentialScope && translationPrice(selectedText) != null &&
        targets.size in 1..5 && targets.distinct().size == targets.size &&
        targets.all { tag -> tag != "ko" && tag != "ko-KR" &&
            runCatching { normalizeTargetLanguageTag(tag) == tag }.getOrDefault(false) }
