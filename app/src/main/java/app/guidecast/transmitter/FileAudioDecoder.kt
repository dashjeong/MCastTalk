package app.guidecast.transmitter

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

data class FileAudioInfo(val durationMs: Long?, val sampleRateHz: Int, val channelCount: Int)
data class FilePcmFrame(val bytes: ByteArray, val startMs: Long, val endMs: Long)

/** Local SAF media -> bounded, ordered PCM16 little-endian / mono / 16 kHz. */
object FileAudioDecoder {
    fun decode(
        context: Context,
        uri: Uri,
        onInfo: (FileAudioInfo) -> Unit = {},
    ): Flow<FilePcmFrame> = flow {
        require(uri.scheme == "content" || uri.scheme == "file") { "기기에 저장된 미디어 파일을 선택하세요." }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("선택한 파일에서 음성 트랙을 찾지 못했습니다.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                (format.getLong(MediaFormat.KEY_DURATION) / 1_000L).takeIf { it > 0 }
            } else null
            onInfo(FileAudioInfo(duration, format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)))
            var converter = filePcmConverter(format)
            suspend fun publish(buffer: ByteBuffer, presentationTimeUs: Long) {
                val output = converter.convert(buffer)
                var offset = 0
                while (offset < output.size) {
                    currentCoroutineContext().ensureActive()
                    val end = minOf(offset + 640, output.size) // At most 20 ms per handoff.
                    val startMs = (presentationTimeUs / 1_000L).coerceAtLeast(0) + offset / 32L
                    emit(FilePcmFrame(output.copyOfRange(offset, end), startMs,
                        startMs + (end - offset) / 32L))
                    offset = end
                }
            }
            if (mime == "audio/raw") {
                val buffer = ByteBuffer.allocate(256 * 1_024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) break
                    check(count <= buffer.capacity()) { "음성 프레임이 처리 가능한 크기를 넘었습니다." }
                    buffer.position(0); buffer.limit(count)
                    publish(buffer, extractor.sampleTime)
                    extractor.advance()
                }
            } else {
                val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
                decoder.configure(format, null, null, 0)
                decoder.start(); started = true
                var inputEnded = false
                var outputEnded = false
                var lastProgress = SystemClock.elapsedRealtime()
                val info = MediaCodec.BufferInfo()
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    if (!inputEnded) {
                        val index = decoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(index))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                                    "보호된 음성 트랙은 변환할 수 없습니다."
                                }
                                decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                    }
                    val output = decoder.dequeueOutputBuffer(info, 10_000)
                    when {
                        output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> converter = filePcmConverter(decoder.outputFormat)
                        output >= 0 -> {
                            try {
                                if (info.size > 0) {
                                    check(info.size <= 2 * 1_024 * 1_024) { "음성 디코더 프레임이 너무 큽니다." }
                                    val buffer = requireNotNull(decoder.getOutputBuffer(output)).duplicate()
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    publish(buffer, info.presentationTimeUs)
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                lastProgress = SystemClock.elapsedRealtime()
                            } finally { decoder.releaseOutputBuffer(output, false) }
                        }
                    }
                    check(SystemClock.elapsedRealtime() - lastProgress < 30_000L) {
                        "음성 디코더가 응답하지 않습니다. 다른 형식의 파일로 다시 시도하세요."
                    }
                }
            }
        } finally {
            codec?.let { if (started) runCatching { it.stop() }; runCatching { it.release() } }
            extractor.release()
        }
    }.flowOn(Dispatchers.IO)
}

private fun filePcmConverter(format: MediaFormat) = FilePcmConverter(
    format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    else AudioFormat.ENCODING_PCM_16BIT,
)

/** Fractional box-filter resampling; constant memory and phase continuity across decoder packets. */
internal class FilePcmConverter(
    private val sampleRate: Int,
    private val channels: Int,
    private val encoding: Int,
) {
    private val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        else -> error("지원하지 않는 PCM 형식입니다.")
    }
    private var weight = 0L
    private var weightedSample = 0.0
    init { require(sampleRate in 8_000..192_000); require(channels in 1..8) }

    fun convert(source: ByteBuffer): ByteArray {
        val input = source.slice().order(ByteOrder.LITTLE_ENDIAN)
        require(input.remaining() % (bytesPerSample * channels) == 0) { "불완전한 PCM 프레임입니다." }
        val frames = input.remaining() / (bytesPerSample * channels)
        val output = ByteBuffer.allocate(((frames.toLong() * 16_000 / sampleRate + 2) * 2).toInt())
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            var sample = 0.0
            repeat(channels) {
                sample += when (encoding) {
                    AudioFormat.ENCODING_PCM_8BIT -> ((input.get().toInt() and 255) - 128) / 128.0
                    AudioFormat.ENCODING_PCM_16BIT -> input.short / 32768.0
                    AudioFormat.ENCODING_PCM_32BIT -> input.int / 2147483648.0
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val value = (input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or
                            (input.get().toInt() shl 16)
                        value / 8388608.0
                    }
                    else -> input.float.toDouble().let { if (it.isFinite()) it else 0.0 }
                }
            }
            sample /= channels
            var remaining = 16_000L
            while (remaining > 0) {
                val take = minOf(sampleRate - weight, remaining)
                weightedSample += sample * take
                weight += take; remaining -= take
                if (weight == sampleRate.toLong()) {
                    output.putShort((weightedSample / weight * 32768.0).roundToInt()
                        .coerceIn(-32768, 32767).toShort())
                    weight = 0; weightedSample = 0.0
                }
            }
        }
        return output.array().copyOf(output.position())
    }
}
