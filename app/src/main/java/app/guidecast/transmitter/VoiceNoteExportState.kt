package app.guidecast.transmitter

import android.os.Bundle
import java.security.MessageDigest

/** Persist only a small request, never a multi-megabyte transcript in Activity saved state. */
internal fun VoiceNoteExportRequest.savedRequest(): Bundle = Bundle().apply {
    putInt("version", 1); putString("id", note.id); putString("revision", voiceNoteExportRevision(note))
    putString("kind", kind); putBoolean("translations", options.translations)
    putBoolean("timestamps", options.timestamps); putBoolean("speakers", options.speakers)
    putString("search", options.search); putBoolean("windows", options.windowsText)
}

/** Disk IO belongs to the caller's IO dispatcher. Changed/deleted notes require a fresh choice. */
internal fun restoreVoiceNoteExport(saved: Bundle, load: (String) -> VoiceNote): VoiceNoteExportRequest {
    val kind = saved.getString("kind")
    if (saved.getInt("version") != 1 || kind !in setOf("txt", "md", "srt", "json", "wav"))
        throw FileTranscriptionException("내려받기 준비가 초기화됐습니다. 저장 위치를 다시 선택하세요.")
    val note = runCatching { load(requireNotNull(saved.getString("id"))) }.getOrNull()
    if (note == null || voiceNoteExportRevision(note) != saved.getString("revision"))
        throw FileTranscriptionException("저장 위치를 고르는 동안 노트가 변경되거나 삭제됐습니다. 내용을 확인한 뒤 다시 내려받으세요.")
    return VoiceNoteExportRequest(note, requireNotNull(kind), VoiceNoteExportOptions(
        saved.getBoolean("translations"), saved.getBoolean("timestamps"), saved.getBoolean("speakers"),
        saved.getString("search"), saved.getBoolean("windows")))
}

internal fun voiceNoteExportRevision(note: VoiceNote): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun part(value: Any?) {
        val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
        val length = bytes?.size ?: -1
        for (shift in 24 downTo 0 step 8) digest.update((length ushr shift).toByte())
        if (bytes != null) digest.update(bytes)
    }
    part(note.id); part(note.title); part(note.createdAt); part(note.sourceLanguage); part(note.targetLanguage)
    part(note.durationMs); part(note.interrupted); part(note.notice); part(note.lines.size)
    note.lines.forEach { line ->
        part(line.startMs); part(line.endMs); part(line.original); part(line.language)
        part(line.translation); part(line.speaker); part(line.edited); part(line.timingEstimated)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
