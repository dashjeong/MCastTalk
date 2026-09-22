package app.guidecast.transmitter

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
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
    /** Strict backup enumeration: corrupt notes must fail backup, never silently disappear. */
    @Synchronized fun portableIds(): List<String> = root.listFiles { f -> f.extension == "json" || f.name.endsWith(".json.bak") }
        .orEmpty().map { it.name.substringBefore(".json") }.distinct().sorted().also { ids ->
            require(ids.size <= 500); ids.forEach { file(it, "json") }
        }
    private fun contains(id: String) = listOf("json", "json.bak", "json.new").any { file(id, it).exists() }
    @Synchronized fun validateImport(staged: VoiceNoteRepository) {
        val incoming = staged.portableIds().filterNot(::contains)
        require(portableIds().size + incoming.size <= 500) { "음성노트 보관 한도 500개를 초과합니다. 기존 노트를 정리한 뒤 다시 가져오세요." }
        val bytes = incoming.sumOf { staged.audio(it).length() + staged.file(it, "json").length() }
        require(root.usableSpace >= bytes + 8L * 1024 * 1024) { "녹톡 원음을 복원할 저장 공간이 부족합니다." }
        incoming.forEach { id ->
            // An orphan from an interrupted earlier import may be reused only if byte-identical.
            if (audio(id).exists()) require(staged.audio(id).isFile && audio(id).length() == staged.audio(id).length() &&
                audio(id).inputStream().use { VoiceNoteTransfer.copy(it, null) {} } ==
                staged.audio(id).inputStream().use { VoiceNoteTransfer.copy(it, null) {} }) { "같은 식별자의 다른 녹음이 있어 복원을 중지했습니다." }
        }
    }
    @Synchronized fun importMissing(staged: VoiceNoteRepository, checkActive: () -> Unit): Long {
        validateImport(staged)
        var inserted = 0L
        staged.portableIds().forEach { id ->
            checkActive()
            if (contains(id)) return@forEach
            val note = staged.load(id)
            val destination = audio(id)
            var createdAudio = false
            try {
                if (staged.audio(id).isFile && !destination.exists()) {
                    val temporary = File.createTempFile("restore-", ".tmp", root)
                    try {
                        temporary.outputStream().use { output ->
                            staged.audio(id).inputStream().use { VoiceNoteTransfer.copy(it, output, checkActive) }
                            output.fd.sync()
                        }
                        checkActive()
                        check(temporary.renameTo(destination)) { "녹음을 저장하지 못했습니다." }
                        createdAudio = true
                    } finally { temporary.delete() }
                }
                checkActive()
                save(note)
                inserted++
            } catch (error: Throwable) {
                if (createdAudio) destination.delete()
                throw error
            }
        }
        return inserted
    }
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
        // Reject obviously oversized input before allocating its JSON representation.
        val textCharacters = note.lines.sumOf { it.original.length.toLong() + it.translation.length + it.speaker.length }
        if (textCharacters > 4 * 1024 * 1024) throw noteSizeLimit()
        val json = JSONObject().apply {
            put("version", 1); put("id", note.id); put("title", note.title); put("created", note.createdAt)
            put("source", note.sourceLanguage ?: JSONObject.NULL); put("target", note.targetLanguage)
            put("duration", note.durationMs); put("interrupted", note.interrupted)
            put("notice", note.notice ?: JSONObject.NULL)
            put("lines", JSONArray().apply { note.lines.forEach { line -> put(JSONObject().apply {
                put("start", line.startMs); put("end", line.endMs); put("original", line.original)
                put("language", line.language ?: JSONObject.NULL); put("translation", line.translation); put("speaker", line.speaker)
                put("edited", line.edited)
                put("timingEstimated", line.timingEstimated)
            }) } })
        }.toString().toByteArray(Charsets.UTF_8)
        if (json.size > 4 * 1024 * 1024) throw noteSizeLimit()
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
                    row.optionalString("language"), row.optString("translation"), row.optString("speaker"), row.optBoolean("edited"),
                    row.optBoolean("timingEstimated", true))
            }, value.optionalString("notice"))
    }
    /** Read only summaries into the library screen. Interrupted audio can be recovered explicitly. */
    @Synchronized fun list(): List<VoiceNote> = root.listFiles { f -> f.extension == "json" || f.name.endsWith(".json.bak") }
        .orEmpty().map { it.name.substringBefore(".json") }.distinct().mapNotNull { id ->
            runCatching { summary(id) }.getOrNull()
        }.sortedByDescending { it.createdAt }

    /** Skip transcript values without materializing a JSONObject or thousands of line objects. */
    private fun summary(id: String): VoiceNote = AtomicFile(file(id, "json")).openRead().use { input ->
        require(input.channel.size() <= 4 * 1024 * 1024)
        JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
            var version = 0; var storedId: String? = null; var title: String? = null
            var created: Long? = null; var duration: Long? = null; var interrupted: Boolean? = null
            var source: String? = null; var target: String? = null
            val fields = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field))
                when (field) {
                    "version" -> version = reader.nextInt()
                    "id" -> storedId = reader.nextString()
                    "title" -> title = reader.nextString()
                    "created" -> created = reader.nextLong()
                    "duration" -> duration = reader.nextLong()
                    "interrupted" -> interrupted = reader.nextBoolean()
                    "source" -> if (reader.peek() == JsonToken.NULL) reader.nextNull() else source = reader.nextString()
                    "target" -> target = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && version == 1 && storedId == id)
            require(title != null && title.length <= 120 && target in VOICE_NOTE_LANGUAGES)
            require(source == null || source in VOICE_NOTE_LANGUAGES)
            VoiceNote(id, requireNotNull(title), requireNotNull(created), source, requireNotNull(target),
                requireNotNull(duration), requireNotNull(interrupted))
        }
    }

    fun recover(id: String): VoiceNote {
        val note = load(id)
        if (!note.interrupted) return note
        val duration = recoverVoiceNoteWav(audio(id))
        return note.copy(durationMs = duration, interrupted = false,
            notice = "중단된 녹음에서 저장된 음성을 복구했습니다. 끝부분을 확인하세요.").also(::save)
    }
    @Synchronized fun delete(id: String) {
        val audio = audio(id)
        if (audio.exists() && !audio.delete()) throw FileTranscriptionException("녹음을 삭제하지 못했습니다. 다른 작업을 마친 뒤 다시 삭제하세요. 노트 내용은 보관되어 있습니다.")
        AtomicFile(file(id, "json")).delete()
        if (listOf("json", "json.bak", "json.new").any { file(id, it).exists() }) {
            throw FileTranscriptionException("녹음 파일은 삭제했지만 노트 내용을 지우지 못했습니다. 기기 상태를 확인한 뒤 다시 삭제하세요.")
        }
    }
    private fun noteSizeLimit() = FileTranscriptionException("노트가 4MB 크기 한도를 넘었습니다. 문장을 줄이거나 새 노트로 나눠 주세요. 기존 내용과 녹음은 보관되어 있습니다.")
    private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
}
