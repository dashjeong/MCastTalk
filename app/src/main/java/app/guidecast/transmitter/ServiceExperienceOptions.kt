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
            if (agent && options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL) "마이크 음성과 입력한 분야·상황 설명을 Google로 보냅니다."
            else "마이크 음성을 Google로 보냅니다.",
            agent, false, false,
            "현재 앱에서는 한 언어로 최대 60초씩 이용합니다. 여러 언어는 Gemini 다국어 통역을 선택하세요. 자료 참고 번역과 학습 비교는 이 음성 경로에서 지원하지 않습니다.",
        )
    }
    else -> ServiceExperience(
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
            "현재 앱의 OpenAI Realtime 연결은 문장 번역입니다. 음성을 API로 보내는 직접 음성 통역은 아직 지원하지 않습니다."
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
    if (options.provider == TranslationApiProvider.GEMINI_LIVE) listOf(
        ServiceModelChoice(GEMINI_LIVE_TRANSLATE, "연속 음성 통역", "말하는 내용을 이어서 통역합니다. 분야 지시와 자료 참고는 지원하지 않습니다.", OnlineInterpretationMode.CONTINUOUS),
        ServiceModelChoice(GEMINI_LIVE_AGENT, "분야별 음성 통역", "분야·상황 설명을 통역 지시에 추가합니다. 정확도 보증이나 자료 학습은 아닙니다.", OnlineInterpretationMode.PROFESSIONAL),
    ) else emptyList()

/** A preset never changes a key, grants consent or enables a learning network request. */
internal fun applyServiceModelChoice(current: TranslationApiOptions, choice: ServiceModelChoice): TranslationApiOptions {
    require(serviceModelChoices(current).any { it == choice })
    return current.copy(model = choice.id, interpretationMode = choice.interpretationMode,
        allowOnline = false, allowLiveAudio = false, allowDomainReferences = false)
}

internal data class ExperienceChoice(val id: String, val title: String, val description: String)
