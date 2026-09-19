package app.guidecast.provider.gemma.translation

/** Only fixed enum tokens cross IPC; user/provider text cannot become style instructions. */
internal object GemmaTranslationStylePrompt {
    fun apply(prompt: String, style: String): String = when (style) {
        "" -> prompt
        "AUTO" -> "Match the original situation and register: natural spoken phrasing for dialogue, formal phrasing for announcements. " +
            "Use provided context only to resolve ambiguity. Translate only the current utterance. " + FIDELITY + "\n\n" + prompt
        "FORMAL" -> "Use a clear, formal register suitable for a public announcement. " +
            FIDELITY + "\n\n" + prompt
        "CONVERSATIONAL" -> "Use natural conversational phrasing suitable for spoken guidance. " +
            FIDELITY + "\n\n" + prompt
        else -> throw IllegalArgumentException("Unknown translation style")
    }
    private const val FIDELITY = "Preserve every original fact, number, name, negation, condition and intent. " +
        "Do not embellish, infer emotions, invent examples or omit information. Change wording only when meaning is unchanged."
}
