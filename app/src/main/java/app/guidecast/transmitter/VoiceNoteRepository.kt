package app.guidecast.transmitter

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class VoiceNote(
    val id: String,
    val title: String,
    val createdAt: Long,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val durationMs: Long = 0,
    val interrupted: Boolean = true,
    val lines: List<VoiceNoteLine> = emptyList(),
    val notice: String? = null,
)

/** App-private audio and atomic metadata; never part of broadcast or diagnostic exports. */
internal class VoiceNoteRepository(private val root: File) {
    init { check(root.isDirectory || root.mkdirs()) }
    private fun file(id: String, suffix: String): File {
        require(Regex("[0-9a-f-]{36}").matches(id) && UUID.fromString(id).toString() == id)
        return File(root, "$id.$suffix")
    }
    fun audio(id: String): File = file(id, "wav")
    fun create(title: String, source: String?, target: String): VoiceNote {
        check(root.listFiles { f -> f.extension == "json" }.orEmpty().size < 500) { "음성노트 보관함이 가득 찼습니다. 필요 없는 노트를 삭제하세요." }
        check(root.usableSpace >= 32L * 1024 * 1024) { "녹음을 시작할 저장 공간이 부족합니다." }
        return VoiceNote(UUID.randomUUID().toString(), title.trim().take(120).ifBlank { "음성노트" },
            System.currentTimeMillis(), source, target).also(::save)
    }
    @Synchronized fun save(note: VoiceNote) {
        require(note.title.length <= 120 && note.lines.size <= 2_000)
        require(note.sourceLanguage == null || note.sourceLanguage in VOICE_NOTE_LANGUAGES)
        require(note.targetLanguage in VOICE_NOTE_LANGUAGES)
        require(note.lines.all { it.original.length <= 65_536 && it.translation.length <= 65_536 && it.speaker.length <= 80 })
        val json = JSONObject().apply {
            put("version", 1); put("id", note.id); put("title", note.title); put("created", note.createdAt)
            put("source", note.sourceLanguage ?: JSONObject.NULL); put("target", note.targetLanguage)
            put("duration", note.durationMs); put("interrupted", note.interrupted)
            put("notice", note.notice ?: JSONObject.NULL)
            put("lines", JSONArray().apply { note.lines.forEach { line -> put(JSONObject().apply {
                put("start", line.startMs); put("end", line.endMs); put("original", line.original)
                put("language", line.language ?: JSONObject.NULL); put("translation", line.translation); put("speaker", line.speaker)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        require(json.size <= 4 * 1024 * 1024) { "노트 크기 한도를 넘었습니다. 녹음은 그대로 보관됩니다." }
        val atomic = AtomicFile(file(note.id, "json"))
        val output = atomic.startWrite()
        try { output.write(json); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    @Synchronized fun load(id: String): VoiceNote {
        val atomic = AtomicFile(file(id, "json"))
        val value = atomic.openRead().use { input ->
            require(input.channel.size() <= 4 * 1024 * 1024)
            val bytes = input.readBytes()
            require(bytes.size <= 4 * 1024 * 1024)
            JSONObject(String(bytes, Charsets.UTF_8))
        }
        require(value.getInt("version") == 1 && value.getString("id") == id)
        val rows = value.getJSONArray("lines")
        require(rows.length() <= 2_000)
        return VoiceNote(id, value.getString("title"), value.getLong("created"),
            value.optionalString("source"), value.getString("target"), value.getLong("duration"),
            value.getBoolean("interrupted"), List(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                VoiceNoteLine(row.getLong("start"), row.getLong("end"), row.getString("original"),
                    row.optionalString("language"), row.optString("translation"), row.optString("speaker"))
            }, value.optionalString("notice"))
    }
    /** Read only summaries into the library screen. Interrupted audio can be recovered explicitly. */
    @Synchronized fun list(): List<VoiceNote> = root.listFiles { f -> f.extension == "json" || f.name.endsWith(".json.bak") }
        .orEmpty().map { it.name.substringBefore(".json") }.distinct().mapNotNull { id ->
            runCatching { load(id).copy(lines = emptyList()) }.getOrNull()
        }.sortedByDescending { it.createdAt }

    fun recover(id: String): VoiceNote {
        val note = load(id)
        if (!note.interrupted) return note
        val duration = recoverVoiceNoteWav(audio(id))
        return note.copy(durationMs = duration, interrupted = false,
            notice = "중단된 녹음에서 저장된 음성을 복구했습니다. 끝부분을 확인하세요.").also(::save)
    }
    @Synchronized fun delete(id: String) {
        val audio = audio(id)
        check(!audio.exists() || audio.delete()) { "녹음을 삭제하지 못했습니다." }
        AtomicFile(file(id, "json")).delete()
    }
    private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
}
