package app.guidecast.transmitter

/** Shared source-aware policy: app PCM recognition does not inherit the platform file API gate. */
internal fun fileSpeechSupportFailure(
    source: String?,
    sdkInt: Int,
    platformAvailable: Boolean,
    appLanguageAvailable: Boolean,
    appLanguageReason: String? = null,
): String? = when {
    source != null && FileSpeechTranscriber.usesAppRecognition(source) ->
        if (appLanguageAvailable) null else appLanguageReason ?: "선택한 언어의 앱 음성 인식을 사용할 수 없습니다. 지원 언어와 모델을 확인하세요."
    source == null && sdkInt < 34 -> "자동 언어 감지는 Android 14 이상이 필요합니다. 말하는 언어를 직접 선택하세요."
    sdkInt < 33 -> "이 언어의 파일 인식은 Android 13 이상이 필요합니다. 앱이 지원하는 원문 언어를 직접 선택하세요."
    !platformAvailable -> "기기의 자동 언어 감지·음성 인식을 사용할 수 없습니다. 앱이 지원하는 원문 언어를 직접 선택하세요."
    else -> null
}
