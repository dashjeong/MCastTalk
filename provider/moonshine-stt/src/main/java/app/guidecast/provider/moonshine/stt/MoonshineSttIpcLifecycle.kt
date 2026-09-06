package app.guidecast.provider.moonshine.stt

import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Records cancellation across the non-atomic Android bindService boundary. */
internal class MoonshineSttBindingAttemptState {
    private val cancelled = AtomicBoolean(false)

    val wasCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
    }

    fun mayStartBinding(continuationActive: Boolean): Boolean =
        continuationActive && !cancelled.get()
}

/**
 * Native STT memory may be reclaimed only after both forms of work have relinquished ownership.
 * Epochs and counts are accepted instead of engine internals so this policy remains a small
 * JVM-testable boundary; synchronization is supplied by the engine's model/connection mutexes.
 */
internal fun moonshineSttNativeReleaseDecision(
    expectedUseEpoch: Long,
    currentUseEpoch: Long,
    pendingPreparationCount: Int,
    activeRecognitionCount: Int,
): MoonshineSttNativeReleaseResult {
    require(expectedUseEpoch >= 0L) { "Expected use epoch cannot be negative" }
    require(currentUseEpoch >= 0L) { "Current use epoch cannot be negative" }
    require(pendingPreparationCount >= 0) { "Pending preparation count cannot be negative" }
    require(activeRecognitionCount >= 0) { "Active recognition count cannot be negative" }
    return when {
        expectedUseEpoch != currentUseEpoch -> MoonshineSttNativeReleaseResult.SUPERSEDED
        pendingPreparationCount > 0 || activeRecognitionCount > 0 ->
            MoonshineSttNativeReleaseResult.BUSY
        else -> MoonshineSttNativeReleaseResult.RELEASED
    }
}

/**
 * Recognition is a hot operation: it may create a stream on an already loaded transcriber, but it
 * must never turn into an uncoordinated JNI model load. The client explicitly warms a replacement
 * worker under process-wide native admission before retrying the recognition session.
 */
internal fun <T> requirePreparedMoonshineSttRuntime(
    activeRuntime: T?,
    isLoaded: (T) -> Boolean,
): T {
    if (activeRuntime == null || !isLoaded(activeRuntime)) {
        throw MoonshineSttNativePreparationRequiredException()
    }
    return activeRuntime
}

internal class MoonshineSttNativePreparationRequiredException : IllegalStateException(
    "Moonshine STT 네이티브 작업자를 다시 준비해야 합니다.",
)

/** Disk-ready assets are not evidence that an isolated native worker generation is still live. */
internal fun moonshineSttStatusForWorkerLiveness(
    current: MoonshineSpeechLanguageStatus,
    workerAlive: Boolean,
): MoonshineSpeechLanguageStatus = if (current.isReady && !workerAlive) {
    nativeRestartRequiredStatus()
} else {
    current
}

/**
 * Bounded stop tombstones for one-way Binder calls that may arrive before startRecognition.
 * Session identifiers are never reused, so an early stop remains authoritative until that start
 * has been observed. Bounding protects the private worker from an accidental unbounded caller.
 */
internal class MoonshineSttCancelledSessions(
    private val maximumEntries: Int = 64,
) {
    private val lock = Any()
    private val sessionIds = LinkedHashSet<Long>()

    init {
        require(maximumEntries > 0)
    }

    fun mark(sessionId: Long) {
        if (sessionId <= 0L) return
        synchronized(lock) {
            sessionIds.remove(sessionId)
            sessionIds.add(sessionId)
            while (sessionIds.size > maximumEntries) {
                sessionIds.remove(sessionIds.iterator().next())
            }
        }
    }

    fun markAll(sessionIds: Collection<Long>) {
        sessionIds.forEach(::mark)
    }

    operator fun contains(sessionId: Long): Boolean = synchronized(lock) {
        sessionId in sessionIds
    }

    fun remove(sessionId: Long): Boolean = synchronized(lock) {
        sessionIds.remove(sessionId)
    }

    internal fun size(): Int = synchronized(lock) { sessionIds.size }
}
