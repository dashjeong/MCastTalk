package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputKind

internal enum class RelaySetupItem(val label: String, val message: String) {
    INPUT("마이크", "중계에 사용할 마이크를 선택해 주세요."),
    LANGUAGES("발화·통역 언어", "발화 언어와 다른 통역 언어를 1~5개 선택해 주세요."),
    OUTPUT("기기 재생·LAN 방송", "기기 재생 또는 LAN 방송을 켜 주세요."),
    SERVICE("AI 서비스", "통역 중계에 사용할 Live 음성 서비스를 선택해 주세요."),
    MODEL("AI 모델", "통역 중계를 지원하는 모델을 선택해 주세요."),
    KEY("API 키", "선택한 AI 서비스의 API 키를 입력해 주세요."),
    VOICE("통역 음성", "다음 중계에서 사용할 음성을 선택합니다."),
    PROFESSIONAL("전문 분야·자료·지침", "전문 분야와 통역에 참고할 자료를 설정합니다."),
    COMPARISON("오프라인 비교·사용량", "기기에서 들을 언어의 비교와 사용량을 확인합니다."),
}

/** Navigation advice only. The service retains the authoritative transmission admission. */
internal fun relayRequiredSetting(api: TranslationApiOptions, relay: InterpreterRelayOptions,
    inputKind: AudioInputKind?): RelaySetupItem? = when {
    !api.usesNativeLiveAudio -> RelaySetupItem.SERVICE
    !validTranslationApiOptions(api) -> RelaySetupItem.MODEL
    !api.hasKey -> RelaySetupItem.KEY
    NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.none { it.languageTag == relay.source } -> RelaySetupItem.LANGUAGES
    relay.targetLanguageTags.size !in 1..5 || relay.targetLanguageTags.distinct().size != relay.targetLanguageTags.size ||
        relay.target !in relay.targetLanguageTags || relay.targetLanguageTags.any { target ->
            nativeRelayTargetLanguageOptions(relay.source).none { it.languageTag == target }
        } -> RelaySetupItem.LANGUAGES
    !relay.localPlayback && !relay.networkBroadcast -> RelaySetupItem.OUTPUT
    inputKind !in setOf(AudioInputKind.BUILT_IN, AudioInputKind.WIRED_HEADSET, AudioInputKind.USB, AudioInputKind.BLUETOOTH) -> RelaySetupItem.INPUT
    else -> null
}

internal fun streamingBroadcastRequiredSetting(api: TranslationApiOptions, translate: Boolean): String? =
    if (translate && api.usesNativeLiveAudio) "service" else null

internal fun streamingRequiredSetting(api: TranslationApiOptions, translate: Boolean,
    targets: Set<String>, authorized: Boolean): String? = when {
    !translate -> null
    streamingBroadcastRequiredSetting(api, translate) != null -> "service"
    targets.isEmpty() -> "languages"
    api.provider != TranslationApiProvider.LOCAL && !api.hasKey -> "key"
    api.provider != TranslationApiProvider.LOCAL && !authorized -> "consent"
    else -> null
}

internal data class StreamingPreparationAdvice(val recognition: Boolean, val voices: List<String>, val translators: List<String>)

internal fun streamingPreparationAdvice(api: TranslationApiOptions, models: TranslationModelUiState): StreamingPreparationAdvice? {
    if (!models.broadcastTranslationEnabled) return null
    val advice = StreamingPreparationAdvice(!models.speechRecognitionReady,
        models.selectedLanguageTags.filterNot(models::ttsReady),
        if (api.provider == TranslationApiProvider.LOCAL)
            models.selectedLanguageTags.filterNot { translationModelReadyForChannel(models, it) } else emptyList())
    return advice.takeIf { it.recognition || it.voices.isNotEmpty() || it.translators.isNotEmpty() }
}
