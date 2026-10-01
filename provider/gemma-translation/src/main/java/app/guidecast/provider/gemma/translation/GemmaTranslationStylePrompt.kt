package app.guidecast.provider.gemma.translation

/** Only fixed enum tokens cross IPC; user/provider text cannot become style instructions. */
internal object GemmaTranslationStylePrompt {
    fun apply(prompt: String, style: String, variant: GemmaModelVariant = GemmaModelVariant.STANDARD): String {
        if (variant == GemmaModelVariant.E4B_IT) {
            val register = when (style) {
                "" -> return prompt
                "AUTO" -> "Match the source register: conversational dialogue, formal announcements."
                "FORMAL" -> "Use a clear formal register."
                "CONVERSATIONAL" -> "Use natural conversational phrasing without changing meaning."
                else -> throw IllegalArgumentException("Unknown translation style")
            }
            return "$register\n$prompt"
        }
        return when (style) {
        "" -> prompt
        "AUTO" -> "Match the original situation and register: natural spoken phrasing for dialogue, formal phrasing for announcements. " +
            "Use provided context only to resolve ambiguity. Translate only the current utterance. " + FIDELITY + "\n\n" + prompt
        "FORMAL" -> "Use a clear, formal register suitable for a public announcement. " +
            FIDELITY + "\n\n" + prompt
        "CONVERSATIONAL" -> "Use natural conversational phrasing suitable for spoken guidance. " +
            FIDELITY + "\n\n" + prompt
        else -> throw IllegalArgumentException("Unknown translation style")
        }
    }
    private const val FIDELITY = "Preserve every original fact, number, name, negation, condition and intent. " +
        "Do not embellish, infer emotions, invent examples or omit information. Change wording only when meaning is unchanged."
}
