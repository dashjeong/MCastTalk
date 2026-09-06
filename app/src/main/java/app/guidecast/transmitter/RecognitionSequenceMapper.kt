package app.guidecast.transmitter

import java.util.concurrent.atomic.AtomicLong

/** Preserves provider utterance identity while allocating app-session-unique source ids. */
internal class RecognitionSequenceMapper(
    private val nextSequence: AtomicLong,
) {
    private val active = mutableMapOf<Long, Long>()

    fun map(providerSequence: Long): Long = active.getOrPut(providerSequence) {
        nextSequence.getAndIncrement()
    }

    fun complete(providerSequence: Long) {
        active.remove(providerSequence)
    }

    fun clear() {
        active.clear()
    }
}
