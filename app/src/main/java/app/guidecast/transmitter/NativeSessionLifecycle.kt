package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred

/** Local input EOF and provider turn completion are separate observations, not an ASR final guarantee. */
internal class NativeInputDrainState(
    private val onTrace: ((NativeDrainTrace) -> Unit)? = null,
    private val traceNowNanos: () -> Long = System::nanoTime,
) {
    private var requestedValue = false
    private var eosSentValue = false
    private var completedValue = false
    private var activeInputRevision = 0L
    private var completedThroughRevision = 0L
    private var turnStartedAtRevision = 0L
    private var turnInProgress = false
    private var sourceInProgress = false
    private val ended = CompletableDeferred<Boolean>()
    private val traceOrigin = if (onTrace == null) 0L else runCatching(traceNowNanos).getOrDefault(0L)
    private var traceSequence = 0L
    private var traceTurn = 0L
    private var traceSuppressed = 0L
    private var traceTransitions = 0L
    private val traceLifecycleKinds = mutableSetOf<NativeDrainTraceKind>()
    private var traceLastNanos = 0L
    private var lastQuietReject: Int? = null
    private fun traceState() = NativeDrainTraceState(
        activeInputRevision, turnStartedAtRevision, completedThroughRevision,
        (if (requestedValue) 1 else 0) or (if (eosSentValue) 2 else 0) or
            (if (completedValue) 4 else 0) or (if (turnInProgress) 8 else 0) or
            (if (sourceInProgress) 16 else 0) or (if (ended.isCompleted) 32 else 0),
        (if (!eosSentValue) 1 else 0) or (if (turnInProgress) 2 else 0) or
            (if (sourceInProgress) 4 else 0) or (if (activeInputRevision > completedThroughRevision) 8 else 0),
    )
    // Caller holds the drain monitor. The production sink only enqueues immutable count records.
    private fun trace(kind: NativeDrainTraceKind, before: NativeDrainTraceState, flags: Int = 0) {
        val sink = onTrace ?: return
        val lifecycle = kind == NativeDrainTraceKind.REQUEST || kind == NativeDrainTraceKind.EOS_SEND_START ||
            kind == NativeDrainTraceKind.EOS_SEND_FAILED || kind == NativeDrainTraceKind.EOS_SENT ||
            kind == NativeDrainTraceKind.SESSION_ENDED
        if (lifecycle) {
            if (!traceLifecycleKinds.add(kind)) { traceSuppressed++; return }
        } else {
            if (traceTransitions >= 60L) { traceSuppressed++; return }
            traceTransitions++
        }
        runCatching {
            traceLastNanos = maxOf(traceLastNanos, (traceNowNanos() - traceOrigin).coerceAtLeast(0L))
            sink(NativeDrainTrace(++traceSequence, traceTurn, traceLastNanos, kind,
                flags, before, traceState(), traceSuppressed))
        }
    }
    @Synchronized fun eosSendStarted() { if (onTrace != null) trace(NativeDrainTraceKind.EOS_SEND_START, traceState()) }
    @Synchronized fun eosSendFailed() { if (onTrace != null) trace(NativeDrainTraceKind.EOS_SEND_FAILED, traceState()) }
    val requested: Boolean @Synchronized get() = requestedValue
    val completed: Boolean @Synchronized get() = completedValue
    @Synchronized fun request(): Boolean {
        if (requestedValue || ended.isCompleted) return false
        val before = if (onTrace != null) traceState() else null
        requestedValue = true
        before?.let { trace(NativeDrainTraceKind.REQUEST, it) }
        return true
    }
    @Synchronized fun audioSent(active: Boolean) { if (active) activeInputRevision++ }
    /** Manual activity owns all input up to its successfully sent End. No next activity may be sent until terminal publication. */
    @Synchronized fun activityEndSent() {
        check(!turnInProgress) { "Activity response overlapped its input boundary" }
        val before = if (onTrace != null) traceState() else null
        turnInProgress = true
        turnStartedAtRevision = activeInputRevision
        traceTurn++
        before?.let { trace(NativeDrainTraceKind.ACTIVITY_END_SENT, it) }
    }
    @Synchronized fun eosSent() {
        check(requestedValue)
        val before = if (onTrace != null) traceState() else null
        eosSentValue = true
        before?.takeIf { it.state and 2 == 0 }?.let { trace(NativeDrainTraceKind.EOS_SENT, it) }
    }
    @Synchronized fun completeQuietInput(): Boolean {
        val before = if (onTrace != null) traceState() else null
        // Energy is only a conservative pending-input guard, not speech recognition or an ACK.
        if (eosSentValue && !turnInProgress && !sourceInProgress && activeInputRevision <= completedThroughRevision)
            completedValue = true
        if (before != null && requestedValue &&
            (lastQuietReject != traceState().reject || before.state != traceState().state)) {
            lastQuietReject = traceState().reject
            trace(NativeDrainTraceKind.QUIET_CHECK, before)
        }
        return completedValue
    }
    /** Record the first output's input boundary before publication can suspend. */
    @Synchronized fun outputStarted() {
        if (!turnInProgress) {
            val before = if (onTrace != null) traceState() else null
            turnInProgress = true
            turnStartedAtRevision = activeInputRevision
            traceTurn++
            before?.let { trace(NativeDrainTraceKind.FIRST_OUTPUT, it) }
        }
    }
    @Synchronized fun observeResponse(hasOutput: Boolean, finished: Boolean, interrupted: Boolean, hasSource: Boolean = false): Boolean {
        val sourceOpened = hasSource && !sourceInProgress
        val before = if (onTrace != null && (finished || interrupted || sourceOpened)) traceState() else null
        if (hasSource) sourceInProgress = true
        if (hasOutput) outputStarted()
        if (finished || interrupted) {
            if (finished && turnInProgress) completedThroughRevision = turnStartedAtRevision
            turnInProgress = false
            sourceInProgress = false
        }
        if (finished && eosSentValue && activeInputRevision <= completedThroughRevision) completedValue = true
        if (before != null && (finished || interrupted || sourceOpened)) {
            val flags = (if (hasOutput) 1 else 0) or (if (finished) 2 else 0) or
                (if (interrupted) 4 else 0) or (if (hasSource) 8 else 0)
            trace(if (interrupted) NativeDrainTraceKind.INTERRUPTED else if (finished)
                NativeDrainTraceKind.TURN_COMPLETE else NativeDrainTraceKind.SOURCE_PENDING, before, flags)
        }
        return completedValue
    }
    @Synchronized fun sessionEnded(completedNormally: Boolean) {
        val before = if (onTrace != null && !ended.isCompleted) traceState() else null
        ended.complete(completedNormally && completedValue)
        before?.let { trace(NativeDrainTraceKind.SESSION_ENDED, it, if (completedNormally) 1 else 0) }
    }
    suspend fun awaitEnded(): Boolean = ended.await()
}
