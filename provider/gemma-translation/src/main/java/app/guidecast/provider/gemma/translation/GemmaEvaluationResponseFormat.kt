package app.guidecast.provider.gemma.translation

/** Shape only: no source, glossary, history, reference answer, or translation is embedded. */
internal fun gemmaEvaluationResponseSchema(modelId: String, enabled: Boolean): String? {
    if (!enabled) return null
    require(modelId == GemmaModelVariant.E4B_IT_ID) { "JSON response format evaluation requires E4B" }
    return """{"type":"object","properties":{"translation":{"type":"string"}},"required":["translation"],"additionalProperties":false}"""
}
