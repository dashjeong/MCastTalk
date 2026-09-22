package app.guidecast.transmitter

import android.util.JsonWriter
import java.io.Writer

internal data class VoiceNoteExportOptions(
    val translations: Boolean = true,
    val timestamps: Boolean = true,
    val speakers: Boolean = true,
    val search: String? = null,
    val windowsText: Boolean = false,
)

internal data class VoiceNoteExportRequest(val note: VoiceNote, val kind: String, val options: VoiceNoteExportOptions)
private val emptySubtitleLines = Regex("\n[ \\t]*\n+")

/** Snapshot and stream the user's chosen scope, preserving original media offsets. */
internal fun writeVoiceNoteExport(
    output: Writer, note: VoiceNote, kind: String, options: VoiceNoteExportOptions,
    checkActive: () -> Unit = {},
) {
    require(kind in setOf("txt", "md", "srt", "json"))
    val indices = voiceNoteMatchingLines(note.lines, options.search.orEmpty())
    require(indices.isNotEmpty()) { "내려받을 문장이 없습니다." }
    val missing = indices.count { note.lines[it].translation.isBlank() }
    if (kind == "json") {
        val json = JsonWriter(output)
        json.beginObject().name("schema").value("mcasttalk.voice-note/1")
            .name("title").value(note.title).name("createdAtMs").value(note.createdAt)
            .name("durationMs").value(note.durationMs).name("sourceLanguage").value(note.sourceLanguage)
            .name("targetLanguage").value(note.targetLanguage).name("interrupted").value(note.interrupted)
            .name("scope").value(if (options.search == null) "all" else "search_results")
            .name("totalSegments").value(note.lines.size.toLong()).name("exportedSegments").value(indices.size.toLong())
            .name("missingTranslations").value(missing.toLong()).name("translationsIncluded").value(options.translations)
            .name("segments").beginArray()
        indices.forEach { index ->
            checkActive()
            val line = note.lines[index]
            json.beginObject().name("sourceOrdinal").value(index + 1L)
                .name("startMs").value(line.startMs).name("endMs").value(line.endMs)
                .name("timingEstimated").value(line.timingEstimated).name("language").value(line.language)
                .name("original").value(line.original).name("edited").value(line.edited)
            if (options.translations) json.name("translation").value(line.translation)
            if (options.speakers) json.name("speaker").value(line.speaker)
            json.endObject()
        }
        json.endArray().endObject().flush()
        return
    }
    val windows = kind == "txt" && options.windowsText
    fun write(text: String) { output.write(if (windows) text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n") else text) }
    if (windows) output.write("\uFEFF")
    if (kind != "srt") {
        write(if (kind == "md") "# ${note.title.noteMarkdown()}\n\n" else "${note.title}\n\n")
        write("${if (options.search == null) "전체" else "검색 결과"} ${indices.size} / ${note.lines.size}개 구간 · 번역 대기 ${missing}개\n")
        if (note.interrupted) write("녹음 중단 · 복구 확인 필요\n")
        if (indices.any { note.lines[it].timingEstimated }) write("구간 시각에 추정값이 포함되어 있습니다.\n")
        write("\n")
    }
    indices.forEachIndexed { ordinal, index ->
        checkActive()
        val line = note.lines[index]
        val shown = line.copy(translation = if (options.translations) line.translation else "",
            speaker = if (options.speakers) line.speaker else "")
        when (kind) {
            "srt" -> {
                val start = line.startMs.coerceIn(0, Long.MAX_VALUE - 1)
                write("${ordinal + 1}\n${voiceNoteTime(start, true)} --> ${voiceNoteTime(maxOf(line.endMs, start + 1), true)}\n")
                write(voiceNoteLineText(shown).replace("\r\n", "\n").replace('\r', '\n').replace(emptySubtitleLines, "\n") + "\n\n")
            }
            "md" -> {
                write("## 구간 ${index + 1}${if (options.timestamps) " · ${voiceNoteTime(line.startMs)}" else ""}\n\n")
                write("언어: ${(line.language ?: "미확인").noteMarkdown()}${if (line.edited) " · 직접 수정" else ""}\n\n")
                write(voiceNoteLineText(shown).noteMarkdown() + "\n\n")
            }
            else -> {
                if (options.timestamps) write(voiceNoteTime(line.startMs) + " ")
                write("[${line.language ?: "언어 미확인"}]\n${voiceNoteLineText(shown)}\n\n")
            }
        }
    }
    output.flush()
}

/** User speech must remain visible text, not executable HTML or Markdown links/images. */
private fun String.noteMarkdown(): String = buildString {
    for (character in this@noteMarkdown) when (character) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        in "\\`*_{}[]()#+-.!|" -> append('\\').append(character)
        else -> append(character)
    }
}
