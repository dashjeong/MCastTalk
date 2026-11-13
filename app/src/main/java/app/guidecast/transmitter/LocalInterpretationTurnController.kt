package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.SynthesisTermination
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class LocalInterpretationPhase { OFF, LISTENING, PREPARING, PLAYING }

internal data class LocalInterpretationTurn(
    val monitorGeneration: Long, val sessionId: Long, val channelId: String,
    val sequence: Long, val generation: Long,
    val speechStartDeadlineNanos: Long, val totalDeadlineNanos: Long,
)

/** 휴대폰 한 대의 듣기/통역 차례. 원음은 소유하지 않는다. */
internal class LocalInterpretationTurnController(
    private val onPhase: (LocalInterpretationPhase) -> Unit = {},
) {
    var phase: LocalInterpretationPhase = LocalInterpretationPhase.OFF
        private set
    private var monitor: Long? = null
    private var session = 0L
    private var channel = ""
    private var nextGeneration = 0L
    private var turn: LocalInterpretationTurn? = null
    private var detector = AdaptiveSpeechActivityDetector()
    private var observedAt: Long? = null
    private var quietSince: Long? = null
    private var closedAt: Long? = null
    private val intervals = ArrayDeque<LongRange>()
    private var retiredThrough = Long.MIN_VALUE

    @Synchronized fun activate(monitorGeneration: Long, sessionId: Long, channelId: String, now: Long) {
        closeInterval(now)
        monitor = monitorGeneration; session = sessionId; channel = channelId; turn = null
        resetObservation()
        setPhase(LocalInterpretationPhase.LISTENING)
    }

    @Synchronized fun registeredGeneration(sessionId: Long, channelId: String): Long? =
        monitor?.takeIf { session == sessionId && channel == channelId }

    @Synchronized fun observe(frame: PcmAudioFrame) {
        if (monitor == null || closedAt != null) return
        val now = frame.capturedAtElapsedRealtimeNanos
        // 마이크가 끊긴 시간을 쉼으로 계산하지 않는다.
        if (observedAt?.let { now < it || now - it > 160_000_000L } == true) resetObservation()
        observedAt = now
        val observation = detector.observe(frame.bytes, now)
        if (observation.active) quietSince = null
        else if (quietSince == null) quietSince = now
    }

    @Synchronized fun reserve(monitorGeneration: Long, sequence: Long, now: Long,
        speechStartDeadlineNanos: Long = now + 10_000_000_000L): LocalInterpretationTurn? {
        if (monitor != monitorGeneration || turn != null) return null
        if (closedAt == null) {
            val observed = observedAt ?: return null
            val quiet = quietSince ?: return null
            if (now < observed || now - observed > 160_000_000L || observed - quiet < 700_000_000L) return null
            closedAt = now
        }
        return LocalInterpretationTurn(monitorGeneration, session, channel, sequence, ++nextGeneration,
            speechStartDeadlineNanos, now + 150_000_000_000L).also {
            turn = it
            setPhase(LocalInterpretationPhase.PREPARING)
        }
    }

    @Synchronized fun owns(turn: LocalInterpretationTurn): Boolean = this.turn == turn && monitor == turn.monitorGeneration
    @Synchronized fun expired(monitorGeneration: Long, now: Long): Boolean = turn?.let {
        it.monitorGeneration == monitorGeneration &&
            (now >= it.totalDeadlineNanos || phase == LocalInterpretationPhase.PREPARING && now >= it.speechStartDeadlineNanos)
    } == true
    @Synchronized fun accepts(monitorGeneration: Long, sequence: Long?): Boolean =
        turn?.let { it.monitorGeneration == monitorGeneration && it.sequence == sequence && closedAt != null } == true

    @Synchronized fun playing(turn: LocalInterpretationTurn) {
        if (owns(turn)) setPhase(LocalInterpretationPhase.PLAYING)
    }

    @Synchronized fun playing(monitorGeneration: Long, sequence: Long?) {
        if (accepts(monitorGeneration, sequence)) turn?.let(::playing)
    }

    @Synchronized fun complete(turn: LocalInterpretationTurn, now: Long, keepClosed: Boolean = false) {
        if (!owns(turn)) return
        this.turn = null
        if (keepClosed) setPhase(LocalInterpretationPhase.PREPARING)
        else {
            closeInterval(now)
            resetObservation()
            setPhase(LocalInterpretationPhase.LISTENING)
        }
    }

    @Synchronized fun deactivate(monitorGeneration: Long, now: Long) {
        if (monitor != monitorGeneration) return
        closeInterval(now)
        monitor = null; turn = null
        resetObservation()
        setPhase(LocalInterpretationPhase.OFF)
    }

    @Synchronized fun releaseUnusedCarry(previous: LocalInterpretationTurn, now: Long) {
        if (monitor == previous.monitorGeneration && turn == null && nextGeneration == previous.generation) {
            closeInterval(now)
            resetObservation()
            setPhase(LocalInterpretationPhase.LISTENING)
        }
    }

    @Synchronized fun recognitionCopy(frame: PcmAudioFrame): PcmAudioFrame {
        val captured = frame.capturedAtElapsedRealtimeNanos
        val blocked = captured <= retiredThrough || closedAt?.let { captured >= it } == true ||
            intervals.any { captured in it }
        return if (blocked) frame.copy(bytes = ByteArray(frame.bytes.size)) else frame
    }

    private fun closeInterval(now: Long) {
        closedAt?.let { start ->
            intervals.addLast(start..maxOf(start, now))
            // 정리된 과거 프레임은 보수적으로 무음 처리하므로 재사용돼도 통역 소리가 새지 않는다.
            while (intervals.size > 128) retiredThrough = maxOf(retiredThrough, intervals.removeFirst().last)
        }
        closedAt = null
    }

    private fun resetObservation() { detector = AdaptiveSpeechActivityDetector(); observedAt = null; quietSince = null }
    private fun setPhase(value: LocalInterpretationPhase) {
        if (phase != value) { phase = value; onPhase(value) }
    }
}

