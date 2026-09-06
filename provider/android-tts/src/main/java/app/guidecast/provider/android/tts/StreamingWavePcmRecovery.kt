package app.guidecast.provider.android.tts

import android.media.AudioFormat
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Reads Android TTS WAV recovery output without retaining the file, its data chunk and converted
 * PCM at the same time. Only one source chunk, one converted chunk and at most two 20 ms frames
 * are live in this flow, independent of the utterance/file size.
 */
internal fun streamRecoveredWavePcmFrames(
    file: File,
    outputSampleRateHz: Int,
    frameBytes: Int,
    startByteOffset: Int,
    openInput: (File) -> SeekableWaveInput = ::RandomAccessWaveInput,
): Flow<ByteArray> = flow {
    require(outputSampleRateHz > 0)
    require(frameBytes > 0 && frameBytes % Short.SIZE_BYTES == 0)
    require(startByteOffset >= 0 && startByteOffset % Short.SIZE_BYTES == 0) {
        "PCM recovery offset is invalid"
    }

    openInput(file).use { input ->
        val data = parseWaveData(input)
        val converter = PcmStreamConverter(
            inputSampleRateHz = data.format.sampleRateHz,
            inputEncoding = data.format.encoding,
            inputChannelCount = data.format.channelCount,
            outputSampleRateHz = outputSampleRateHz,
        )
        input.seek(data.offset)

        val sourceBuffer = ByteArray(STREAM_READ_BYTES)
        var sourceBytesRemaining = data.size
        var convertedBytesSeen = 0L
        var skipBytesRemaining = startByteOffset.toLong()
        var frame = ByteArray(frameBytes)
        var frameSize = 0

        while (sourceBytesRemaining > 0L) {
            val readSize = minOf(sourceBuffer.size.toLong(), sourceBytesRemaining).toInt()
            input.readFully(sourceBuffer, 0, readSize)
            sourceBytesRemaining -= readSize

            // PcmStreamConverter consumes the supplied array synchronously and retains only an
            // incomplete input sample frame. Reuse the fixed source buffer for every full read.
            val source = if (readSize == sourceBuffer.size) {
                sourceBuffer
            } else {
                sourceBuffer.copyOf(readSize)
            }
            val converted = converter.convert(source)
            convertedBytesSeen += converted.size

            var offset = 0
            if (skipBytesRemaining > 0L) {
                val skipped = minOf(skipBytesRemaining, converted.size.toLong()).toInt()
                skipBytesRemaining -= skipped
                offset += skipped
            }
            while (offset < converted.size) {
                val copied = minOf(frameBytes - frameSize, converted.size - offset)
                converted.copyInto(
                    destination = frame,
                    destinationOffset = frameSize,
                    startIndex = offset,
                    endIndex = offset + copied,
                )
                frameSize += copied
                offset += copied
                if (frameSize == frameBytes) {
                    emit(frame)
                    frame = ByteArray(frameBytes)
                    frameSize = 0
                }
            }
        }

        check(convertedBytesSeen > 0L) { "Android TTS WAV contains no PCM" }
        check(skipBytesRemaining == 0L) {
            "Android TTS callback PCM exceeds synthesized WAV output"
        }
        if (frameSize > 0) emit(frame) // The unused suffix is already zero padded.
    }
}
    // File IO and conversion stay off the pipeline collector. Capacity one prevents flowOn's
    // default 64-element prefetch from retaining seconds of recovered speech.
    .buffer(capacity = 1)
    .flowOn(Dispatchers.IO)

internal interface SeekableWaveInput : Closeable {
    val length: Long
    fun seek(position: Long)
    fun readFully(destination: ByteArray, offset: Int, length: Int)
}

private class RandomAccessWaveInput(file: File) : SeekableWaveInput {
    private val input = RandomAccessFile(file, "r")
    override val length: Long get() = input.length()
    override fun seek(position: Long) = input.seek(position)
    override fun readFully(destination: ByteArray, offset: Int, length: Int) =
        input.readFully(destination, offset, length)

    override fun close() = input.close()
}

private data class StreamingWaveFormat(
    val channelCount: Int,
    val sampleRateHz: Int,
    val encoding: Int,
)

private data class StreamingWaveData(
    val format: StreamingWaveFormat,
    val offset: Long,
    val size: Long,
)

