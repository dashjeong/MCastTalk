package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Completion, watch replacement and timeout ownership share a single atomic state. */
internal class NativeAudioDeadlineState(private val policy: NativeAudioResponseTimeouts) {
    internal data class Watch(val generation: Long, val started: Long,
        val receivedAudio: Boolean = false, val sending: Boolean = true)
    private data class State(val response: Watch? = null, val callback: Watch? = null,
        val failure: NativeAudioResponseTimeout? = null)
    internal class Observation internal constructor(internal val watch: Watch,
        val stage: NativeAudioResponseTimeoutStage)

    private val state = AtomicReference(State())
    private val generations = AtomicLong()
    val failure: NativeAudioResponseTimeout? get() = state.get().failure

    private fun change(update: (State) -> State) {
        while (true) {
            val before = state.get()
            before.failure?.let { throw it }
            if (state.compareAndSet(before, update(before))) return
        }
    }
    fun beginResponse(now: Long): Long {
        val watch = Watch(generations.incrementAndGet(), now)
        change { check(it.response == null); it.copy(response = watch) }
        return watch.generation
    }
    fun requestSent(generation: Long) = change { before ->
        val watch = requireNotNull(before.response)
        check(watch.generation == generation)
        before.copy(response = watch.copy(sending = false))
    }
    fun audioReceived(generation: Long) = change { before ->
        val watch = requireNotNull(before.response)
        check(watch.generation == generation)
        before.copy(response = watch.copy(receivedAudio = true))
    }
    fun completeResponse(generation: Long) = change { before ->
        check(before.response?.generation == generation)
        before.copy(response = null)
    }
    fun beginCallback(now: Long): Long {
        val watch = Watch(generations.incrementAndGet(), now)
        change { check(it.callback == null); it.copy(callback = watch) }
        return watch.generation
    }
    fun completeCallback(generation: Long) {
        while (true) {
            val before = state.get()
            // Cleanup must neither erase a terminal timeout nor mask a parent's cancellation.
            if (before.failure != null || before.callback?.generation != generation) return
            if (state.compareAndSet(before, before.copy(callback = null))) return
        }
    }
    fun observe(now: Long): Observation? {
        val before = state.get()
        if (before.failure != null) return null
        before.callback?.let { watch ->
            if (now - watch.started >= policy.eventCallbackMillis)
                return Observation(watch, NativeAudioResponseTimeoutStage.EVENT_CALLBACK)
        }
        val watch = before.response ?: return null
        val elapsed = now - watch.started
        val stage = when {
            !watch.receivedAudio && elapsed >= policy.firstAudioMillis ->
                if (watch.sending) NativeAudioResponseTimeoutStage.REQUEST_SEND else NativeAudioResponseTimeoutStage.FIRST_AUDIO
            elapsed >= policy.completionMillis -> NativeAudioResponseTimeoutStage.COMPLETION
            else -> return null
        }
        return Observation(watch, stage)
    }
    /** A stale observation cannot claim termination after the observed watch has changed. */
    fun claim(observed: Observation): NativeAudioResponseTimeout? {
        while (true) {
            val before = state.get()
            if (before.failure != null) return null
            val current = if (observed.stage == NativeAudioResponseTimeoutStage.EVENT_CALLBACK)
                before.callback else before.response
            if (current !== observed.watch) return null
            val failure = NativeAudioResponseTimeout(observed.stage)
            if (state.compareAndSet(before, before.copy(failure = failure))) return failure
        }
    }
    fun claimRequestSendTimeout(generation: Long): NativeAudioResponseTimeout? {
        val before = state.get()
        before.failure?.let { return it }
        val watch = before.response?.takeIf { it.generation == generation && it.sending } ?: return null
        return claim(Observation(watch, NativeAudioResponseTimeoutStage.REQUEST_SEND))
    }
}
