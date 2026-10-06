package app.guidecast.transmitter

/** Voice families are choices, not a guarantee of perceived speaker gender. */
enum class RelayVoiceGender(val label: String) {
    AUTO("기본"), FEMALE("여성 계열"), MALE("남성 계열"),
}

internal fun relayVoiceSupported(options: TranslationApiOptions): Boolean = when (options.provider) {
    TranslationApiProvider.GEMINI_LIVE -> options.model == GEMINI_LIVE_AGENT
    TranslationApiProvider.OPENAI_REALTIME -> options.realtimeAudio && options.model in OPENAI_REALTIME_MODELS
    else -> false
}

internal fun relayVoiceChoices(options: TranslationApiOptions): List<RelayVoiceGender> =
    if (relayVoiceSupported(options)) RelayVoiceGender.entries.toList() else listOf(RelayVoiceGender.AUTO)

internal fun geminiRelayVoiceName(model: String, voice: RelayVoiceGender): String? =
    if (model != GEMINI_LIVE_AGENT) null else when (voice) {
        RelayVoiceGender.AUTO -> null
        RelayVoiceGender.FEMALE -> "Kore"
        RelayVoiceGender.MALE -> "Puck"
    }

internal fun openAiRelayVoiceName(voice: RelayVoiceGender): String = when (voice) {
    RelayVoiceGender.AUTO, RelayVoiceGender.FEMALE -> "marin"
    RelayVoiceGender.MALE -> "cedar"
}

internal fun relayVoiceLabel(options: TranslationApiOptions): String {
    if (!relayVoiceSupported(options)) return if (options.provider == TranslationApiProvider.GEMINI_LIVE &&
        options.model == GEMINI_LIVE_TRANSLATE) {
        "목소리 선택 미지원 · 원음 목소리의 처리는 Live Translate 모델이 결정합니다."
    } else "Live 목소리 선택 미지원 · 문장 번역의 음성은 기기에서 선택합니다."
    val name = when (options.provider) {
        TranslationApiProvider.GEMINI_LIVE -> geminiRelayVoiceName(options.model, options.liveVoice)
        else -> openAiRelayVoiceName(options.liveVoice)
    }
    return if (name == null) "기본 · 모델 기본 목소리" else "${options.liveVoice.label} · $name"
}
