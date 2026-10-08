package app.guidecast.transmitter

/** Describes the app's implemented route, rather than everything a provider model can do. */
internal data class ServiceExperience(
    val title: String,
    val processing: String,
    val transmitted: String,
    val supportsDomainInstructions: Boolean,
    val supportsReferences: Boolean,
    val supportsLearningComparison: Boolean,
    val limitation: String? = null,
    val supportsNativePairComparison: Boolean = false,
)

internal fun serviceExperience(options: TranslationApiOptions): ServiceExperience = when (options.provider) {
    TranslationApiProvider.LOCAL -> ServiceExperience(
        "오프라인 통역", "음성 인식 → 기기 내 번역 → 기기 음성 재생",
        "일반 통역에서는 음성과 문장을 외부 API로 보내지 않습니다.",
        false, true, true,
        "사용할 언어 모델을 먼저 준비하세요. 온라인 비교 학습은 따로 동의했을 때만 사용합니다.",
    )
    TranslationApiProvider.GEMINI_LIVE -> {
        val translate = options.model == GEMINI_LIVE_TRANSLATE
        val agent = options.model == GEMINI_LIVE_AGENT
        ServiceExperience(
            if (translate) "Gemini 연속 음성 통역" else if (agent && options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL) "Gemini 분야별 음성 통역" else "Gemini 일반 음성 통역",
            "마이크 음성 → Gemini → 통역 음성 재생",
            if (agent) buildString {
                append("마이크 음성을 Google로 보냅니다.")
                if (options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL && options.domainPrompt.isNotBlank()) append(" 저장한 분야·상황 설명도 보냅니다.")
                if (options.interpreterInstructions.isNotBlank()) append(" 저장한 사용자 통역 지침도 보냅니다.")
                if (options.allowDomainReferences) append(" 참고 자료를 허용했으며 준비된 짧은 발췌가 있을 때만 함께 보냅니다.")
            } else "마이크 음성을 Google로 보냅니다.",
            agent, agent, false,
            if (agent) "선택 자료의 짧은 발췌를 연결할 때 전달합니다. Gemini 음성 중계는 원문·통역 대응을 자동 확인할 수 없어 자동 비교를 지원하지 않습니다. 직접 확인한 예문은 별도로 비교할 수 있습니다. 통역 언어마다 별도 연결을 사용합니다."
            else "이 번역 전용 모델은 참고 자료·사용자 지침을 지원하지 않습니다. Gemini 음성 중계는 원문·통역 대응을 자동 확인할 수 없어 자동 비교를 지원하지 않습니다. 직접 확인한 예문은 별도로 비교할 수 있습니다. 통역 언어마다 별도 연결을 사용합니다.",
        )
    }
    else -> if (options.usesNativeLiveAudio) ServiceExperience(
        "OpenAI 직접 음성 통역", "마이크 음성 → OpenAI → 통역 음성 · 원문/번역 자막",
        "마이크 음성과 선택한 말투·분야 지시를 OpenAI로 보냅니다. 원문 자막 인식도 제공자가 처리합니다.",
        true, true, false,
        "통역 언어마다 별도 연결을 사용합니다. 통역 중계에서 비교를 켜면 확정된 원문·통역 쌍을 준비된 Gemma와 비교하며 추가 API 요청은 없습니다. 직접 검수·저장한 예문만 재사용합니다. 실기기 품질 검증은 별도입니다.",
        supportsNativePairComparison = true,
    ) else ServiceExperience(
        when (options.provider) {
            TranslationApiProvider.GEMINI -> "Gemini 다국어 통역"
            TranslationApiProvider.COMPATIBLE -> "사용자 지정 API 문장 번역"
            else -> "OpenAI 문장 번역"
        },
        if (options.provider == TranslationApiProvider.GEMINI) "기기 음성 인식 한 번 → 공유 다국어 번역 요청 한 번 → 언어별 기기 음성 재생"
        else "기기 음성 인식 → API 문장 번역 → 기기 음성 재생",
        "인식된 문장과 필요한 문맥을 선택한 API로 보냅니다. 자료 참고를 허용하면 짧은 관련 근거도 보냅니다.",
        true, true, true,
        if (options.provider == TranslationApiProvider.OPENAI_REALTIME)
            "현재 선택은 문장 연결입니다. 마이크 음성을 직접 보내려면 Realtime 음성 통역을 선택하세요."
        else null,
    )
}

