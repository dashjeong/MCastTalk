package app.guidecast.transmitter

/** Ephemeral process-local ordinal only; never a device, user or provider session identifier. */
internal object NativeDrainTraceOrdinals {
    private val next = java.util.concurrent.atomic.AtomicLong()
    fun next(): Long = next.incrementAndGet()
}
internal enum class NativeDrainTraceKind {
    REQUEST, EOS_SEND_START, EOS_SEND_FAILED, EOS_SENT, FIRST_OUTPUT, SOURCE_PENDING,
    TURN_COMPLETE, INTERRUPTED, QUIET_CHECK, SESSION_ENDED, ACTIVITY_END_SENT,
}
/** All fields are counts/masks. State: request1,eos2,complete4,turn8,source16,ended32.
 * Reject: noEos1,turn2,source4,outstandingRevision8. Rejection is not a provider ACK.
 */
internal data class NativeDrainTraceState(
    val active: Long, val started: Long, val covered: Long, val state: Int, val reject: Int,
)
internal data class NativeDrainTrace(
    val sequence: Long, val turn: Long, val relativeNanos: Long, val kind: NativeDrainTraceKind,
    val flags: Int, val before: NativeDrainTraceState, val after: NativeDrainTraceState,
    val suppressed: Long,
) {
    fun detail(drainOrdinal: Long): String =
        "drain=$drainOrdinal seq=$sequence turn=$turn dt_ns=$relativeNanos event=${kind.name} flags=$flags " +
            "pre=${before.active},${before.started},${before.covered},${before.state},${before.reject} " +
            "post=${after.active},${after.started},${after.covered},${after.state},${after.reject} suppressed=$suppressed"
}
