package app.guidecast.provider.android.tts

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class PcmStreamConverter(
    private val inputSampleRateHz: Int,
    private val inputEncoding: Int,
    private val inputChannelCount: Int,
    private val outputSampleRateHz: Int,
) {
    init {
        require(inputSampleRateHz > 0)
        require(outputSampleRateHz > 0)
        require(inputChannelCount in 1..2)
        require(inputEncoding in SUPPORTED_ENCODINGS)
    }

    private val bytesPerSample = when (inputEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> error("Unsupported PCM encoding: $inputEncoding")
    }
    private val bytesPerFrame = bytesPerSample * inputChannelCount
    private var remainder = ByteArray(0)
    private var inputFramesSeen = 0L
    private var outputFramesEmitted = 0L
    private var prevMixed = 0f
    private var hasPrevMixed = false

    fun convert(chunk: ByteArray): ByteArray {
        val input = if (remainder.isEmpty()) chunk else remainder + chunk
        val completeBytes = input.size - (input.size % bytesPerFrame)
        remainder = if (completeBytes == input.size) EMPTY_PCM else input.copyOfRange(completeBytes, input.size)
        if (completeBytes == 0) return EMPTY_PCM

        // The common TTS format needs no decoding or resampling. Keep one owned copy:
        // callers may reuse their callback buffer, so returning chunk itself is unsafe.
        // Previously this allocated two ByteBuffers and copied through a second PCM array.
        if (inputSampleRateHz == outputSampleRateHz && inputChannelCount == 1 &&
            inputEncoding == AudioFormat.ENCODING_PCM_16BIT
        ) return input.copyOf(completeBytes)

        val source = ByteBuffer.wrap(input, 0, completeBytes).order(ByteOrder.LITTLE_ENDIAN)
        val estimatedFrames = ((completeBytes / bytesPerFrame.toLong()) * outputSampleRateHz /
            inputSampleRateHz + 4).toInt()
        val output = ByteBuffer.allocate(estimatedFrames * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        while (source.remaining() >= bytesPerFrame) {
            var mixed = 0f
            repeat(inputChannelCount) { mixed += source.readNormalizedSample() }
            mixed /= inputChannelCount
            inputFramesSeen += 1

            if (!hasPrevMixed) {
                prevMixed = mixed
                hasPrevMixed = true
            }

            val shouldHaveEmitted = inputFramesSeen * outputSampleRateHz / inputSampleRateHz
            while (outputFramesEmitted < shouldHaveEmitted) {
                val sampleIndexInInputTime = (outputFramesEmitted + 1).toDouble() * inputSampleRateHz / outputSampleRateHz
                val fraction = (sampleIndexInInputTime - (inputFramesSeen - 1)).coerceIn(0.0, 1.0).toFloat()
                val interpolated = prevMixed + (mixed - prevMixed) * fraction
                val pcm16 = (interpolated.coerceIn(-1f, 1f) * 32_768f).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                output.putShort(pcm16)
                outputFramesEmitted += 1
            }
            prevMixed = mixed
        }

        return output.array().copyOf(output.position())
    }

    private fun ByteBuffer.readNormalizedSample(): Float = when (inputEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ((get().toInt() and 0xFF) - 128) / 128f
        AudioFormat.ENCODING_PCM_16BIT -> short / 32768f
        AudioFormat.ENCODING_PCM_FLOAT -> float
        else -> error("Unsupported PCM encoding: $inputEncoding")
    }

    private companion object {
        val EMPTY_PCM = ByteArray(0)
        val SUPPORTED_ENCODINGS = setOf(
            AudioFormat.ENCODING_PCM_8BIT,
            AudioFormat.ENCODING_PCM_16BIT,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
    }
}
