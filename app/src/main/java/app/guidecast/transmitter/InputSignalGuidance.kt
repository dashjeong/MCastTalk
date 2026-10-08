package app.guidecast.transmitter

internal enum class InputSignalLevel { INACTIVE, WAITING, LOW, RECEIVING, HIGH, BLOCKED }

internal data class InputSignalGuidance(val level: InputSignalLevel, val label: String, val detail: String, val attention: Boolean = false)

/** UI guidance only. These observations never remove PCM or identify a person who is speaking. */
internal class InputSignalGuidanceTracker {
    private var lastFrameCount = -1L
    private var lastObservedMillis: Long? = null
    private var category: InputSignalLevel? = null
    private var categorySinceMillis = 0L
    private var consecutiveObservations = 0
    private var lastGuidance = guidance(InputSignalLevel.INACTIVE)

    fun observe(
        rms: Float, peak: Float, frameCount: Long, elapsedRealtimeMillis: Long,
        inputActive: Boolean = true, clientSilenced: Boolean = false, systemMuted: Boolean = false,
    ): InputSignalGuidance {
        if (!inputActive) return resetAndShow(InputSignalLevel.INACTIVE)
        if (clientSilenced || systemMuted) return resetAndShow(InputSignalLevel.BLOCKED)
        if (frameCount <= 0 || !rms.isFinite() || !peak.isFinite() || rms < 0f || peak < 0f)
            return resetAndShow(InputSignalLevel.WAITING)
        val previousTime = lastObservedMillis
        if (frameCount < lastFrameCount || (previousTime != null &&
                (elapsedRealtimeMillis < previousTime || elapsedRealtimeMillis - previousTime > MAX_OBSERVATION_GAP_MILLIS))) reset()
        // Repeated recompositions cannot turn an old sample into sustained low/high input.
        if (frameCount == lastFrameCount) return lastGuidance
        lastFrameCount = frameCount
        lastObservedMillis = elapsedRealtimeMillis
        val observed = when {
            peak >= HIGH_PEAK_THRESHOLD -> InputSignalLevel.HIGH
            rms == 0f && peak == 0f -> InputSignalLevel.WAITING
            rms < LOW_RMS_THRESHOLD && peak < LOW_PEAK_THRESHOLD -> InputSignalLevel.LOW
            else -> InputSignalLevel.RECEIVING
        }
        if (category != observed) {
            category = observed; categorySinceMillis = elapsedRealtimeMillis; consecutiveObservations = 0
        }
        consecutiveObservations++
        val heldMillis = elapsedRealtimeMillis - categorySinceMillis
        val settled = when (observed) {
            InputSignalLevel.HIGH -> if (heldMillis >= HIGH_HOLD_MILLIS && consecutiveObservations >= MIN_OBSERVATIONS) observed else InputSignalLevel.RECEIVING
            InputSignalLevel.LOW -> if (heldMillis >= LOW_HOLD_MILLIS && consecutiveObservations >= MIN_OBSERVATIONS) observed else InputSignalLevel.WAITING
            else -> observed
        }
        return guidance(settled).also { lastGuidance = it }
    }

    private fun resetAndShow(level: InputSignalLevel): InputSignalGuidance {
        reset()
        return guidance(level).also { lastGuidance = it }
    }

    private fun reset() {
        lastFrameCount = -1; lastObservedMillis = null; category = null
        categorySinceMillis = 0; consecutiveObservations = 0
    }

    private fun guidance(level: InputSignalLevel): InputSignalGuidance = when (level) {
        InputSignalLevel.INACTIVE -> InputSignalGuidance(level, "마이크 입력 중지", "입력을 시작하면 말할 때 소리가 들어오는지 확인할 수 있습니다.")
        InputSignalLevel.WAITING -> InputSignalGuidance(level, "입력 대기", "말할 때 입력 막대가 움직이는지 확인하세요. 마이크를 화자 가까이에 두면 도움이 됩니다.")
        InputSignalLevel.LOW -> InputSignalGuidance(level, "입력 소리 작음", "말하는데도 입력이 작다면 마이크를 가까이 두고, 유선·USB·블루투스 마이크가 올바르게 선택됐는지 확인하세요.", true)
        InputSignalLevel.RECEIVING -> InputSignalGuidance(level, "소리 입력 중", "주변 대화나 TV 소리도 입력될 수 있습니다. 이어폰이나 화자 가까이 둔 외부 마이크를 사용하면 도움이 됩니다.")
        InputSignalLevel.HIGH -> InputSignalGuidance(level, "입력 소리 큼", "소리가 찌그러질 수 있습니다. 마이크를 조금 멀리 두거나 외부 마이크의 입력 레벨을 낮춰 보세요.", true)
        InputSignalLevel.BLOCKED -> InputSignalGuidance(level, "마이크 입력 차단", "다른 녹음·통화 앱을 닫고 기기의 마이크 접근 설정을 확인하세요.", true)
    }

    private companion object {
        const val HIGH_PEAK_THRESHOLD = 0.98f
        const val LOW_RMS_THRESHOLD = 0.002f
        const val LOW_PEAK_THRESHOLD = 0.01f
        const val HIGH_HOLD_MILLIS = 600L
        const val LOW_HOLD_MILLIS = 2_000L
        const val MAX_OBSERVATION_GAP_MILLIS = 1_500L
        const val MIN_OBSERVATIONS = 3
    }
}
