package app.guidecast.core.translation

/** 언어별 발언권. 이미 시작한 AI 문장과 게시한 음성의 재생 길이는 끝까지 보장한다. */
class HumanInterpretationFloor(private val nowNanos: () -> Long = System::nanoTime) {
    private class Lane {
        var connected = false
        var everSpoke = false
        var lastVoice: Long? = null
        var aiSentence = false
        var audioUntil = 0L
        var humanAudioUntil = 0L
        var lastFinal = Long.MIN_VALUE
        var skipThrough = Long.MIN_VALUE
    }
    private val lanes = mutableMapOf<String, Lane>()
    private fun lane(channel: String) = lanes.getOrPut(channel) { Lane() }

    @Synchronized fun connected(channel: String) { lane(channel).connected = true }
    @Synchronized fun disconnected(channel: String) {
        lane(channel).also { release(it); it.connected = false }
    }
    @Synchronized fun pause(channel: String) { release(lane(channel)) }

    @Synchronized fun finalized(channel: String, sequence: Long) {
        lane(channel).also { expire(it); it.lastFinal = maxOf(it.lastFinal, sequence) }
    }

    @Synchronized fun skipAi(channel: String, sequence: Long): Boolean {
        val state = lane(channel)
        expire(state)
        return state.lastVoice != null || sequence <= state.skipThrough
    }

    @Synchronized fun beginAiSentence(channel: String, sequence: Long): Boolean {
        if (skipAi(channel, sequence)) return false
        val state = lane(channel)
        if (state.aiSentence) return false
        state.aiSentence = true
        return true
    }

    @Synchronized fun aiFrame(channel: String, bytes: Int, sampleRate: Int) {
        val state = lane(channel)
        state.audioUntil = maxOf(state.audioUntil, nowNanos()) + bytes.toLong() * 1_000_000_000L / (sampleRate * 2L)
    }

    @Synchronized fun endAiSentence(channel: String) { lane(channel).aiSentence = false }

    @Synchronized fun humanDrainMillis(channel: String): Long =
        ((lane(channel).humanAudioUntil - nowNanos() + 999_999L) / 1_000_000L).coerceAtLeast(0)

    /** 게시까지 같은 잠금 안에서 처리해 문장 시작과 사람 음성의 경합을 막는다. */
    @Synchronized fun humanFrame(channel: String, audible: Boolean, bytes: Int = 0, sampleRate: Int = 16_000,
        publish: () -> Boolean): Boolean {
        val state = lane(channel)
        expire(state)
        if (!state.connected) return false
        if (audible) {
            state.lastVoice = nowNanos()
            state.everSpoke = true
        }
        if (state.lastVoice == null || state.aiSentence || nowNanos() < state.audioUntil) return false
        if (!publish()) return false
        state.humanAudioUntil = maxOf(state.humanAudioUntil, nowNanos()) + bytes.toLong() * 1_000_000_000L / (sampleRate * 2L)
        return true
    }

    @Synchronized fun state(channel: String): String {
        val state = lane(channel)
        expire(state)
        return when {
            state.lastVoice != null && (state.aiSentence || nowNanos() < state.audioUntil) -> "WAITING"
            state.lastVoice != null -> "HUMAN"
            state.everSpoke -> "AI"
            state.connected -> "READY"
            else -> "IDLE"
        }
    }

    private fun expire(state: Lane) {
        if (state.lastVoice?.let { nowNanos() - it > 8_000_000_000L } == true) release(state)
    }
    private fun release(state: Lane) {
        if (state.lastVoice != null) state.skipThrough = maxOf(state.skipThrough, state.lastFinal)
        state.lastVoice = null
    }
}
