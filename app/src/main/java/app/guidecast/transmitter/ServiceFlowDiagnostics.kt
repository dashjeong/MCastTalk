package app.guidecast.transmitter

internal enum class ServiceFlowAction {
    INPUT_START, PAUSE_REQUEST, RESUME_REQUEST, STOP_REQUEST, INPUT_FAILURE,
    LIVE_CONNECTING, LIVE_READY, LIVE_TURN_COMPLETE, LIVE_CLOSED,
    LIVE_GENERATED, LIVE_CANCELLED, LIVE_INCOMPLETE, LIVE_CAPTION_FAILED, LIVE_CAPTION_EXPIRED,
}
internal enum class ServiceFlowReason { REQUEST, UNKNOWN }

/** Fixed values only. Never serialize options, arbitrary model names, errors or input content. */
internal fun serviceFlowSnapshot(options: TranslationApiOptions, sessionId: String, generation: Long,
    action: ServiceFlowAction, reason: ServiceFlowReason = ServiceFlowReason.REQUEST,
    before: InputPhase? = null, after: InputPhase? = null): String {
    require(sessionId.matches(Regex("[0-9a-f-]{36}")) && generation >= 0)
    val model = when {
        options.provider == TranslationApiProvider.LOCAL -> "LOCAL_MODEL"
        options.model == GEMINI_LIVE_AGENT -> "GEMINI_LIVE_AGENT"
        options.model == GEMINI_LIVE_TRANSLATE -> "GEMINI_LIVE_TRANSLATE"
        options.model == "gemini-3.5-flash-lite" -> "GEMINI_TEXT_DEFAULT"
        options.model == "gpt-realtime-2.1-mini" -> "OPENAI_REALTIME_DEFAULT"
        options.model == "gpt-5.4-mini" -> "OPENAI_TEXT_DEFAULT"
        else -> "CUSTOM"
    }
    return "provider=${options.provider.name} model=$model revision=${options.revision} " +
        "session_id=$sessionId input_generation=$generation action=$action reason=$reason " +
        "before=${before?.name ?: "UNKNOWN"} after=${after?.name ?: "UNKNOWN"}"
}
