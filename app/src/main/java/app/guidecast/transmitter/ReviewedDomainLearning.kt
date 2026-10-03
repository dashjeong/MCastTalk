package app.guidecast.transmitter

import java.text.Normalizer

internal data class ReviewedDomainPair(val original: String, val corrected: String, val normalizedSource: String)
data class DomainLearningRevision(val profileId: Long, val previousProfileId: Long, val revision: Long)

/** A provider answer is only a proposal. Explicit human review and local validation are mandatory. */
internal fun reviewedDomainPair(original: String, corrected: String, target: String, humanReviewed: Boolean): ReviewedDomainPair {
    require(humanReviewed) { "의미·용어를 직접 검수한 뒤 승인하세요." }
    val source = original.trim(); val translation = corrected.trim()
    require(source.length in 1..DomainCorpusFormat.MAX_TEXT_LENGTH && translation.length in 1..DomainCorpusFormat.MAX_TEXT_LENGTH) {
        "검수 예문은 원문·번역 각각 500자 이하여야 합니다."
    }
    require(listOf(source, translation).none { text -> text.any { it.code < 32 || it.code == 127 } || containsCredentialLikeText(text) }) {
        "민감정보 또는 여러 줄이 포함된 예문은 저장할 수 없습니다."
    }
    require(targetScriptMatches(translation, target)) { "번역 언어를 확인하세요." }
    return ReviewedDomainPair(source, translation, Normalizer.normalize(source, Normalizer.Form.NFC))
}
