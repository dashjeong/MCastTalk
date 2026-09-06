package app.guidecast.core.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

data class PcmSignalStats(
    val rms: Float,
    val peak: Float,
    val nonZeroSamples: Int,
    val sampleCount: Int,
)

fun ByteArray.pcmS16LeSignalStats(): PcmSignalStats {
    val evenSize = size - (size % 2)
    val sampleCount = evenSize / 2
    if (sampleCount == 0) return PcmSignalStats(0f, 0f, 0, 0)

    var sumSquares = 0.0
    var peak = 0
    var nonZeroSamples = 0
    var offset = 0
    while (offset < evenSize) {
        val sample = ((this[offset + 1].toInt() shl 8) or (this[offset].toInt() and 0xff)).toShort().toInt()
        val magnitude = if (sample == Short.MIN_VALUE.toInt()) 32_768 else kotlin.math.abs(sample)
        if (sample != 0) nonZeroSamples += 1
        if (magnitude > peak) peak = magnitude
        sumSquares += sample.toDouble() * sample.toDouble()
        offset += 2
    }
    return PcmSignalStats(
        rms = (sqrt(sumSquares / sampleCount) / 32_768.0).toFloat(),
        peak = peak / 32_768f,
        nonZeroSamples = nonZeroSamples,
        sampleCount = sampleCount,
    )
}

class PcmSineWaveGenerator(
    private val sampleRateHz: Int,
    private val frequencyHz: Double,
    private val amplitude: Double = 0.5,
) {
    private var sampleIndex = 0L

    init {
        require(sampleRateHz > 0)
        require(frequencyHz > 0.0 && frequencyHz < sampleRateHz / 2.0)
        require(amplitude in 0.0..1.0)
    }

    fun nextFrame(durationMillis: Int): ByteArray {
        require(durationMillis > 0)
        val sampleCount = sampleRateHz * durationMillis / 1_000
        return ByteArray(sampleCount * 2).also { output ->
            repeat(sampleCount) { localIndex ->
                val phase = 2.0 * PI * frequencyHz * sampleIndex / sampleRateHz
                val sample = (sin(phase) * amplitude * Short.MAX_VALUE).toInt().toShort().toInt()
                output[localIndex * 2] = (sample and 0xff).toByte()
                output[localIndex * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                sampleIndex += 1
            }
        }
    }
}
