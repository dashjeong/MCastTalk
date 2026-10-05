package app.guidecast.transmitter

/** An unidentified provider interruption is not an operator stop command. */
internal fun cancelNativeAudioOutputTurn(
    sequence: Long?,
    retired: NativeAudioRetiredTurns,
    discard: (Long?) -> Unit,
    flush: (Long?) -> Unit,
    isCurrent: () -> Boolean = { true },
) {
    if (sequence == null || !isCurrent()) return
    retired.retire(sequence)
    discard(sequence)
    flush(sequence)
}

/** Caller holds the track's control lock. Only one output may occupy its device buffer. */
internal class NativeAudioPlaybackBoundary(initialEpoch: Long) {
    var sequence: Long? = null
        private set
    private var epoch = initialEpoch
    private var acceptedSamples = 0L
    private var previousHead: Long? = null
    private var wraps = 0L
    private var awaitingReset = false

    fun recordPriming(samples: Int) {
        require(samples >= 0)
        acceptedSamples += samples
    }

    fun resetAfterFlush(currentEpoch: Long) {
        epoch = currentEpoch
        sequence = null
        acceptedSamples = 0
        previousHead = null
        wraps = 0
        awaitingReset = true
    }

    fun flushIfMatches(outputSequence: Long?, nextEpoch: Long, isCurrent: () -> Boolean = { true }, flush: () -> Unit): Boolean {
        if (!isCurrent() || outputSequence != null && outputSequence != sequence) return false
        flush()
        resetAfterFlush(nextEpoch)
        return true
    }

    fun write(outputSequence: Long?, currentEpoch: Long, rawHead: Int, write: () -> Int): Int {
        if (outputSequence == null) return 0
        if (epoch != currentEpoch) resetAfterFlush(currentEpoch)
        val head = rawHead.toLong() and 0xffffffffL
        if (awaitingReset) {
            if (head != 0L) return 0
            awaitingReset = false
        }
        previousHead?.let { previous ->
            if (((head - previous) and 0xffffffffL) > 0x7fffffffL) return 0
            if (head < previous) wraps += 1L shl 32
        }
        previousHead = head
        if (sequence != null && sequence != outputSequence && head + wraps < acceptedSamples) return 0
        return write().also { count ->
            if (count > 0) {
                check(count % 2 == 0)
                acceptedSamples += count / 2
                sequence = outputSequence
            }
        }
    }
}
