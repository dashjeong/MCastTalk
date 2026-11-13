package app.guidecast.transmitter

enum class LearningStage(val label: String) {
    NOT_SENT("보내지 않음"), NOT_SAVED("저장하지 않음"), DEFERRED("나중에 적용"), APPROVAL_PENDING("승인 대기"),
    FAILED("실패"), NOT_APPLIED("적용하지 않음"), REVIEWING("검토 중"), APPLIED("적용됨"), UNCHANGED("변경 없음"),
}

/** 원문·이용 키·서버 오류 본문을 넣지 않는 고정 안내만 사용한다. */
data class LearningFeedback(val stage: LearningStage, val reason: String) {
    val message: String get() = stage.label + " · " + reason
}

internal fun cloudReviewBlockReason(options: DeveloperLabOptions, developer: Boolean, network: Boolean, live: Boolean): String? = when {
    !network -> "현재 통역 방식에서는 검토 내용을 보내지 않습니다."
    !developer -> "개발자 표시가 꺼져 있습니다."
    !options.cloudReviewEnabled -> "검토 내용 보내기에 동의하지 않았습니다."
    !options.hasApiKey -> "검토 서비스의 이용 키가 없습니다."
    !validReviewModel(options.modelId) || options.comparisonEnabled && !validReviewModel(options.secondaryModelId) ->
        "검토 서비스 선택을 확인해 주세요."
    options.comparisonEnabled && !options.hasComparisonKeys -> "비교할 두 서비스의 이용 키가 필요합니다."
    live && !options.autoLearnEnabled -> "방송 중에는 검토 결과 저장이 꺼져 있습니다."
    else -> null
}
