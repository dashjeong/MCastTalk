package app.guidecast.transmitter

/** Strip only the selected lane's provider speaker prefix, after assembling its deltas. */
internal fun geminiVisibleTranslation(raw: String, target: String, terminal: Boolean): String {
    val text = raw.trimStart()
    val prefix = "<speaker:${geminiLiveTarget(target)}>"
    val visible = when {
        text.startsWith(prefix, ignoreCase = true) -> text.substring(prefix.length).trimStart()
        !terminal && text.isNotEmpty() && prefix.startsWith(text, ignoreCase = true) -> ""
        else -> raw
    }
    return geminiTranscriptionFragment(visible).orEmpty()
}

/** An exact no-speech sentinel is not a spoken transcription fragment. */
internal fun geminiTranscriptionFragment(raw: String?): String? =
    raw?.takeUnless { it.trim() == "<no speech detected>" }

/** Observed caption artifacts are not a provider protocol or a general meaning/completeness test.
 * Keep the received text; require confirmation instead of claiming a usable terminal caption.
 */
internal fun geminiTerminalCaptionNeedsConfirmation(raw: String, target: String): Boolean {
    val text = geminiVisibleTranslation(raw, target, terminal = true).trim()
    return OBSERVED_GEMINI_CAPTION_PREAMBLE.containsMatchIn(text) ||
        text.endsWith("<partial>") || (text.startsWith("<partial>") && !text.contains("</partial>"))
}

private val OBSERVED_GEMINI_CAPTION_PREAMBLE = Regex(
    """^(?:🧠\s*)?\[([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)]\s*<to\s+\1>""", RegexOption.IGNORE_CASE)
