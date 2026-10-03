package app.guidecast.core.translation

import java.math.BigDecimal

/** Provider-reported tokens only. Null means unconfirmed, never zero cost. */
data class ModalityUsage(val input: Long, val cachedInput: Long, val output: Long) {
    init { require(input >= 0 && cachedInput in 0..input && output >= 0) }
}
data class OnlineUsage(val text: ModalityUsage, val audio: ModalityUsage = ModalityUsage(0, 0, 0))
data class TokenRates(val input: BigDecimal, val cachedInput: BigDecimal, val output: BigDecimal) {
    init { require(listOf(input, cachedInput, output).all { it.signum() >= 0 }) }
    fun cost(usage: ModalityUsage): BigDecimal =
        (input * (usage.input - usage.cachedInput).toBigDecimal() +
            cachedInput * usage.cachedInput.toBigDecimal() + output * usage.output.toBigDecimal()).movePointLeft(6)
}
data class OnlinePriceTable(val model: String, val version: String, val source: String,
    val text: TokenRates, val audio: TokenRates) {
    fun estimate(usage: OnlineUsage?): BigDecimal? = usage?.let { text.cost(it.text) + audio.cost(it.audio) }
}
object OnlinePrices {
    private fun rates(input: String, cache: String, output: String) =
        TokenRates(input.toBigDecimal(), cache.toBigDecimal(), output.toBigDecimal())
    val realtimeMini = OnlinePriceTable("gpt-realtime-2.1-mini", "2026-10-03-standard-usd",
        "https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini",
        rates("0.60", "0.06", "2.40"), rates("10.00", "0.30", "20.00"))
    val openAiTextMini = OnlinePriceTable("gpt-5.4-mini", "2026-10-03-standard-usd",
        "https://developers.openai.com/api/docs/models/gpt-5.4-mini",
        rates("0.75", "0.075", "4.50"), rates("0", "0", "0"))
    val geminiFlashLite = OnlinePriceTable("gemini-3.5-flash-lite", "2026-10-03-standard-paid-usd",
        "https://ai.google.dev/gemini-api/docs/pricing",
        rates("0.30", "0.03", "2.50"), rates("0.30", "0.03", "2.50"))
}

/** One request per language, independent of listener count. No credentials, text or audio retained. */
class RealtimeRequestLedger {
    data class Ticket(val generation: Long, val sequence: Long, val language: String, val corpusVersion: Long, val scope: String = "legacy")
    private var generation = 0L
    private val active = mutableMapOf<String, Ticket>()
    private val seen = LinkedHashSet<Triple<String, Long, String>>()
    @Synchronized fun begin(sequence: Long, language: String, corpusVersion: Long, scope: String = "legacy"): Ticket? {
        require(sequence >= 0 && corpusVersion >= 0 && language.isNotBlank())
        val normalized = language.lowercase(java.util.Locale.ROOT)
        val key = Triple(scope, sequence, normalized)
        if (normalized in active || key in seen) return null
        seen += key
        while (seen.size > 256) seen.remove(seen.first())
        return Ticket(generation, sequence, normalized, corpusVersion, scope).also { active[normalized] = it }
    }
    @Synchronized fun accepts(ticket: Ticket): Boolean = ticket.generation == generation && active[ticket.language] == ticket
    @Synchronized fun finish(ticket: Ticket): Boolean {
        if (!accepts(ticket)) return false
        active.remove(ticket.language)
        return true
    }
    /** Switching mode/language or disconnecting invalidates every late callback atomically. */
    @Synchronized fun cancelAll() { generation++; active.clear(); seen.clear() }
}
