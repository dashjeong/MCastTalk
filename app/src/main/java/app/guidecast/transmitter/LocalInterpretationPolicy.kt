package app.guidecast.transmitter

/** 기본은 꺼짐. 화면 연결 전에는 pmtest 빌드 속성으로만 켜서 검증한다. */
internal data class LocalInterpretationPolicy(val enabled: Boolean = BuildConfig.LOCAL_INTERPRETATION_ENABLED) {
    fun permits(channelId: String, targetCount: Int, nativeLiveAudio: Boolean, controllerReady: Boolean): Boolean =
        enabled && channelId.isNotBlank() && channelId != "source" && targetCount == 1 && !nativeLiveAudio && controllerReady
}
