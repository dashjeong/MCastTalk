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
