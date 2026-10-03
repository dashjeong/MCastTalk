package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

/**
 * Streams recorded PCM frames from disk to the speech recognition engine.
 * The audio recording loop writes directly to the WAV file without blocking.
 * A committed cursor tracks available audio bytes on disk and delivers them sequentially
 * to recognition. If recognition is slow, frames are not dropped and the queue is not exhausted;
 * instead, the cursor continues reading committed audio from disk at the recognizer's pace.
 */
internal class VoiceNoteDiskAudioStream(
    private val file: File,
    private val startedAtNanos: Long,
    private val chunkSize: Int = 3_200,
) : AutoCloseable {
    init {
        require(chunkSize > 0) { "chunkSize must be positive: $chunkSize" }
        require(chunkSize % 2 == 0) { "chunkSize must be an even number of bytes for 16-bit PCM: $chunkSize" }
    }

    private val committedBytes = AtomicLong(0L)
    private val deliveredBytes = AtomicLong(0L)
    private val writerDone = AtomicBoolean(false)
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val collected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * The audio duration (in ms) delivered so far to the speech recognition engine.
     * Calculated directly from the committed audio byte offset read by the recognizer:
     * 16,000 Hz, 16-bit mono = 32 bytes per millisecond.
     */
    fun deliveredDurationMs(): Long = deliveredBytes.get() / 32L

    /** Called by the audio recording loop when new bytes have been appended to disk. Never blocks. */
    fun onBytesCommitted(bytesWritten: Long) {
        if (closed.get()) return
        require(bytesWritten >= 0L) { "bytesWritten must not be negative: $bytesWritten" }
        require(bytesWritten % 2L == 0L) { "bytesWritten must be an even number of bytes: $bytesWritten" }
        committedBytes.updateAndGet { current ->
            require(bytesWritten >= current) {
                "committedBytes cannot move backwards: current=$current, new=$bytesWritten"
            }
            bytesWritten
        }
        signal.trySend(Unit)
    }

    /** Called by the audio recording loop when recording has finished. Never blocks. */
    fun finishWriting() {
        writerDone.set(true)
        signal.trySend(Unit)
    }

    /**
     * Consumed by SpeechRecognitionEngine.
     * Enforces single collector.
     * Sequentially reads from disk up to committedBytes at the consumer's pace.
     * Timestamps reflect the exact original audio position:
     * 16000 Hz, 16-bit mono = 32,000 bytes/sec -> 31,250 nanoseconds per byte.
     */
    fun flow(): Flow<PcmAudioFrame> = flow {
        check(collected.compareAndSet(false, true)) { "Only one recognition collector permitted" }
        var readOffset = 0L
        val buffer = ByteArray(chunkSize)

        // Wait until file is created or writer is already done
        while (!file.exists() && !writerDone.get() && !closed.get()) {
            currentCoroutineContext().ensureActive()
            delay(10)
        }
        if (!file.exists() && committedBytes.get() == 0L) return@flow

        RandomAccessFile(file, "r").use { reader ->
            while (!closed.get()) {
                currentCoroutineContext().ensureActive()
                val committed = committedBytes.get()
                val fileLength = reader.length()

                if (fileLength < 44L + committed) {
                    throw java.io.IOException(
                        "Voice note audio file truncated or corrupt: length=$fileLength, expected at least ${44L + committed}"
                    )
                }

                if (readOffset < committed) {
                    val toRead = minOf(chunkSize.toLong(), committed - readOffset).toInt()
                    val targetPos = 44L + readOffset
                    if (reader.filePointer != targetPos) {
                        reader.seek(targetPos)
                    }
                    reader.readFully(buffer, 0, toRead)
                    val frameBytes = buffer.copyOf(toRead)
                    val frameTimestamp = startedAtNanos + readOffset * 31_250L
                    readOffset += toRead
                    deliveredBytes.set(readOffset)
                    emit(PcmAudioFrame(frameBytes, frameTimestamp))
                } else {
                    // readOffset >= committed
                    // When writerDone is observed, re-read committedBytes to eliminate race condition
                    // where writer committed final bytes right as or after writerDone became true.
                    if (writerDone.get()) {
                        val latestCommitted = committedBytes.get()
                        if (readOffset >= latestCommitted) {
                            break
                        }
                        continue
                    }
                    // Wait for writer notification or close
                    val result = signal.receiveCatching()
                    if (result.isClosed) {
                        val latestCommitted = committedBytes.get()
                        if (readOffset >= latestCommitted) break
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            writerDone.set(true)
            signal.close()
        }
    }
}