private fun parseWaveData(input: SeekableWaveInput): StreamingWaveData {
    check(input.length in 1..MAX_ANDROID_TTS_FILE_BYTES) {
        "Android TTS output file is missing or too large"
    }
    require(input.length >= RIFF_HEADER_BYTES) { "Android TTS fallback output is not a WAV file" }

    val riff = ByteArray(RIFF_HEADER_BYTES)
    input.seek(0L)
    input.readFully(riff, 0, riff.size)
    require(riff.matchesAscii(0, "RIFF") && riff.matchesAscii(8, "WAVE")) {
        "Android TTS fallback output is not a WAV file"
    }

    val chunkHeader = ByteArray(CHUNK_HEADER_BYTES)
    var offset = RIFF_HEADER_BYTES.toLong()
    var format: StreamingWaveFormat? = null
    var dataOffset: Long? = null
    var dataSize: Long? = null
    var chunkCount = 0
    while (offset + CHUNK_HEADER_BYTES <= input.length) {
        check(++chunkCount <= MAX_WAVE_CHUNKS) { "Android TTS WAV contains too many chunks" }
        input.seek(offset)
        input.readFully(chunkHeader, 0, chunkHeader.size)
        val chunkSize = chunkHeader.littleEndianInt(4)
        require(chunkSize >= 0) { "Android TTS WAV chunk size is invalid" }
        val contentOffset = offset + CHUNK_HEADER_BYTES
        val contentEnd = contentOffset + chunkSize.toLong()
        require(contentEnd <= input.length) { "Android TTS WAV chunk is truncated" }

        when {
            chunkHeader.matchesAscii(0, "fmt ") -> {
                require(chunkSize >= MIN_WAVE_FORMAT_BYTES) {
                    "Android TTS WAV format is truncated"
                }
                val rawFormat = ByteArray(MIN_WAVE_FORMAT_BYTES)
                input.seek(contentOffset)
                input.readFully(rawFormat, 0, rawFormat.size)
                val code = rawFormat.littleEndianShort(0)
                val channelCount = rawFormat.littleEndianShort(2)
                val sampleRateHz = rawFormat.littleEndianInt(4)
                val bitsPerSample = rawFormat.littleEndianShort(14)
                val encoding = when {
                    code == WAVE_FORMAT_PCM && bitsPerSample == 8 ->
                        AudioFormat.ENCODING_PCM_8BIT
                    code == WAVE_FORMAT_PCM && bitsPerSample == 16 ->
                        AudioFormat.ENCODING_PCM_16BIT
                    code == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32 ->
                        AudioFormat.ENCODING_PCM_FLOAT
                    else -> error(
                        "Unsupported Android TTS WAV format: " +
                            "code=$code, bits=$bitsPerSample",
                    )
                }
                require(channelCount in 1..2) { "Unsupported Android TTS WAV channel count" }
                require(sampleRateHz in MIN_TTS_SAMPLE_RATE_HZ..MAX_TTS_SAMPLE_RATE_HZ) {
                    "Unsupported Android TTS WAV sample rate"
                }
                format = StreamingWaveFormat(channelCount, sampleRateHz, encoding)
            }

            chunkHeader.matchesAscii(0, "data") -> {
                dataOffset = contentOffset
                dataSize = chunkSize.toLong()
            }
        }
        offset = contentEnd + (chunkSize and 1)
    }

    return StreamingWaveData(
        format = requireNotNull(format) { "Android TTS WAV format chunk is missing" },
        offset = requireNotNull(dataOffset) { "Android TTS WAV data chunk is missing" },
        size = requireNotNull(dataSize) { "Android TTS WAV data chunk is missing" },
    )
}

private fun ByteArray.littleEndianShort(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.littleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        (this[offset + 3].toInt() shl 24)

private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
    offset >= 0 && offset + value.length <= size && value.indices.all { index ->
        this[offset + index].toInt() and 0xff == value[index].code
    }

internal const val MAX_ANDROID_TTS_FILE_BYTES = 32L * 1024 * 1024
internal const val STREAM_READ_BYTES = 16 * 1024
private const val RIFF_HEADER_BYTES = 12
private const val CHUNK_HEADER_BYTES = 8
private const val MIN_WAVE_FORMAT_BYTES = 16
private const val MAX_WAVE_CHUNKS = 256
private const val MIN_TTS_SAMPLE_RATE_HZ = 8_000
private const val MAX_TTS_SAMPLE_RATE_HZ = 48_000
private const val WAVE_FORMAT_PCM = 1
private const val WAVE_FORMAT_IEEE_FLOAT = 3
