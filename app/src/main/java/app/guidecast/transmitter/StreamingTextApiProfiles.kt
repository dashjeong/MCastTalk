package app.guidecast.transmitter

/** Streaming keeps device ASR and TTS; only the sentence translator changes. */
internal fun isOnlineTextApiProfile(options: TranslationApiOptions): Boolean =
    options.provider != TranslationApiProvider.LOCAL && !options.usesNativeLiveAudio &&
        validTranslationApiOptions(options)

private fun textApiProfile(options: TranslationApiOptions): TranslationApiOptions? {
    if (!validTranslationApiOptions(options) || options.provider == TranslationApiProvider.LOCAL) return null
    val text = when (options.provider) {
        TranslationApiProvider.GEMINI_LIVE -> geminiSharedInputChoice(options)
        TranslationApiProvider.OPENAI_REALTIME -> options.copy(realtimeAudio = false)
        else -> options
    }.copy(realtimeAudio = false, allowOnline = false, allowLiveAudio = false,
        allowDomainReferences = false, localFallback = false, hasKey = false)
    return text.takeIf(::isOnlineTextApiProfile)
}

/** A text profile is separate from the provider saved for optional learning comparison. */
internal fun restoreStreamingTextApiProfile(
    current: TranslationApiOptions,
    savedText: TranslationApiOptions?,
    legacyOnline: TranslationApiOptions?,
): TranslationApiOptions {
    val saved = savedText?.takeIf(::isOnlineTextApiProfile)?.let(::textApiProfile)
    val restored = saved ?: legacyOnline?.let(::textApiProfile) ?: textApiProfile(current) ?: requireNotNull(
        textApiProfile(geminiSharedInputChoice(current).copy(realtimeAudio = false)),
    )
    return restored.copy(budgetLimitUsd = current.budgetLimitUsd)
}

/** The default remains the general picker; callers explicitly opt into the streaming route. */
internal fun translationApiServiceChoices(nativeOnly: Boolean = false, textOnly: Boolean = false): List<ExperienceChoice> {
    require(!nativeOnly || !textOnly) { "Select either a native or a text translation route" }
    return listOf(
        ExperienceChoice("gemini-batch", "Gemini · 다국어 통역", "한 번 인식한 문장을 요청 한 번으로 여러 언어로 번역하고 기기 음성으로 재생합니다."),
        ExperienceChoice("gemini", "Gemini · Live 음성 통역", "선택한 1~5개 언어마다 별도 연결·API 비용이 발생합니다. 기기 청취는 1개 언어, LAN은 모든 선택 언어를 송출합니다."),
        ExperienceChoice("openai-audio", "OpenAI · Realtime 음성 통역", "선택한 1~5개 언어마다 별도 연결·API 비용이 발생합니다. 기기 청취는 1개 언어, LAN은 모든 선택 언어를 송출합니다."),
        ExperienceChoice("openai", "OpenAI · Realtime 문장 연결", "기기에서 인식한 문장을 번역하고 기기 음성으로 재생합니다."),
        ExperienceChoice("openai-text", "OpenAI · 문장 번역", "GPT 문장 모델로 번역하고 기기 음성으로 재생합니다."),
    ).filter { choice ->
        val native = choice.id in setOf("gemini", "openai-audio")
        (!nativeOnly || native) && (!textOnly || !native)
    }
}

internal fun translationApiServiceChoice(
    current: TranslationApiOptions,
    id: String,
    nativeOnly: Boolean = false,
    textOnly: Boolean = false,
): TranslationApiOptions {
    require(translationApiServiceChoices(nativeOnly, textOnly).any { it.id == id })
    return when (id) {
        "gemini-batch" -> geminiSharedInputChoice(current).copy(realtimeAudio = false)
        "gemini" -> onlineServiceChoice(current, true)
        "openai-audio" -> openAiAudioChoice(current)
        "openai" -> onlineServiceChoice(current, false)
        "openai-text" -> openAiTextChoice(current).copy(realtimeAudio = false)
        else -> error("Unsupported translation service")
    }
}
