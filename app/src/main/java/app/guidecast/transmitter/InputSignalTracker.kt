package app.guidecast.transmitter

internal data class InputSignalSnapshot(
    val frameCount: Long,
    val audibleFrameCount: Long,
    val signalActive: Boolean,
)

/**
 * Requires a short run of audible PCM before reporting success and clears that success after a
 * sustained quiet period. A single click or an old frame therefore cannot leave the UI green.
 */
internal class InputSignalTracker(
    private val audibleRmsThreshold: Float = 0.002f,
    private val audiblePeakThreshold: Float = 0.01f,
    private val minimumConsecutiveAudibleFrames: Int = 5,
    private val signalHoldMillis: Long = 2_500L,
) {
    private var frameCount = 0L
    private var audibleFrameCount = 0L
    private var consecutiveAudibleFrameCount = 0
    private var lastAudibleFrameMillis = 0L
    private var signalActive = false

    fun observe(rms: Float, peak: Float, elapsedRealtimeMillis: Long): InputSignalSnapshot {
        frameCount += 1
        val audible = rms >= audibleRmsThreshold || peak >= audiblePeakThreshold
        if (audible) {
            audibleFrameCount += 1
            consecutiveAudibleFrameCount += 1
            lastAudibleFrameMillis = elapsedRealtimeMillis
            if (consecutiveAudibleFrameCount >= minimumConsecutiveAudibleFrames) {
                signalActive = true
            }
        } else {
            consecutiveAudibleFrameCount = 0
            if (signalActive &&
                elapsedRealtimeMillis - lastAudibleFrameMillis >= signalHoldMillis
            ) {
                signalActive = false
            }
        }
        return InputSignalSnapshot(
            frameCount = frameCount,
            audibleFrameCount = audibleFrameCount,
            signalActive = signalActive,
        )
    }
}
