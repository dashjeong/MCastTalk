package app.guidecast.transmitter

import app.guidecast.core.audio.pcmS16LeSignalStats
import kotlin.math.max

/**
 * Low-cost PCM speech/pause hint for interpretation segmentation.
 *
 * This is intentionally conservative: it uses an adaptive noise floor, attack hysteresis and a
 * release hangover. It never discards audio or decides transcript text; Android/Moonshine remains
 * authoritative. A false positive merely delays a pause-based commit until the deadline path.
 */
internal data class SpeechActivityObservation(
    val active: Boolean,
    val lastSpeechAtNanos: Long?,
)

internal class AdaptiveSpeechActivityDetector(
    private val minimumRmsThreshold: Float = 0.004f,
    private val minimumPeakThreshold: Float = 0.010f,
    private val noiseRmsRatio: Float = 2.8f,
    private val noisePeakRatio: Float = 6.0f,
    private val maximumSpeechZeroCrossingRatio: Float = 0.35f,
    private val maximumSpeechCrestFactor: Float = 10f,
    private val minimumActiveSampleRatio: Float = 0.10f,
    private val attackFrames: Int = 3,
    private val releaseMillis: Long = 320L,
) {
    private var noiseFloorRms = 0.002f
    private var consecutiveSpeechFrames = 0
    private var speechActive = false
    private var lastProbableSpeechNanos: Long? = null

    init {
        require(minimumRmsThreshold in 0f..1f)
        require(minimumPeakThreshold in 0f..1f)
        require(noiseRmsRatio >= 1f && noisePeakRatio >= 1f)
        require(maximumSpeechZeroCrossingRatio in 0.1f..0.8f)
        require(maximumSpeechCrestFactor in 2f..30f)
        require(minimumActiveSampleRatio in 0f..0.5f)
        require(attackFrames in 2..8)
        require(releaseMillis in 150..1_000)
    }

    fun observe(pcmS16Le: ByteArray, capturedAtNanos: Long): SpeechActivityObservation {
        val signal = pcmS16Le.pcmS16LeSignalStats()
        val shape = pcmS16Le.activityShape()
        return observe(
            rms = signal.rms,
            peak = signal.peak,
            capturedAtNanos = capturedAtNanos,
            zeroCrossingRatio = shape.zeroCrossingRatio,
            activeSampleRatio = if (signal.sampleCount == 0) {
                0f
            } else {
                signal.nonZeroSamples.toFloat() / signal.sampleCount
            },
        )
    }

    internal fun observe(
        rms: Float,
        peak: Float,
        capturedAtNanos: Long,
        zeroCrossingRatio: Float = 0.1f,
        activeSampleRatio: Float = 1f,
    ): SpeechActivityObservation {
        require(
            rms in 0f..1f &&
                peak in 0f..1f &&
                zeroCrossingRatio in 0f..1f &&
                activeSampleRatio in 0f..1f &&
                capturedAtNanos >= 0
        )
        val rmsThreshold = max(minimumRmsThreshold, noiseFloorRms * noiseRmsRatio)
        val peakThreshold = max(minimumPeakThreshold, noiseFloorRms * noisePeakRatio)
        val crestFactor = if (rms > 0f) peak / rms else 0f
        val speechLikeShape =
            zeroCrossingRatio <= maximumSpeechZeroCrossingRatio &&
                crestFactor <= maximumSpeechCrestFactor &&
                activeSampleRatio >= minimumActiveSampleRatio
        val probableSpeech = rms >= rmsThreshold && peak >= peakThreshold && speechLikeShape

        if (probableSpeech) {
            consecutiveSpeechFrames += 1
            if (consecutiveSpeechFrames >= attackFrames) speechActive = true
            if (speechActive) lastProbableSpeechNanos = capturedAtNanos
        } else {
            consecutiveSpeechFrames = 0
            val quietMillis = ((capturedAtNanos -
                (lastProbableSpeechNanos ?: capturedAtNanos)) / 1_000_000L).coerceAtLeast(0L)
            if (speechActive && quietMillis >= releaseMillis) speechActive = false

            // Learn ambient noise slowly only outside speech. Limit the update so a sudden click or
            // shouted syllable cannot raise the gate and hide the next sentence.
            if (!speechActive && rms <= max(minimumRmsThreshold * 1.5f, noiseFloorRms * 2f)) {
                noiseFloorRms = (noiseFloorRms * 0.97f + rms * 0.03f)
                    .coerceIn(0.0005f, 0.05f)
            }
        }
        return SpeechActivityObservation(
            active = speechActive,
            lastSpeechAtNanos = lastProbableSpeechNanos,
        )
    }
}

private data class PcmActivityShape(
    val zeroCrossingRatio: Float,
)

private fun ByteArray.activityShape(): PcmActivityShape {
    val evenSize = size - size % Short.SIZE_BYTES
    var offset = 0
    var previousSign = 0
    var comparableSamples = 0
    var zeroCrossings = 0
    while (offset < evenSize) {
        val sample = ((this[offset + 1].toInt() shl 8) or
            (this[offset].toInt() and 0xff)).toShort().toInt()
        val sign = sample.compareTo(0)
        if (sign != 0) {
            if (previousSign != 0) {
                comparableSamples += 1
                if (sign != previousSign) zeroCrossings += 1
            }
            previousSign = sign
        }
        offset += Short.SIZE_BYTES
    }
    return PcmActivityShape(
        zeroCrossingRatio = if (comparableSamples == 0) {
            0f
        } else {
            zeroCrossings.toFloat() / comparableSamples
        },
    )
}
