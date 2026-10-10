package app.guidecast.transmitter

import java.util.ArrayDeque

internal enum class GeminiManualSealReason { NONE, EXPLICIT_SEAL, MAX_DURATION, QUIET, INPUT_EOF }

/** Half-open byte ranges refer to accepted PCM, including subsequently suppressed idle bytes.
 * Ingress times bound Flow delivery, not physical microphone capture. -1 means unobserved.
 * Attempted ranges are not send acknowledgements. Energy is a segmentation hint, not speech VAD.
 */
internal data class GeminiManualEpochTrace(
    val epoch: Long,
    val queueAtPreviousTerminal: Int,
    var attemptedFirst: Long = -1, var attemptedEnd: Long = -1, var attemptedBytes: Long = 0,
    var acceptedFirstNs: Long = -1, var acceptedLastNs: Long = -1,
    var ingressFirstNs: Long = -1, var ingressLastNs: Long = -1,
    var sentInputFirst: Long = -1, var sentInputEnd: Long = -1,
    var sentFirst: Long = -1, var sentEnd: Long = -1,
    var sentFirstNs: Long = -1, var sentLastNs: Long = -1,
    var energyPackets: Long = 0, var energyFirst: Long = -1, var energyEnd: Long = -1,
    var quietBytes: Long = 0, var preRollBytes: Long = 0,
    var seal: GeminiManualSealReason = GeminiManualSealReason.NONE,
    var endAckNs: Long = -1, var terminalNs: Long = -1, var queueAtTerminal: Int = -1,
    var sourceEvents: Long = 0, var sourceFirstNs: Long = -1, var sourceLastNs: Long = -1,
) {
    fun detail(): String = "epoch=$epoch prev_terminal_queue=$queueAtPreviousTerminal " +
        "attempt_input=$attemptedFirst,$attemptedEnd attempt_bytes=$attemptedBytes " +
        "accepted_ns=$acceptedFirstNs,$acceptedLastNs ingress_ns=$ingressFirstNs,$ingressLastNs " +
        "sent_input=$sentInputFirst,$sentInputEnd sent_stream=$sentFirst,$sentEnd sent_ns=$sentFirstNs,$sentLastNs " +
        "energy_packets=$energyPackets energy_input=$energyFirst,$energyEnd quiet=$quietBytes preroll=$preRollBytes " +
        "seal=${seal.name} end_ack_ns=$endAckNs terminal_ns=$terminalNs terminal_queue=$queueAtTerminal " +
        "source_events=$sourceEvents source_ns=$sourceFirstNs,$sourceLastNs"
}

internal data class GeminiManualInputBoundary(
    val relativeNs: Long, val ingressBytes: Long, val acceptedBytes: Long,
    val queuedBytes: Int, val unconfirmedBytes: Int,
) {
    fun detail(): String = "$relativeNs,$ingressBytes,$acceptedBytes,$queuedBytes,$unconfirmedBytes"
}

internal data class GeminiManualTraceSnapshot(
    val acceptedBytes: Long, val ingressBytes: Long, val deliveredBytes: Long,
    val suppressedBytes: Long, val queuedBytes: Int, val unconfirmedBytes: Int, val abandonedBytes: Long,
    val completedActivities: Long, val omittedEpochs: Long, val orderViolations: Long,
    val unownedSourceEvents: Long, val stopObserved: GeminiManualInputBoundary?, val inputEof: GeminiManualInputBoundary?,
    val epochs: List<GeminiManualEpochTrace>,
    val drainOrdinal: Long = -1,
    val packetizerDiscardedBytes: Long = 0,
) {
    fun detail(): String = "schema=1 drain=$drainOrdinal time=RELATIVE_MONOTONIC_NS origin=PUMP_CONSTRUCTION " +
        "ingress=FLOW_DELIVERY end_ack=SOCKET_SEND_RETURN terminal=POST_PUBLICATION_ACCEPT " +
        "accepted=$acceptedBytes ingress_bytes=$ingressBytes sent=$deliveredBytes idle_suppressed=$suppressedBytes " +
        "queued=$queuedBytes unconfirmed=$unconfirmedBytes abandoned=$abandonedBytes completed=$completedActivities " +
        "retained=${epochs.size} omitted=$omittedEpochs order_violations=$orderViolations unowned_source=$unownedSourceEvents " +
        "boundary_fields=ns,ingress,accepted,queued,unconfirmed stop_seen=${stopObserved?.detail() ?: "UNOBSERVED"} " +
        "input_eof=${inputEof?.detail() ?: "UNOBSERVED"} packetizer_discarded=$packetizerDiscardedBytes " +
        "ingress_unaccepted=${if (ingressBytes < 0) -1 else ingressBytes - acceptedBytes}"
}

