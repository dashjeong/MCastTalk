package app.guidecast.core.audio

import java.io.Closeable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** 48kHz mono S16LE capture -> RNNoise -> anti-aliased 16kHz mono S16LE. No VAD frame dropping. */
class RnNoiseMicrophoneProcessor(
    private val filter: NoiseFrameFilter = NativeRnNoiseFilter(),
    private val onFailure: (String) -> Unit = {},
) : Closeable {
    private val frame = FloatArray(480)
    private val original = FloatArray(480)
    private val decimator = SpeechDecimator()
    private var samples = 0
    private var lowByte = -1
    private var failed = false
    private var closed = false

    @Synchronized fun process(input: ByteArray): ByteArray {
        check(!closed)
        val availableSamples = (input.size + if (lowByte >= 0) 1 else 0) / 2
        val output = ByteArray((samples + availableSamples) / 480 * 320)
        var written = 0
        for (byte in input) {
            if (lowByte < 0) {
                lowByte = byte.toInt() and 255
            } else {
                frame[samples++] = ((byte.toInt() shl 8) or lowByte).toShort().toFloat()
                lowByte = -1
                if (samples == 480) {
                    if (!failed) {
                        frame.copyInto(original)
                        try {
                            filter.process(frame)
                            check(frame.all { it.isFinite() }) { "AI 소음 감소 출력 형식 오류" }
                        } catch (error: Exception) {
                            failed = true
                            original.copyInto(frame)
                            onFailure("AI 소음 감소 실패 · 원본 마이크로 계속합니다: ${error.javaClass.simpleName}")
                        }
                    }
                    for (value in frame) {
                        decimator.push(value)?.let { sample ->
                            val pcm = sample.roundToInt().coerceIn(-32768, 32767)
                            output[written++] = pcm.toByte()
                            output[written++] = (pcm shr 8).toByte()
                        }
                    }
                    samples = 0
                }
            }
        }
        check(written == output.size)
        return output
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        filter.close()
    }
}

interface NoiseFrameFilter : Closeable {
    fun process(frame: FloatArray)
}

class NativeRnNoiseFilter : NoiseFrameFilter {
    private var handle = create().also { check(it != 0L) { "RNNoise 초기화 실패" } }
    @Synchronized override fun process(frame: FloatArray) {
        check(handle != 0L)
        require(frame.size == 480)
        processFrame(handle, frame)
    }
    @Synchronized override fun close() {
        if (handle != 0L) { destroy(handle); handle = 0 }
    }
    private external fun create(): Long
    private external fun processFrame(handle: Long, frame: FloatArray)
    private external fun destroy(handle: Long)
    companion object {
        init { System.loadLibrary("guidecast_rnnoise") }
    }
}

/** Fixed bounded FIR, preserving phase across JNI blocks and arbitrary AudioRecord chunk sizes. */
internal class SpeechDecimator {
    private val history = FloatArray(TAPS)
    private var cursor = 0
    private var phase = 0
    fun push(sample: Float): Float? {
        history[cursor] = sample
        cursor = (cursor + 1) % TAPS
        if (++phase != 3) return null
        phase = 0
        var value = 0f
        for (i in 0 until TAPS) value += history[(cursor + TAPS - 1 - i) % TAPS] * coefficients[i]
        return value
    }
    companion object {
        private const val TAPS = 63
        // 7kHz passband centre leaves transition room below the output's 8kHz Nyquist limit.
        private val coefficients = FloatArray(TAPS) { i ->
            val x = i - (TAPS - 1) / 2
            val cutoff = 7_000.0 / 48_000
            val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val window = 0.42 - 0.5 * cos(2 * PI * i / (TAPS - 1)) +
                0.08 * cos(4 * PI * i / (TAPS - 1))
            (sinc * window).toFloat()
        }.also { values ->
            val sum = values.sum()
            for (i in values.indices) values[i] /= sum
        }
    }
}