/** 자체 스피커 정리와 새 세대 등록은 같은 잠금에서 끝나야 한다. */
internal inline fun <T> withLocalMonitorTeardown(lock: Any, detach: () -> T, beforeUnlock: (T) -> Unit): T {
    return synchronized(lock) {
        detach().also(beforeUnlock)
    }
}

internal enum class LocalInterpretationDrainState { WAITING, DRAINED, FAILED }

internal class LocalInterpretationPlaybackCheckpoint(private val start: NativeLocalPlaybackSnapshot) {
    fun inspect(current: NativeLocalPlaybackSnapshot, publishedBytes: Long): LocalInterpretationDrainState {
        val before = start.buffer
        val after = current.buffer
        if (current.generation != start.generation || current.closed || current.epochChanged ||
            after.rejectedBytes != before.rejectedBytes || after.overwrittenBytes != before.overwrittenBytes ||
            after.canceledBytes != before.canceledBytes || current.unwrittenDequeuedBytes < 0 ||
            current.writtenBytes < start.writtenBytes || after.offeredBytes - before.offeredBytes > publishedBytes
        ) return LocalInterpretationDrainState.FAILED
        val accepted = current.writtenBytes - start.writtenBytes
        return if (publishedBytes > 0 && after.offeredBytes - before.offeredBytes == publishedBytes &&
            accepted == publishedBytes && after.pendingBytes == 0L && after.pendingFrames == 0 &&
            current.unwrittenDequeuedBytes == start.unwrittenDequeuedBytes &&
            (current.playbackHeadSamples ?: -1) >= current.writtenBytes / 2
        ) LocalInterpretationDrainState.DRAINED else LocalInterpretationDrainState.WAITING
    }
}

internal suspend fun finishLocalInterpretationTurn(
    controller: LocalInterpretationTurnController, turn: LocalInterpretationTurn,
    result: SynthesisTermination, playback: LocalInterpretationPlaybackCheckpoint,
    publishedBytes: Long, hasPendingSpeech: Boolean,
    snapshot: () -> NativeLocalPlaybackSnapshot?, stillCurrent: () -> Boolean,
    stopAndFlush: () -> Unit, nowNanos: () -> Long,
    drainTimeoutMillis: Long = 30_000,
): Boolean = withContext(NonCancellable) {
    if (!controller.owns(turn)) return@withContext false
    var completed = false
    try {
        if (result == SynthesisTermination.SUCCEEDED) {
            val drained = withTimeoutOrNull(drainTimeoutMillis) {
                while (controller.owns(turn) && stillCurrent()) {
                    val receipt = snapshot()
                    when (receipt?.let { playback.inspect(it, publishedBytes) }) {
                        LocalInterpretationDrainState.FAILED -> return@withTimeoutOrNull false
                        LocalInterpretationDrainState.DRAINED -> {
                            delay(200) // 실제 소진 확인 뒤에만 잔향 여유를 더한다.
                            return@withTimeoutOrNull controller.owns(turn) && stillCurrent() &&
                                snapshot()?.let { playback.inspect(it, publishedBytes) } == LocalInterpretationDrainState.DRAINED
                        }
                        else -> delay(20)
                    }
                }
                false
            } == true
            if (drained) {
                controller.complete(turn, nowNanos(), keepClosed = hasPendingSpeech)
                completed = true
            }
        }
        completed
    } finally {
        // 옛 콜백은 새 스피커를 멈추거나 새 차례의 차단을 열 수 없다.
        if (!completed && controller.owns(turn)) {
            try { stopAndFlush() } finally { controller.deactivate(turn.monitorGeneration, nowNanos()) }
        }
    }
}
