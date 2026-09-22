package app.guidecast.transmitter

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** Portable records contain user content only; paths, credentials and diagnostic notices stay local. */
internal object VoiceNoteTransfer {
    const val NOTE = "voiceNote"
    const val SEGMENT = "voiceNoteSegment"
    private const val MAX_TIME = 3_600_000L
    fun id(value: String): String = value.also {
        require(it.length == 36 && UUID.fromString(it).toString() == it) { "노트 식별자가 올바르지 않습니다." }
    }
    fun audioId(path: String): String? = if (path.startsWith("voice-notes/") && path.endsWith(".wav"))
        runCatching { id(path.removePrefix("voice-notes/").removeSuffix(".wav")) }.getOrNull() else null

    fun validate(row: JSONObject): DataTransferFormat.RecordKey {
        return when (row.getString("type")) {
            NOTE -> {
                val note = note(row, emptyList())
                val bytes = row.getLong("audioBytes")
                require(bytes == 0L && note.interrupted || bytes in 44..(44 + VOICE_NOTE_MAX_BYTES) && bytes % 2 == 0L)
                require(if (bytes == 0L) row.getString("audioSha256").isEmpty()
                    else row.getString("audioSha256").matches(Regex("[a-f0-9]{64}")))
                DataTransferFormat.RecordKey(NOTE, note.id)
            }
            SEGMENT -> {
                val parent = id(row.getString("note"))
                val ordinal = row.getInt("ordinal").also { require(it in 0 until 2_000) }
                val line = line(row)
                DataTransferFormat.RecordKey(SEGMENT, "$parent:$ordinal", parent = parent, ordinal = ordinal,
                    contentChars = line.original.length.toLong() + line.translation.length + line.speaker.length)
            }
            else -> error("지원하지 않는 노트 레코드입니다.")
        }
    }

    private fun text(value: String, maximum: Int): String = value.also {
        require(it.length <= maximum && it.none { c -> c == '\u0000' || c.code < 32 && c !in "\n\r\t" })
    }
    private fun language(value: String?): String? = value.also { require(it == null || it in VOICE_NOTE_LANGUAGES) }
    fun note(row: JSONObject, lines: List<VoiceNoteLine>) = VoiceNote(
        id(row.getString("id")), text(row.getString("title"), 120),
        row.getLong("created").also { require(it in 0..253_402_300_799_999L) },
        language(row.nullablePortableString("source")), requireNotNull(language(row.getString("target"))),
        row.getLong("duration").also { require(it in 0..MAX_TIME) }, row.getBoolean("interrupted"), lines,
    )
    fun line(row: JSONObject): VoiceNoteLine {
        val start = row.getLong("start").also { require(it in 0..MAX_TIME) }
        val end = row.getLong("end").also { require(it in start..MAX_TIME) }
        return VoiceNoteLine(start, end, text(row.getString("original"), 65_536),
            row.nullablePortableString("language")?.also { require(it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}"))) }, text(row.getString("translation"), 65_536),
            text(row.getString("speaker"), 80), row.getBoolean("edited"), row.getBoolean("timingEstimated"))
    }

    fun export(repository: VoiceNoteRepository, emit: (JSONObject) -> Unit, checkActive: () -> Unit): List<Pair<File, String>> {
        val audio = mutableListOf<Pair<File, String>>()
        repository.portableIds().forEach { id ->
            checkActive()
            val note = repository.load(id)
            val file = repository.audio(id)
            val bytes = if (file.exists()) file.length() else 0L
            val hash = if (bytes > 0) { validateAudio(file, note); file.inputStream().use { copy(it, null, checkActive) } } else ""
            emit(JSONObject().put("type", NOTE).put("id", id).put("title", note.title).put("created", note.createdAt)
                .put("source", note.sourceLanguage ?: JSONObject.NULL).put("target", note.targetLanguage)
                .put("duration", note.durationMs).put("interrupted", note.interrupted)
                .put("audioBytes", bytes).put("audioSha256", hash))
            note.lines.forEachIndexed { index, line ->
                emit(JSONObject().put("type", SEGMENT).put("note", id).put("ordinal", index)
                    .put("start", line.startMs).put("end", line.endMs).put("original", line.original)
                    .put("language", line.language ?: JSONObject.NULL).put("translation", line.translation)
                    .put("speaker", line.speaker).put("edited", line.edited).put("timingEstimated", line.timingEstimated))
            }
            if (bytes > 0) audio += file to hash
        }
        return audio
    }

    fun validateAudio(file: File, note: VoiceNote) {
        val bytes = file.length()
        require(bytes in 44..(44 + VOICE_NOTE_MAX_BYTES) && bytes % 2 == 0L) { "녹음 파일 크기가 올바르지 않습니다." }
        val header = ByteArray(44)
        java.io.DataInputStream(file.inputStream()).use { it.readFully(header) }
        val expected = voiceNoteWavHeader(bytes - 44)
        require(header.indices.all { note.interrupted && (it in 4..7 || it in 40..43) || header[it] == expected[it] }) {
            "녹음 형식이 손상됐습니다. 기존 자료는 변경하지 않았습니다."
        }
        if (!note.interrupted) require(note.durationMs == (bytes - 44) / 32) { "녹음과 노트의 재생 시간이 다릅니다." }
    }

    /** Bounded copy/hash without loading an audio file into the heap. */
    fun copy(input: InputStream, output: OutputStream?, checkActive: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65_536)
        var count = 0L
        while (true) {
            checkActive()
            val size = input.read(buffer)
            if (size < 0) break
            count += size
            require(count <= 44 + VOICE_NOTE_MAX_BYTES) { "녹음이 60분 용량을 초과합니다." }
            digest.update(buffer, 0, size); output?.write(buffer, 0, size)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