/** Fixed-capacity metadata only. The serialized pump owner supplies all observations. */
internal class GeminiManualActivityTrace(private val nowNanos: () -> Long, private val capacity: Int) {
    private val origin = nowNanos()
    private val epochs = ArrayDeque<GeminiManualEpochTrace>()
    private var omitted = 0L
    private var previousTerminalQueue = -1
    private var lastSentInputEnd = -1L
    private var orderViolations = 0L
    private var unownedSourceEvents = 0L
    private var abandoned = 0L
    private var stop: GeminiManualInputBoundary? = null
    private var eof: GeminiManualInputBoundary? = null
    var acceptedBytes = 0L
        private set
    var ingressBytes = -1L
        private set

    init { require(capacity in 1..16) }
    fun relative(at: Long = nowNanos()): Long = (at - origin).coerceAtLeast(0)
    fun ingress(bytes: Int) { if (bytes > 0) ingressBytes = maxOf(0, ingressBytes) + bytes }
    fun accepted(bytes: Int): Long = acceptedBytes.also { acceptedBytes += bytes }
    fun start(epoch: Long) {
        if (epochs.size == capacity) { epochs.removeFirst(); omitted++ }
        epochs.addLast(GeminiManualEpochTrace(epoch, previousTerminalQueue))
    }
    private fun current(epoch: Long): GeminiManualEpochTrace? = epochs.peekLast()?.takeIf { it.epoch == epoch }
    fun attempted(epoch: Long, offset: Long, bytes: Int, acceptedNs: Long, ingressFirstNs: Long, ingressLastNs: Long) {
        current(epoch)?.let {
            if (it.attemptedFirst == -1L) { it.attemptedFirst = offset; it.acceptedFirstNs = acceptedNs }
            it.attemptedEnd = offset + bytes; it.attemptedBytes += bytes; it.acceptedLastNs = acceptedNs
            if (ingressFirstNs >= 0 && it.ingressFirstNs == -1L) it.ingressFirstNs = ingressFirstNs
            if (ingressLastNs >= 0) it.ingressLastNs = ingressLastNs
        }
    }
    fun sent(epoch: Long, offset: Long, bytes: Int, streamOffset: Long, active: Boolean, preRoll: Boolean) {
        if (lastSentInputEnd > offset) orderViolations++
        lastSentInputEnd = offset + bytes
        current(epoch)?.let {
            if (it.sentInputEnd >= 0 && it.sentInputEnd != offset) orderViolations++
            val at = relative()
            if (it.sentInputFirst == -1L) { it.sentInputFirst = offset; it.sentFirst = streamOffset; it.sentFirstNs = at }
            it.sentInputEnd = offset + bytes; it.sentEnd = streamOffset + bytes; it.sentLastNs = at
            if (active) {
                it.energyPackets++
                if (it.energyFirst == -1L) it.energyFirst = offset
                it.energyEnd = offset + bytes
            } else it.quietBytes += bytes
            if (preRoll) it.preRollBytes += bytes
        }
    }
    fun seal(epoch: Long, reason: GeminiManualSealReason) { current(epoch)?.seal = reason }
    fun endAck(epoch: Long) { current(epoch)?.endAckNs = relative() }
    fun terminal(epoch: Long, queued: Int) {
        current(epoch)?.let { it.terminalNs = relative(); it.queueAtTerminal = queued }
        previousTerminalQueue = queued
    }
    fun source(epoch: Long, ownsResponse: Boolean, receivedAt: Long) {
        if (!ownsResponse) { unownedSourceEvents++; return }
        current(epoch)?.let {
            val at = relative(receivedAt)
            if (it.sourceEvents == 0L) it.sourceFirstNs = at
            it.sourceEvents++; it.sourceLastNs = at
        }
    }
    // This is the first serialized observation of request=true, not the UI request instant.
    fun stopObserved(queued: Int, unconfirmed: Int) {
        if (stop == null) stop = boundary(queued, unconfirmed)
    }
    fun eof(queued: Int, unconfirmed: Int) { if (eof == null) eof = boundary(queued, unconfirmed) }
    private fun boundary(queued: Int, unconfirmed: Int) =
        GeminiManualInputBoundary(relative(), ingressBytes, acceptedBytes, queued, unconfirmed)
    fun discarded(bytes: Int) { abandoned += bytes }
    fun snapshot(sent: Long, suppressed: Long, queued: Int, unconfirmed: Int, completed: Long) =
        GeminiManualTraceSnapshot(acceptedBytes, ingressBytes, sent, suppressed, queued, unconfirmed, abandoned,
            completed, omitted, orderViolations, unownedSourceEvents, stop, eof, epochs.map { it.copy() })
}
