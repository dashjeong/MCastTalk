package app.guidecast.transmitter

/** A local caption selection, never evidence of a provider-correlated utterance pair. */
internal data class ReviewedRelayCaptionChoice(
    val localRun: Long?, val sequence: Long, val source: String, val target: String,
    val original: String, val translation: String,
) {
    val selectionKey: String get() = "${localRun ?: "unknown"}:$sequence:$target"
}

internal fun reviewedRelayCaptionChoices(lines: List<TranslationTranscriptLine>): List<ReviewedRelayCaptionChoice> =
    lines.takeLast(100).asReversed().flatMap { row ->
        val source = row.sourceLanguageTag?.takeIf { it.isNotBlank() }
        val target = row.liveSegmentLanguage?.takeIf { it.isNotBlank() }
        if (source == null || target == null || row.sourceText.isBlank() || !row.isFinal ||
            row.liveOutputState != LiveOutputState.GENERATED || row.liveSourceFailed || row.liveSourceExpired ||
            source.substringBefore('-').equals(target.substringBefore('-'), ignoreCase = true)) emptyList()
        else row.translations[target]?.takeIf { it.isNotBlank() }?.let { translation ->
            listOf(ReviewedRelayCaptionChoice(row.nativeAudioSessionId, row.sequence, source, target,
                row.sourceText, translation))
        }.orEmpty()
    }

internal data class ReviewedRelayComparisonDraft(
    val choice: ReviewedRelayCaptionChoice? = null,
    val original: String = "",
    val translation: String = "",
    val bothReadAndSameMeaning: Boolean = false,
    val approvalEnvironment: ReviewedRelayComparisonEnvironment? = null,
) {
    fun select(value: ReviewedRelayCaptionChoice) = ReviewedRelayComparisonDraft(value, value.original, value.translation)
    fun editOriginal(value: String) = if (value == original) this
        else copy(original = value, bothReadAndSameMeaning = false, approvalEnvironment = null)
    fun editTranslation(value: String) = if (value == translation) this
        else copy(translation = value, bothReadAndSameMeaning = false, approvalEnvironment = null)
    fun confirmBoth(confirmed: Boolean, environment: ReviewedRelayComparisonEnvironment) =
        copy(bothReadAndSameMeaning = confirmed, approvalEnvironment = environment.takeIf { confirmed })
    fun invalidateConfirmation() = copy(bothReadAndSameMeaning = false, approvalEnvironment = null)
    val validTexts: Boolean get() = choice != null && reviewedRelayExampleTextValid(original) &&
        reviewedRelayTranslationTextValid(translation, requireNotNull(choice).target)
    val readyForSubmission: Boolean get() = validTexts && bothReadAndSameMeaning && approvalEnvironment != null
    fun approvedFor(environment: ReviewedRelayComparisonEnvironment): Boolean {
        val approved = approvalEnvironment ?: return false
        return readyForSubmission && approved.sameMaterials(environment) && approved.inputEpoch == environment.inputEpoch
    }
}