internal data class ServiceModelChoice(
    val id: String,
    val title: String,
    val description: String,
    val interpretationMode: OnlineInterpretationMode,
)

/** Only models already admitted by the installed route are offered as presets. */
internal fun serviceModelChoices(options: TranslationApiOptions): List<ServiceModelChoice> =
    when (options.provider) {
        TranslationApiProvider.GEMINI_LIVE -> listOf(
            ServiceModelChoice(GEMINI_LIVE_TRANSLATE, "Gemini 3.5 Live Translate (미리보기)", "음성 → 통역 음성. 연속 통역용이며 분야·말투 지시는 지원하지 않습니다. 사용량에 따라 과금됩니다.", OnlineInterpretationMode.CONTINUOUS),
            ServiceModelChoice(GEMINI_LIVE_AGENT, "Gemini 3.8 Live", "음성 → 통역 음성. 분야·말투 지시를 사용할 수 있습니다. 사용량에 따라 과금됩니다.", OnlineInterpretationMode.PROFESSIONAL),
        )
        TranslationApiProvider.GEMINI -> listOf(
            ServiceModelChoice("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "빠르고 경제적인 문장 번역. 다국어 요청 한 번 뒤 기기 음성으로 재생합니다.", options.interpretationMode),
            ServiceModelChoice("gemini-3.5-flash", "Gemini 3.5 Flash", "일반 문장 번역. Flash-Lite보다 높은 토큰 단가이며 기기 음성으로 재생합니다.", options.interpretationMode),
            ServiceModelChoice("gemini-3.8-flash", "Gemini 3.8 Flash", "복잡한 문맥을 다루는 문장 모델. 응답 시간은 달라질 수 있으며 기기 음성으로 재생합니다.", options.interpretationMode),
        )
        TranslationApiProvider.OPENAI_REALTIME -> listOf(
            ServiceModelChoice("gpt-realtime-2.1-mini", if (options.realtimeAudio) "GPT Realtime 2.1 Mini · 음성" else "GPT Realtime 2.1 Mini · 문장 연결", if (options.realtimeAudio) "마이크 음성 → 통역 음성·자막. 사용량에 따라 과금됩니다." else "인식한 문장을 보내고 기기 음성으로 재생합니다.", options.interpretationMode),
            ServiceModelChoice("gpt-realtime-2", if (options.realtimeAudio) "GPT Realtime 2 · 음성" else "GPT Realtime 2 · 문장 연결", if (options.realtimeAudio) "마이크 음성 → 통역 음성·자막. Mini보다 높은 토큰 단가입니다." else "더 높은 토큰 단가의 문장 연결이며 기기 음성으로 재생합니다.", options.interpretationMode),
        )
        TranslationApiProvider.OPENAI -> listOf(
            ServiceModelChoice("gpt-5.4-mini", "GPT 5.4 Mini", "경제적인 문장 번역. 인식한 문장을 보내고 기기 음성으로 재생합니다.", options.interpretationMode),
            ServiceModelChoice("gpt-5.4", "GPT 5.4", "복잡한 문맥을 다루는 문장 모델. Mini보다 높은 토큰 단가이며 기기 음성으로 재생합니다.", options.interpretationMode),
        )
        else -> emptyList()
    }

/** A preset never changes a key, grants consent or enables a learning network request. */
internal fun applyServiceModelChoice(current: TranslationApiOptions, choice: ServiceModelChoice): TranslationApiOptions {
    require(serviceModelChoices(current).any { it == choice })
    return current.copy(model = choice.id, interpretationMode = choice.interpretationMode,
        allowOnline = false, allowLiveAudio = false, allowDomainReferences = false)
}

internal data class ExperienceChoice(val id: String, val title: String, val description: String)
