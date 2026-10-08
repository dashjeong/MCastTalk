package app.guidecast.transmitter

import app.guidecast.core.translation.ModalityUsage
import app.guidecast.core.translation.OnlineRequestBudget
import app.guidecast.core.translation.OnlineUsage
import java.math.BigDecimal

internal class TranslationBudgetBlocked : IllegalStateException("예상 API 예산 또는 모델 가격을 확인하세요. 요청을 전송하지 않았습니다.")

/** Local admission estimates are separate from the provider's account billing. */
internal class TranslationDispatchBudget(
    private val ledger: OnlineRequestBudget = OnlineRequestBudget(),
    private val priceFor: (TranslationApiOptions) -> app.guidecast.core.translation.OnlinePriceTable? = ::translationPrice,
) {
    internal data class Ticket(val reservation: OnlineRequestBudget.Reservation, val options: TranslationApiOptions,
        val batch: Boolean)

    fun snapshot(): OnlineRequestBudget.Snapshot = ledger.snapshot()

    fun reserve(options: TranslationApiOptions, request: String, maximumOutputTokens: Int, batch: Boolean = false): Ticket? {
        if (options.provider == TranslationApiProvider.LOCAL || options.usesNativeLiveAudio ||
            maximumOutputTokens !in 1..8_192) return null
        val limit = options.budgetLimitUsd.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val bytes = request.toByteArray(Charsets.UTF_8).size
        if (bytes !in 1..32_768) return null
        val price = priceFor(options) ?: return null
        // Each UTF-8 byte is admitted as one input token, with an additional framing allowance.
        // This is an app reservation policy, not a provider tokenizer or billing upper-bound guarantee.
        val maximum = price.estimate(OnlineUsage(ModalityUsage(bytes.toLong() + 4_096, 0, maximumOutputTokens.toLong()))) ?: return null
        return ledger.reserve(maximum, limit)?.let { Ticket(it, options, batch) }
    }

    /** Before dispatch, cancellation refunds the reservation. After dispatch, unknown usage keeps it. */
    fun settle(ticket: Ticket, raw: String?, dispatchAttempted: Boolean): Boolean {
        val actual = if (!dispatchAttempted) BigDecimal.ZERO else knownEstimate(ticket, raw)
        return ledger.settle(ticket.reservation, actual)
    }

    private fun knownEstimate(ticket: Ticket, raw: String?): BigDecimal? {
        if (raw == null) return null
        val usage = when {
            ticket.options.provider == TranslationApiProvider.GEMINI -> {
                val counts = geminiBatchUsage(raw)
                if (counts.prompt == null || counts.cached == null || counts.candidates == null ||
                    counts.thoughts == null || counts.totalsMatch != true) return null
                OnlineUsage(ModalityUsage(counts.prompt, counts.cached, Math.addExact(counts.candidates, counts.thoughts)))
            }
            ticket.options.provider in setOf(TranslationApiProvider.OPENAI, TranslationApiProvider.OPENAI_REALTIME) ->
                strictOpenAiTextUsage(ticket.options, raw) ?: return null
            else -> return null
        }
        return priceFor(ticket.options)?.estimate(usage)
    }

    private fun strictOpenAiTextUsage(options: TranslationApiOptions, raw: String): OnlineUsage? = runCatching {
        requireBoundedJson(raw)
        val root = org.json.JSONObject(raw)
        val usage = root.getJSONObject("usage")
        fun integer(row: org.json.JSONObject, field: String): Long? = when (val value = row.opt(field)) {
            is Int -> value.toLong().takeIf { it >= 0 }
            is Long -> value.takeIf { it >= 0 }
            else -> null
        }
        val normalized = if (options.provider == TranslationApiProvider.OPENAI_REALTIME) {
            require(root.optString("status") == "completed")
            val input = usage.getJSONObject("input_token_details")
            val output = usage.getJSONObject("output_token_details")
            require(integer(input, "audio_tokens") == 0L && integer(output, "audio_tokens") == 0L)
            require(integer(input, "text_tokens") != null && integer(output, "text_tokens") != null &&
                integer(input, "text_tokens") == integer(usage, "input_tokens") &&
                integer(output, "text_tokens") == integer(usage, "output_tokens"))
            // Realtime returns singular detail keys; the shared strict parser expects plural keys.
            org.json.JSONObject(root.toString()).put("usage", org.json.JSONObject(usage.toString())
                .put("input_tokens_details", org.json.JSONObject().put("cached_tokens", input.opt("cached_tokens"))))
        } else {
            if (options.protocol == TranslationApiProtocol.RESPONSES) require(root.optString("status") == "completed")
            else require(TranslationApiJson.result(options, raw) != null)
            root
        }
        val counts = openAiBatchUsage(normalized.toString())
        require(counts.input != null && counts.cached != null && counts.output != null && counts.totalsMatch == true)
        OnlineUsage(ModalityUsage(counts.input, counts.cached, counts.output))
    }.getOrNull()
}
