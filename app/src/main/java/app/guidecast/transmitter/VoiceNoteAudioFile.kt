package app.guidecast.transmitter

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val actual = ByteArray(44)
        it.readFully(actual)
        val expected = voiceNoteWavHeader(0)
        // Only the RIFF/data lengths may be stale after a process interruption.
        // Do not relabel foreign/corrupt PCM or truncate its trailing byte as a repair.
        require((0 until 4).all { index -> actual[index] == expected[index] } &&
            (8 until 40).all { index -> actual[index] == expected[index] }) {
            "녹음 형식이 손상됐습니다. 원본 파일은 변경하지 않았습니다."
        }
        it.setLength(44 + bytes); it.seek(0); it.write(voiceNoteWavHeader(bytes)); it.fd.sync()
    }
    return bytes / 32
}
