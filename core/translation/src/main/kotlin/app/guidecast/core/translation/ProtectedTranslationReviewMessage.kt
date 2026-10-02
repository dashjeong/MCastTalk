package app.guidecast.core.translation

/** Operator-facing reason only; machine codes and translation data stay unchanged. */
fun protectedTranslationReviewMessage(code: String?): String? = when (code) {
    "GEMMA_SENTENCE_TYPE_REVIEW_REQUIRED" -> "부정 평서문이 질문으로 바뀌어 문장 유형 검토가 필요합니다"
    "GEMMA_CURRENCY_ASSET_REVIEW_REQUIRED" -> "복수 금액의 값·통화 보존을 확인할 수 없어 검토가 필요합니다"
    "GEMMA_VERBATIM_QUOTE_REVIEW_REQUIRED" -> "명시적으로 보존을 요청한 인용 원문이 달라 검토가 필요합니다"
    else -> null
}
