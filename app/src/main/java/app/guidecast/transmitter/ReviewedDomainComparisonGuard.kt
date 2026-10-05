package app.guidecast.transmitter

import app.guidecast.core.translation.requireProtectedTranslationMeaning

/** Observable rule protection and human review guard for domain comparison pairs. */
internal fun reviewedDomainComparisonPair(
    comparison: ShadowComparison,
    corrected: String,
    humanReviewed: Boolean,
): ReviewedDomainPair {
    if (comparison.nativeIdentity != null) {
        require(listOf(comparison.original, corrected).all {
            validContextUnicode(it) && !containsNativeContextCredentialLikeText(it)
        }) { "민감정보가 포함되거나 형식이 올바르지 않은 예문은 저장할 수 없습니다." }
    }
    val sourceTag = comparison.source.trim().lowercase().substringBefore('-')
    val targetTag = comparison.target.trim().lowercase().substringBefore('-')
    require(sourceTag.isNotEmpty() && targetTag.isNotEmpty() && sourceTag != targetTag) {
        "출발어와 도착어를 올바르게 지정해야 합니다."
    }
    require(comparison.offline.isNotBlank()) {
        "비교 기준 번역이 비어 있습니다."
    }
    val pair = reviewedDomainPair(
        original = comparison.original,
        corrected = corrected,
        target = comparison.target,
        humanReviewed = humanReviewed,
    )
    require(conservativeReviewAccepted(comparison.original, comparison.offline, pair.corrected, comparison.target)) {
        "보수적 검토 기준을 충족하지 않습니다."
    }
    requireProtectedTranslationMeaning(comparison.original, pair.corrected, sourceTag, targetTag)
    return pair
}
