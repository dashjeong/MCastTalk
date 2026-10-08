package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A monotonic work clock that excludes explicitly suspended playback time. */
internal class MenuPlaybackClock(private val nowNanos: () -> Long = System::nanoTime) {
    private var observedNanos = nowNanos()
    private var activeNanos = 0L
    private var paused = false

    @Synchronized fun elapsedActiveNanos(): Long {
        advance()
        return activeNanos
    }

    @Synchronized fun setPaused(value: Boolean) {
        advance()
        paused = value
    }

    private fun advance() {
        val now = nowNanos()
        val delta = (now - observedNanos).coerceAtLeast(0)
        if (!paused) activeNanos = if (Long.MAX_VALUE - activeNanos < delta) Long.MAX_VALUE else activeNanos + delta
        observedNanos = now
    }
}

internal class MenuPlaybackDeadlineExceeded : CancellationException("Playback work deadline exceeded")

/** Cancels only this work item; cancelling the parent still cancels its operation immediately. */
internal suspend fun <T> withMenuPlaybackTimeout(
    timeoutMillis: Long,
    clock: MenuPlaybackClock,
    pollMillis: Long = 20,
    block: suspend () -> T,
): T = coroutineScope {
    require(timeoutMillis > 0 && timeoutMillis <= Long.MAX_VALUE / 1_000_000 && pollMillis > 0)
    val started = clock.elapsedActiveNanos()
    val limit = timeoutMillis * 1_000_000
    val operation = async { block() }
    val deadline = launch {
        while (operation.isActive) {
            delay(pollMillis)
            if (clock.elapsedActiveNanos() - started >= limit) {
                operation.cancel(MenuPlaybackDeadlineExceeded())
                break
            }
        }
    }
    try { operation.await() }
    finally { deadline.cancelAndJoin() }
}

/** Finishing a paused producer never grants permission to resume transmission. */
internal fun MenuBroadcastState.withCompletedContent(): MenuBroadcastState =
    if (!isStopping && phase in setOf(MenuBroadcastPhase.LIVE, MenuBroadcastPhase.PAUSED))
        copy(contentCompleted = true, phase = if (phase == MenuBroadcastPhase.PAUSED) phase else MenuBroadcastPhase.COMPLETED)
    else this

internal fun MenuBroadcastState.withResumedContent(): MenuBroadcastState =
    if (!isStopping && phase == MenuBroadcastPhase.PAUSED)
        copy(phase = if (contentCompleted) MenuBroadcastPhase.COMPLETED else MenuBroadcastPhase.LIVE)
    else this

internal fun MenuBroadcastState.permitsLiveAudio(): Boolean =
    !isStopping && phase in setOf(MenuBroadcastPhase.LIVE, MenuBroadcastPhase.COMPLETED)
