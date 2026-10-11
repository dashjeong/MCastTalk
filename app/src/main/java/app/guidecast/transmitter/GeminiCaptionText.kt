package app.guidecast.transmitter

/** Strip only recognized control prefixes for the selected lane, after assembling its deltas. */
internal fun geminiVisibleTranslation(raw: String, target: String, terminal: Boolean): String {
    val text = raw.trimStart()
    val prefix = "<speaker:${geminiLiveTarget(target)}>"
    val visible = when {
        text.startsWith(prefix, ignoreCase = true) -> text.substring(prefix.length).trimStart()
        !terminal && text.isNotEmpty() && prefix.startsWith(text, ignoreCase = true) -> ""
        else -> raw
    }
    val already = OBSERVED_GEMINI_ALREADY_PREAMBLE.find(visible.trimStart())
        ?.takeIf { it.groupValues[1].equals(geminiLiveTarget(target), ignoreCase = true) }
    val caption = if (already != null) {
        val body = visible.trimStart().substring(already.range.last + 1).trimStart()
        // Only this complete control wrapper licenses removal of its terminal marker.
        if (terminal) {
            val trimmed = body.trimEnd()
            if (trimmed.endsWith("✨{pause}")) trimmed.removeSuffix("✨{pause}").trimEnd()
            else trimmed.removeSuffix("✨").trimEnd()
        } else body
    } else visible
    return geminiTranscriptionFragment(caption).orEmpty()
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
        OBSERVED_GEMINI_ALREADY_PREAMBLE.containsMatchIn(text) ||
        (text.isEmpty() && OBSERVED_GEMINI_ALREADY_PREAMBLE.containsMatchIn(raw.trimStart())) ||
        text.endsWith("<partial>") || (text.startsWith("<partial>") && !text.contains("</partial>"))
}

private val OBSERVED_GEMINI_ALREADY_PREAMBLE = Regex(
    """^🧠\[([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)(?:\]|\|)<already \1>""", RegexOption.IGNORE_CASE)

private val OBSERVED_GEMINI_CAPTION_PREAMBLE = Regex(
    """^(?:🧠\s*)?\[([A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)]\s*<to\s+\1>""", RegexOption.IGNORE_CASE)
