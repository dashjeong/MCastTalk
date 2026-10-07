package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputKind

internal enum class RelayInputIssue(val message: String) {
    PHYSICAL_MICROPHONE_REQUIRED("마이크를 선택하세요. 기기 내부 소리·웹 입력은 마이크 On-통 Live(AI 통역)에 사용할 수 없습니다."),
    PERMISSION_REQUIRED("마이크 접근을 허용한 뒤 중계를 시작하세요."),
    SYSTEM_MUTED("기기의 마이크 음소거를 해제한 뒤 중계를 시작하세요."),
}

internal fun relayInputIssue(kind: AudioInputKind?, permissionGranted: Boolean, systemMuted: Boolean?): RelayInputIssue? = when {
    kind !in setOf(AudioInputKind.BUILT_IN, AudioInputKind.WIRED_HEADSET, AudioInputKind.USB, AudioInputKind.BLUETOOTH) -> RelayInputIssue.PHYSICAL_MICROPHONE_REQUIRED
    !permissionGranted -> RelayInputIssue.PERMISSION_REQUIRED
    systemMuted == true -> RelayInputIssue.SYSTEM_MUTED
    else -> null
}
