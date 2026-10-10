package app.guidecast.transmitter

internal fun nativeDrainWarning(current: String?, rows: List<TranslationTranscriptLine>,
    sessionId: Long, completed: Boolean, pendingOutput: Boolean): String? {
    if (current != null) return current
    if (rows.any { it.nativeAudioSessionId == sessionId && it.sourceText.isNotBlank() &&
            it.liveOutputState in setOf(LiveOutputState.INCOMPLETE, LiveOutputState.CANCELLED) })
        return "통역이 끝나지 않았거나 취소된 문장이 있습니다. 자막을 확인해 주세요. 방송 주소는 유지됩니다."
    if (completed) return null
    if (pendingOutput)
        return "남은 통역을 시간 안에 완료하지 못했습니다. 방송 주소는 유지됩니다."
    return if (rows.none { it.nativeAudioSessionId == sessionId && it.sourceText.isNotBlank() })
        "인식 결과를 확인하지 못했습니다. 마이크와 입력 언어를 확인해 주세요. 방송 주소는 유지됩니다."
    else "남은 발화의 처리 완료를 확인하지 못했습니다. 자막을 확인해 주세요. 방송 주소는 유지됩니다."
}
