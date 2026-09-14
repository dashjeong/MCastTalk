package app.guidecast.transmitter

/**
 * Detects a recognizer that consumes PCM but stops producing usable, changing hypotheses.
 * Silence, a paused/disconnected input and model preparation are not recognition failures.
 * All state is bounded and scoped to the active attempt; no transcript text enters diagnostics.
 */
internal class RecognitionProgressWatchdog(
    private val responseDeadlineMillis: Long = 20_000L,
    private val minimumSpeechMillis: Long = 6_000L,
    private val recentInputMillis: Long = 2_000L,
) {
    private var attemptId: Long? = null
    private var lastProgressMillis = 0L
    private var lastInputMillis: Long? = null
    private var lastSpeechMillis: Long? = null
    private var speechMillis = 0L
    private var requested = false

    @Synchronized fun beginAttempt(id: Long, nowMillis: Long) {
        attemptId = id
        lastProgressMillis = nowMillis
        lastInputMillis = null
        lastSpeechMillis = null
        speechMillis = 0L
        requested = false
    }

    @Synchronized fun observeInput(nowMillis: Long, isSpeech: Boolean, frameDurationMillis: Long) {
        if (attemptId == null) return
        lastInputMillis = nowMillis
        if (isSpeech) {
            lastSpeechMillis = nowMillis
            speechMillis = (speechMillis + frameDurationMillis.coerceIn(0L, 1_000L))
                .coerceAtMost(minimumSpeechMillis)
        }
    }

    @Synchronized fun onChangedTranscript(id: Long, nowMillis: Long) {
        if (id != attemptId) return
        lastProgressMillis = nowMillis
        speechMillis = 0L
    }

    @Synchronized fun stalledAttempt(nowMillis: Long): Long? {
        val id = attemptId ?: return null
        val input = lastInputMillis ?: return null
        val speech = lastSpeechMillis ?: return null
        if (requested || nowMillis - input > recentInputMillis ||
            nowMillis - speech > recentInputMillis || speechMillis < minimumSpeechMillis ||
            nowMillis - lastProgressMillis < responseDeadlineMillis) return null
        requested = true
        return id
    }

    @Synchronized fun endAttempt(id: Long) {
        if (attemptId == id) attemptId = null
    }
}

internal class RecognitionProgressStalledException : IllegalStateException(
    "입력 신호는 계속되지만 음성인식 결과가 갱신되지 않아 인식 연결을 다시 준비합니다.",
)
