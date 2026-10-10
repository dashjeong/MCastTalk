package app.guidecast.transmitter

internal data class NativeComparisonPresentation(
    val supported: Boolean, val checked: Boolean, val status: String, val detail: String,
)

internal fun nativeLearningInputBoundaryKnown(inputJobPresent: Boolean, phase: InputPhase, captureState: String): Boolean =
    !inputJobPresent && phase == InputPhase.IDLE && captureState in setOf("NOT_STARTED", "CLOSED", "FAILED")

/** A saved preference cannot represent a comparison running in an unsupported connection. */
internal fun nativeComparisonPresentation(options: TranslationApiOptions, requested: Boolean,
    active: Boolean = false, connectedModel: String? = null, startedGeneration: Long? = null,
    currentGeneration: Long? = null): NativeComparisonPresentation {
    if (active && connectedModel != options.model) return NativeComparisonPresentation(false, false,
        "비교 학습 · 연결 모델 확인 중", "현재 연결을 확인한 뒤 비교 상태를 표시합니다. 음성 중계와는 별도입니다.")
    val supported = serviceExperience(options).supportsNativePairComparison
    if (supported && requested && active && startedGeneration != null && startedGeneration != currentGeneration)
        return NativeComparisonPresentation(true, true, "비교 학습 · 다음 중계 시작부터 적용", NativeLearningPause.RESTART.label)
    val deferredGeminiComparison = options.provider == TranslationApiProvider.GEMINI_LIVE &&
        options.model in setOf(GEMINI_LIVE_AGENT, GEMINI_LIVE_TRANSLATE)
    return if (!supported) NativeComparisonPresentation(false, false,
        if (deferredGeminiComparison) "방송 후 예문 비교 설정" else "비교 학습 · 현재 모델에서 미지원",
        if (deferredGeminiComparison)
            "이 모델의 실시간 원문과 통역을 바로 학습 예문으로 저장하지 않습니다. 한국어 방송은 '방송 후 원음으로 예문 비교'에서 별도 문장 전송·비용에 동의한 뒤 비교할 수 있습니다. 음성 통역 중계는 이용할 수 있습니다."
        else "원문·통역의 대응 ID를 확인할 수 없어 비교하지 않습니다. 음성 통역 중계는 이용할 수 있습니다.")
    else NativeComparisonPresentation(true, requested,
        if (requested) "비교 학습 · 확정 예문 비교 선택됨" else "비교 학습 · 꺼짐",
        "준비된 오프라인 모델로 비교하며 직접 검수·저장한 예문만 재사용합니다.")
}

/** Unsupported providers never create a comparison worker, regardless of caption arrival order. */
internal fun nativeLearningSessionFor(options: TranslationApiOptions, sessionId: Long, requested: Boolean,
    monitor: NativeLearningMonitor, create: () -> NativeLearningSession): NativeLearningSession? {
    if (!serviceExperience(options).supportsNativePairComparison) {
        monitor.begin(sessionId, if (requested) NativeLearningPause.ALIGNMENT else NativeLearningPause.OFF)
        return null
    }
    return create()
}
