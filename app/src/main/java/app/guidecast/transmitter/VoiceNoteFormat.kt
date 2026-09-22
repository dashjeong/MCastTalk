package app.guidecast.transmitter

import java.util.Locale

internal const val VOICE_NOTE_SAMPLE_RATE = 16_000
internal const val VOICE_NOTE_MAX_BYTES = 16_000L * 2 * 60 * 60
internal val VOICE_NOTE_LANGUAGES = linkedMapOf("ko-KR" to "한국어", "en-US" to "English", "ja-JP" to "日本語", "zh-CN" to "中文")

internal data class VoiceNoteLine(
    val startMs: Long,
    val endMs: Long,
    val original: String,
    val language: String?,
    val translation: String = "",
    val speaker: String = "",
    val edited: Boolean = false,
    val timingEstimated: Boolean = true,
)

/** Correcting recognition must never leave an old machine translation attached to new text. */
internal fun VoiceNoteLine.corrected(original: String, translation: String, translationEdited: Boolean): VoiceNoteLine {
    require(original.isNotBlank() && original.length <= 65_536 && translation.length <= 65_536)
    val source = original.trim()
    return copy(original = source,
        translation = if (source != this.original && !translationEdited) "" else translation.trim(), edited = true)
}

internal fun voiceNoteMatchingLines(lines: List<VoiceNoteLine>, query: String): List<Int> {
    val term = query.trim()
    return lines.indices.filter { index ->
        val line = lines[index]
        term.isEmpty() || line.original.contains(term, ignoreCase = true) ||
            line.translation.contains(term, ignoreCase = true) || line.speaker.contains(term, ignoreCase = true)
    }
}

/** One representation for the screen and text exports. A missing translation stays missing. */
internal fun voiceNoteLineText(line: VoiceNoteLine): String = buildString {
    if (line.speaker.isNotBlank()) append("[${line.speaker.trim()}] ")
    append(line.original.trim())
    if (line.translation.isNotBlank() && line.translation.trim() != line.original.trim()) {
        append("\n(${line.translation.trim()})")
    }
}

internal fun voiceNoteTime(ms: Long, milliseconds: Boolean = false): String {
    val value = ms.coerceAtLeast(0)
    val base = String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3_600_000, value / 60_000 % 60, value / 1_000 % 60)
    return if (milliseconds) base + String.format(Locale.ROOT, ",%03d", value % 1_000) else base
}
