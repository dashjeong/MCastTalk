package app.guidecast.core.stream

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes whole audio items per channel without coupling sibling-language channels.
 *
 * Normal speech takes one channel lease. An operator test tone takes every lease in a stable
 * order, so it starts after in-flight speech finishes and no translated PCM frame can be woven
 * between tone frames. Different language workers retain independent locks and still run in
 * parallel.
 */
class ChannelAudioPublicationCoordinator(channelIds: Collection<String>) {
    private val orderedChannelIds = channelIds.distinct().sorted()
    private val mutexes = orderedChannelIds.associateWith { Mutex() }

    init {
        require(orderedChannelIds.isNotEmpty()) { "At least one audio channel is required" }
        require(orderedChannelIds.size == channelIds.size) { "Audio channel ids must be unique" }
        require(orderedChannelIds.none(String::isBlank)) { "Audio channel ids must not be blank" }
    }

    suspend fun acquireChannel(channelId: String): Closeable {
        val mutex = requireNotNull(mutexes[channelId]) { "Unknown audio channel: $channelId" }
        mutex.lock()
        return MutexLease(listOf(mutex))
    }

    /**
     * Acquires every channel atomically within [timeoutMillis].
     *
     * A failed pass releases every partial lease before suspending. This is important when one
     * language's native TTS is stuck: an operator tone may wait briefly, but must never retain a
     * healthy sibling lock or suppress live input indefinitely.
     */
    suspend fun acquireAllChannels(timeoutMillis: Long): Closeable? {
        require(timeoutMillis > 0L) { "All-channel acquisition timeout must be positive" }
        var completedLease: MutexLease? = null
        var returningLease = false
        try {
            val completed = withTimeoutOrNull(timeoutMillis) {
                while (completedLease == null) {
                    val acquired = ArrayList<Mutex>(orderedChannelIds.size)
                    try {
                        for (channelId in orderedChannelIds) {
                            val mutex = requireNotNull(mutexes[channelId])
                            if (!mutex.tryLock()) break
                            acquired += mutex
                        }
                        if (acquired.size == orderedChannelIds.size) {
                            completedLease = MutexLease(acquired.toList())
                            acquired.clear()
                        }
                    } finally {
                        acquired.asReversed().forEach(Mutex::unlock)
                    }
                    if (completedLease == null) delay(ALL_CHANNEL_RETRY_MILLIS)
                }
                true
            }
            if (completed == true) {
                returningLease = true
                return completedLease
            }
            return null
        } finally {
            if (!returningLease) completedLease?.close()
        }
    }

    private companion object {
        const val ALL_CHANNEL_RETRY_MILLIS = 15L
    }
}

private class MutexLease(
    private val mutexes: List<Mutex>,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutexes.asReversed().forEach(Mutex::unlock)
    }
}
