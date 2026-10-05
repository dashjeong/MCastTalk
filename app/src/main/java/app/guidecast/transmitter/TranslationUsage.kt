package app.guidecast.transmitter

import app.guidecast.core.translation.ModalityUsage
import app.guidecast.core.translation.OnlineUsage
import org.json.JSONObject

/** Text-only HTTP/Realtime adapters. Unknown or unexpected modalities remain unconfirmed. */
internal fun reportedTranslationUsage(options: TranslationApiOptions, raw: String): OnlineUsage? = runCatching {
    requireBoundedJson(raw)
    val root = JSONObject(raw)
    val usage = root.getJSONObject(if (options.provider == TranslationApiProvider.GEMINI) "usageMetadata" else "usage")
    val input: Long
    val cached: Long
    val output: Long
    when {
        options.provider == TranslationApiProvider.OPENAI_REALTIME -> {
            val details = usage.getJSONObject("input_token_details")
            val outputs = usage.getJSONObject("output_token_details")
            // This adapter is text-only. Unexpected billed audio is unconfirmed, never priced as text.
            require(details.getLong("audio_tokens") == 0L && outputs.getLong("audio_tokens") == 0L)
            input = details.getLong("text_tokens")
            cached = details.getLong("cached_tokens")
            output = outputs.getLong("text_tokens")
            require(input == usage.getLong("input_tokens") && output == usage.getLong("output_tokens"))
        }
        options.provider == TranslationApiProvider.GEMINI -> {
            input = usage.getLong("promptTokenCount")
            cached = usage.optLong("cachedContentTokenCount", 0)
            output = Math.addExact(usage.getLong("candidatesTokenCount"), usage.optLong("thoughtsTokenCount", 0))
        }
        options.protocol == TranslationApiProtocol.RESPONSES -> {
            input = usage.getLong("input_tokens")
            cached = usage.getJSONObject("input_tokens_details").getLong("cached_tokens")
            output = usage.getLong("output_tokens")
        }
        else -> {
            input = usage.getLong("prompt_tokens")
            cached = usage.getJSONObject("prompt_tokens_details").getLong("cached_tokens")
            output = usage.getLong("completion_tokens")
        }
    }
    OnlineUsage(ModalityUsage(input, cached, output))
}.getOrNull()

data class TranslationUsageSummary(
    val requests: Long = 0,
    val unconfirmedUsage: Long = 0,
    val unpricedUsage: Long = 0,
    val estimatedUsd: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val textInputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val textOutputTokens: Long = 0,
    val requestWallMillis: Long = 0,
    val lastModel: String? = null,
    val priceVersion: String? = null,
    val sharedTargetCount: Int? = null,
    val reportedPromptTokens: Long? = null,
    val reportedCandidateTokens: Long? = null,
    val reportedThoughtTokens: Long? = null,
    val reportedTotalTokens: Long? = null,
    val reportedInputTokens: Long? = null,
    val reportedOutputTokens: Long? = null,
    val reportedReasoningTokens: Long? = null,
    val reportedTotalsMatch: Boolean? = null,
)
