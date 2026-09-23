package app.guidecast.transmitter

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
)

/** One representation for the screen and both exports. A missing translation stays missing. */
internal fun voiceNoteLineText(line: VoiceNoteLine): String = buildString {
    if (line.speaker.isNotBlank()) append("[${line.speaker.trim()}] ")
    append(line.original.trim())
    if (line.translation.isNotBlank() && line.translation.trim() != line.original.trim()) {
        append("\n(${line.translation.trim()})")
    }
}

internal fun voiceNoteTranscript(title: String, lines: List<VoiceNoteLine>, srt: Boolean): String = buildString {
    if (!srt) append(title).append("\n\n")
    lines.forEachIndexed { index, line ->
        if (srt) append(index + 1).append('\n')
        append(voiceNoteTime(line.startMs, srt))
        if (srt) append(" --> ").append(voiceNoteTime(maxOf(line.endMs, line.startMs + 1), true))
        else append(" [").append(line.language ?: "언어 미확인").append(']')
        val text = voiceNoteLineText(line)
        append('\n').append(if (srt) text.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n[ \\t]*\n+"), "\n") else text).append("\n\n")
    }
}

internal fun voiceNoteTime(ms: Long, milliseconds: Boolean = false): String {
    val value = ms.coerceAtLeast(0)
    val base = String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3_600_000, value / 60_000 % 60, value / 1_000 % 60)
    return if (milliseconds) base + String.format(Locale.ROOT, ",%03d", value % 1_000) else base
}

/** PCM is appended directly to disk. The header is recoverable after process death. */
internal class VoiceNoteWav(file: File) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    var byteCount = 0L
        private set
    init { output.setLength(0); output.write(voiceNoteWavHeader(0)) }
    fun append(bytes: ByteArray, count: Int) {
        require(count in 0..bytes.size && count % 2 == 0)
        require(byteCount + count <= VOICE_NOTE_MAX_BYTES) { "음성노트는 한 번에 최대 60분까지 녹음합니다." }
        output.write(bytes, 0, count)
        byteCount += count
    }
    fun checkpoint() {
        output.seek(0); output.write(voiceNoteWavHeader(byteCount)); output.seek(44 + byteCount)
        output.fd.sync()
    }
    override fun close() { try { checkpoint() } finally { output.close() } }
}

internal fun voiceNoteWavHeader(byteCount: Long): ByteArray {
    require(byteCount in 0..VOICE_NOTE_MAX_BYTES && byteCount % 2 == 0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII)); putInt((36 + byteCount).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(1)
        putInt(VOICE_NOTE_SAMPLE_RATE); putInt(VOICE_NOTE_SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray(Charsets.US_ASCII)); putInt(byteCount.toInt())
    }.array()
}

internal fun recoverVoiceNoteWav(file: File): Long {
    require(file.length() in 44..(44 + VOICE_NOTE_MAX_BYTES))
    val bytes = (file.length() - 44) / 2 * 2
    RandomAccessFile(file, "rw").use {
        it.setLength(44 + bytes); it.seek(0); it.write(voiceNoteWavHeader(bytes)); it.fd.sync()
    }
    return bytes / 32
}
