package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject

/** Optional reference budget, not a token-count guarantee. Never cuts CURRENT or a paired example. */
internal fun boundedDomainReferenceHints(hints: String, maximumCharacters: Int): String {
    val budget = maximumCharacters.coerceIn(0, DomainCorpusFormat.MAX_HINTS_LENGTH)
    if (hints.length <= budget) return hints
    if (budget == 0) return ""
    return try {
        val original = JSONObject(hints)
        val bounded = JSONObject().put("domain", original.getString("domain"))
        if (bounded.toString().length > budget) return ""
        val selected = JSONArray()
        val examples = original.optJSONArray("examples")
        if (examples != null) for (index in 0 until examples.length()) {
            val pair = examples.getJSONObject(index)
            val wholePair = JSONObject().put("source", pair.getString("source"))
                .put("translation", pair.getString("translation"))
            val candidate = JSONArray(selected.toString()).put(wholePair)
            bounded.put("examples", candidate)
            if (bounded.toString().length <= budget) selected.put(wholePair)
            // An oversized earlier example must not hide a later complete example that fits.
        }
        bounded.remove("examples")
        if (selected.length() > 0) bounded.put("examples", selected)
        // Relevant bilingual examples have priority over a general profile description.
        if (original.has("description")) {
            bounded.put("description", original.getString("description"))
            if (bounded.toString().length > budget) bounded.remove("description")
        }
        bounded.toString().takeIf { it.length <= budget }.orEmpty()
    } catch (_: Exception) {
        // Malformed optional references fail open. Never turn them into partial JSON or speech.
        ""
    }
}
