package app.guidecast.transmitter

import java.util.ArrayDeque

/** Metadata for irreversibly suppressed input, never provider acknowledgement or recognized speech. */
internal class SuppressedSpeechEvidence {
    private data class Range(val start: Long, var end: Long)
    private val uncertain = ArrayDeque<Range>()
    private var lastEnd = 0L
    var unresolved: Boolean = false
        private set

    /** Offsets are half-open accepted PCM16 mono16k byte positions, including quiet gaps. */
    fun suppressed(offset: Long, bytes: Int, mayBeSpeech: Boolean) {
        require(offset >= lastEnd && bytes > 0 && bytes % 2 == 0)
        val end = offset + bytes
        lastEnd = end
        if (unresolved) return
        // Four full 32ms frames inside the most recent 320ms; short tails count only actual bytes.
        val windowStart = end - 10_240L
        while (uncertain.peekFirst()?.let { it.end <= windowStart } == true) uncertain.removeFirst()
        if (mayBeSpeech) {
            val last = uncertain.peekLast()
            if (last != null && last.end == offset) last.end = end
            else uncertain.addLast(Range(offset, end))
        }
        if (uncertain.sumOf { it.end - maxOf(it.start, windowStart) } >= 4_096L) {
            unresolved = true
            uncertain.clear()
        }
    }
}
