package app.guidecast.transmitter

import java.util.ArrayDeque

internal sealed interface GeminiManualCommand {
    val epoch: Long
    data class Start(override val epoch: Long) : GeminiManualCommand
    data class Audio(override val epoch: Long, val bytes: ByteArray) : GeminiManualCommand
    data class End(override val epoch: Long) : GeminiManualCommand
}

internal class GeminiManualActivityFailure(val reason: Reason, val rejectedBytes: Int = 0) :
    IllegalStateException("Manual audio activity ${reason.name.lowercase()}") {
    enum class Reason { OVERFLOW, INTERRUPTED, NO_OUTPUT }
}

/** All calls belong to one serialized owner. Never hold this object across a suspended send. */
internal class GeminiManualActivityPump(
    private val maximumQueuedBytes: Int = 640_000,
    silenceMillis: Int = 700,
    preRollMillis: Int = 320,
    maximumActivityMillis: Int = 12_000,
    nowNanos: () -> Long = System::nanoTime,
    traceCapacity: Int = 16,
) {
    private data class Packet(
        val bytes: ByteArray, val active: Boolean, val offset: Long, val acceptedNs: Long,
        val ingressFirstNs: Long, val ingressLastNs: Long, val preRoll: Boolean = false,
    ) {
        fun suffix(from: Int) = copy(bytes = bytes.copyOfRange(from, bytes.size), offset = offset + from)
    }
    private val trace = GeminiManualActivityTrace(nowNanos, traceCapacity)
    private enum class Phase { IDLE, OPEN, AWAITING_TERMINAL }
    private val queue = ArrayDeque<Packet>()
    private val preRoll = ArrayDeque<Packet>()
    private val silenceBytes = bytesForMillis(silenceMillis)
    private val maximumPreRollBytes = bytesForMillis(preRollMillis)
    private val maximumActivityBytes = bytesForMillis(maximumActivityMillis)
    private var phase = Phase.IDLE
    private var queueSize = 0
    private var preRollSize = 0
    private var inFlight: GeminiManualCommand? = null
    private var inFlightPacket: Packet? = null
    private var activityBytes = 0
    private var trailingQuietBytes = 0
    private var sealRequested = false
    private var epoch = 0L
    private var ended = false
    private var aborted = false
    var suppressedIdleBytes: Long = 0
        private set
    var completedActivities: Long = 0
        private set
    var deliveredAudioBytes: Long = 0
        private set

    init {
        require(maximumQueuedBytes >= 3_200 && maximumQueuedBytes % 2 == 0)
        require(silenceMillis > 0 && preRollMillis >= 0 && maximumActivityMillis > 0)
        require(maximumPreRollBytes < silenceBytes)
        require(maximumActivityBytes >= 3_200 && maximumQueuedBytes >= maximumPreRollBytes + 3_200)
    }

    val inputEnded: Boolean get() = ended
    val hasOpenActivity: Boolean get() = phase == Phase.OPEN || inFlight is GeminiManualCommand.Start
    val activeEndSent: Boolean get() = phase == Phase.AWAITING_TERMINAL
    val activeEpoch: Long get() = epoch
    val queuedBytes: Int get() = queueSize + preRollSize
    val unconfirmedAudioBytes: Int get() = (inFlight as? GeminiManualCommand.Audio)?.bytes?.size ?: 0
    val hasPending: Boolean get() = phase != Phase.IDLE || inFlight != null || queuedBytes > 0

    /** active is only a segmentation hint; its silence suppression is counted, never an ACK. */
    fun accept(packet: ByteArray, active: Boolean, ingressFirstNanos: Long? = null, ingressLastNanos: Long? = ingressFirstNanos) {
        check(!ended) { "Input already ended" }
        require(packet.isNotEmpty() && packet.size <= 3_200 && packet.size % 2 == 0)
        if (packet.size > maximumQueuedBytes - queuedBytes - unconfirmedAudioBytes)
            throw GeminiManualActivityFailure(GeminiManualActivityFailure.Reason.OVERFLOW, packet.size)
        queue.addLast(Packet(packet.copyOf(), active, trace.accepted(packet.size), trace.relative(),
            ingressFirstNanos?.let(trace::relative) ?: -1, ingressLastNanos?.let(trace::relative) ?: -1))
        queueSize += packet.size
        if (phase == Phase.IDLE && inFlight == null) prepareIdle()
    }

    fun observeIngress(bytes: Int) { trace.ingress(bytes) }
    fun observeStopRequest() { trace.stopObserved(queuedBytes, unconfirmedAudioBytes) }
    fun observeSource(receivedAtNanos: Long) { trace.source(epoch, activeEndSent, receivedAtNanos) }
    fun traceSnapshot(): GeminiManualTraceSnapshot = trace.snapshot(deliveredAudioBytes, suppressedIdleBytes,
        queuedBytes, unconfirmedAudioBytes, completedActivities)

    fun endInput() { trace.eof(queuedBytes, unconfirmedAudioBytes); ended = true }

    /** Seal only the audio already sent. Untouched queued input remains for the next activity. */
    fun sealCurrentActivity() {
        if (aborted) return
        if (phase == Phase.OPEN || inFlight is GeminiManualCommand.Start) sealRequested = true
    }

    /** One command may be outstanding. Audio is removed from the untouched queue at this point. */
    fun nextCommand(): GeminiManualCommand? {
        if (aborted) return null
        check(inFlight == null) { "A wire command is still unconfirmed" }
        val command = when (phase) {
            Phase.AWAITING_TERMINAL -> null
            Phase.IDLE -> {
                prepareIdle()
                if (queue.isEmpty()) {
                    if (ended) suppressPreRoll()
                    null
                } else {
                    epoch++
                    trace.start(epoch)
                    GeminiManualCommand.Start(epoch)
                }
            }
            Phase.OPEN -> {
                if (activityBytes > 0 && (sealRequested || activityBytes >= maximumActivityBytes ||
                        trailingQuietBytes >= silenceBytes || (ended && queue.isEmpty()))) {
                    trace.seal(epoch, when {
                        sealRequested -> GeminiManualSealReason.EXPLICIT_SEAL
                        activityBytes >= maximumActivityBytes -> GeminiManualSealReason.MAX_DURATION
                        trailingQuietBytes >= silenceBytes -> GeminiManualSealReason.QUIET
                        else -> GeminiManualSealReason.INPUT_EOF
                    })
                    GeminiManualCommand.End(epoch)
                } else if (queue.isEmpty()) null else {
                    val first = queue.removeFirst()
                    val count = minOf(first.bytes.size, maximumActivityBytes - activityBytes)
                    check(count > 0)
                    val data = if (count == first.bytes.size) first.bytes else first.bytes.copyOfRange(0, count)
                    if (count < first.bytes.size)
                        queue.addFirst(first.suffix(count))
                    queueSize -= count
                    // Retain the classification until this exact packet send is confirmed.
                    inFlightActive = first.active
                    inFlightPacket = first.copy(bytes = data)
                    trace.attempted(epoch, first.offset, count, first.acceptedNs, first.ingressFirstNs, first.ingressLastNs)
                    GeminiManualCommand.Audio(epoch, data)
                }
            }
        }
        inFlight = command
        return command
    }

    private var inFlightActive = false

    /** Call only after the exact send returned successfully. Failure must terminate this pump. */
    fun ack(command: GeminiManualCommand) {
        check(!aborted) { "Activity pump is closed" }
        check(command === inFlight) { "Unexpected command acknowledgement" }
        when (command) {
            is GeminiManualCommand.Start -> {
                check(phase == Phase.IDLE)
                phase = Phase.OPEN; activityBytes = 0; trailingQuietBytes = 0
            }
            is GeminiManualCommand.Audio -> {
                check(phase == Phase.OPEN)
                inFlightPacket?.let { trace.sent(epoch, it.offset, command.bytes.size, deliveredAudioBytes, it.active, it.preRoll) }
                activityBytes += command.bytes.size; deliveredAudioBytes += command.bytes.size
                trailingQuietBytes = if (inFlightActive) 0 else trailingQuietBytes + command.bytes.size
            }
            is GeminiManualCommand.End -> {
                check(phase == Phase.OPEN && activityBytes > 0)
                phase = Phase.AWAITING_TERMINAL
                trace.endAck(epoch)
            }
        }
        inFlight = null
        inFlightPacket = null
    }

    /** hasOutput must aggregate only this activity's published text/audio, not an earlier one. */
    fun turnComplete(interrupted: Boolean, hasOutput: Boolean): Boolean {
        if (aborted) return false
        if (interrupted) throw GeminiManualActivityFailure(GeminiManualActivityFailure.Reason.INTERRUPTED)
        if (phase != Phase.AWAITING_TERMINAL || inFlight != null) return false
        if (!hasOutput) throw GeminiManualActivityFailure(GeminiManualActivityFailure.Reason.NO_OUTPUT)
        completedActivities++
        trace.terminal(epoch, queuedBytes)
        phase = Phase.IDLE; activityBytes = 0; trailingQuietBytes = 0; sealRequested = false
        return true
    }

    /** Returns only never-attempted bytes; the caller separately accounts for an in-flight send. */
    fun discard(): Int {
        val bytes = queuedBytes
        trace.discarded(bytes)
        queue.clear(); preRoll.clear(); queueSize = 0; preRollSize = 0
        ended = true; aborted = true
        return bytes
    }

    private fun prepareIdle() {
        while (queue.isNotEmpty() && !queue.first.active) {
            val packet = queue.removeFirst(); queueSize -= packet.bytes.size
            preRoll.addLast(packet.copy(preRoll = true)); preRollSize += packet.bytes.size
            trimPreRoll()
        }
        if (queue.isNotEmpty()) {
            while (preRoll.isNotEmpty()) queue.addFirst(preRoll.removeLast())
            queueSize += preRollSize; preRollSize = 0
        }
    }

    private fun trimPreRoll() {
        while (preRollSize > maximumPreRollBytes) {
            val packet = preRoll.removeFirst()
            val excess = minOf(packet.bytes.size, preRollSize - maximumPreRollBytes)
            preRollSize -= excess; suppressedIdleBytes += excess
            if (excess < packet.bytes.size)
                preRoll.addFirst(packet.suffix(excess))
        }
    }

    private fun suppressPreRoll() {
        suppressedIdleBytes += preRollSize
        preRoll.clear(); preRollSize = 0
    }

    private fun bytesForMillis(value: Int): Int {
        require(value in 0..60_000)
        return value * 32
    }
}
