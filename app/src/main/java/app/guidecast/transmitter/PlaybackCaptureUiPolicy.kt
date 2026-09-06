package app.guidecast.transmitter

import app.guidecast.core.audio.PlaybackCaptureDiagnostics

internal enum class PlaybackCaptureUiTone {
    NEUTRAL,
    SUCCESS,
    WARNING,
}

internal data class PlaybackCaptureUiStatus(
    val message: String,
    val detail: String,
    val tone: PlaybackCaptureUiTone,
)

/**
 * Converts Android's anonymized playback information into an operator-facing diagnosis.
 * The result is informational only and must never gate the broadcast controls.
 */
internal fun playbackCaptureUiStatus(
    diagnostics: PlaybackCaptureDiagnostics,
    signalActive: Boolean,
): PlaybackCaptureUiStatus = when {
    signalActive -> PlaybackCaptureUiStatus(
        message = "앱 출력 PCM 수신 확인",
        detail = "가상 라인 입력에서 연속 음성이 최근 감지됐습니다. 앱 출력 그대로 모드로 웹 송출할 수 있습니다.",
        tone = PlaybackCaptureUiTone.SUCCESS,
    )

    !diagnostics.monitoringAvailable -> PlaybackCaptureUiStatus(
        message = "재생 상태 진단을 사용할 수 없음",
        detail = "입력 음량계로 수신 여부를 확인하세요. 진단 실패는 입력이나 방송을 중지하지 않습니다.",
        tone = PlaybackCaptureUiTone.NEUTRAL,
    )

    diagnostics.activePlaybackCount == 0 -> PlaybackCaptureUiStatus(
        message = "번역 앱 음성 재생 대기",
        detail = "삼성 통역 등 출력 앱에서 샘플 문장을 재생하면 여기서 수신 여부를 바로 확인할 수 있습니다.",
        tone = PlaybackCaptureUiTone.NEUTRAL,
    )

    diagnostics.potentiallyCapturablePlaybackCount > 0 &&
        diagnostics.potentiallyCapturablePlaybackCount < diagnostics.activePlaybackCount ->
        PlaybackCaptureUiStatus(
            message = "여러 재생이 섞여 대상 판정 불가",
            detail = "캡처 가능성 있는 재생 ${diagnostics.potentiallyCapturablePlaybackCount}개와 " +
                "제한된 재생이 함께 있습니다. 대상 앱 성공 여부는 PCM 표시로만 확인하세요.",
            tone = PlaybackCaptureUiTone.WARNING,
        )

    diagnostics.potentiallyCapturablePlaybackCount > 0 -> PlaybackCaptureUiStatus(
        message = "캡처 가능성 있는 재생 ${diagnostics.potentiallyCapturablePlaybackCount}개 감지",
        detail = "익명 usage·트랙 정책만 맞습니다. 앱 manifest·프로필까지 포함한 실제 허용은 PCM 표시로 확인하세요.",
        tone = PlaybackCaptureUiTone.NEUTRAL,
    )

    diagnostics.policyBlockedPlaybackCount > 0 &&
        diagnostics.unsupportedUsagePlaybackCount > 0 -> PlaybackCaptureUiStatus(
        message = "재생은 감지됐지만 캡처할 수 없음",
        detail = "제3자 캡처 차단 ${diagnostics.policyBlockedPlaybackCount}개 · " +
            "Android 캡처 불가 용도 ${diagnostics.unsupportedUsagePlaybackCount}개입니다.",
        tone = PlaybackCaptureUiTone.WARNING,
    )

    diagnostics.policyBlockedPlaybackCount > 0 -> PlaybackCaptureUiStatus(
        message = "출력 앱이 제3자 캡처를 차단함",
        detail = "일반 APK는 이 정책을 우회할 수 없습니다. 앱 자체 통역 또는 물리 입력을 사용하세요.",
        tone = PlaybackCaptureUiTone.WARNING,
    )

    else -> PlaybackCaptureUiStatus(
        message = "Android가 캡처하지 않는 재생 용도",
        detail = "통화·Assistant·접근성 계열 출력은 일반 앱의 가상 라인 입력으로 전달할 수 없습니다.",
        tone = PlaybackCaptureUiTone.WARNING,
    )
}
