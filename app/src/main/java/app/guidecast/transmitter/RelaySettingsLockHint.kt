package app.guidecast.transmitter

internal fun relaySettingsLockHint(
    item: RelaySetupItem?,
    roomBusy: Boolean,
    microphoneBusy: Boolean,
    inputStopping: Boolean,
): String? = when (item) {
    RelaySetupItem.INPUT -> when {
        inputStopping -> "마이크 입력을 정리하고 있습니다. 정리가 끝나면 입력 설정을 바꿀 수 있습니다."
        microphoneBusy -> "마이크를 끄면 입력 설정을 바꿀 수 있습니다. 방송 주소는 유지됩니다."
        else -> null
    }
    RelaySetupItem.SERVICE, RelaySetupItem.MODEL, RelaySetupItem.KEY,
    RelaySetupItem.VOICE, RelaySetupItem.PROFESSIONAL -> when {
        inputStopping -> "마이크 입력을 정리하고 있습니다. 정리가 끝나면 통역 설정을 바꿀 수 있습니다."
        microphoneBusy -> "마이크를 끄면 통역 설정을 바꿀 수 있습니다."
        else -> null
    }
    RelaySetupItem.COMPARISON -> if (roomBusy)
        "방송을 종료한 뒤 오프라인 비교를 실행하거나 추가 비교 설정을 바꿀 수 있습니다." else null
    RelaySetupItem.LANGUAGES, RelaySetupItem.OUTPUT, null -> if (roomBusy)
        "방송을 종료한 뒤 방송 언어·송출 구성을 바꿀 수 있습니다." else null
}
