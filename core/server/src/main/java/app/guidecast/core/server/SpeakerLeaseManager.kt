package app.guidecast.core.server

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface SpeakerLeaseResult {
    data class Granted(val lease: SpeakerLease) : SpeakerLeaseResult
    data object Busy : SpeakerLeaseResult
}

interface SpeakerLease : AutoCloseable {
    val generation: Long
}

/**
 * Thread-safe single-speaker lease coordinator.
 *
 * Guarantees that only ONE active speaker can stream to the transmitter at any time.
 * Preemption is strictly prohibited without an explicit lease release or silence timeout.
 */
class SpeakerLeaseManager(
    private val idleTimeoutNanos: Long = 5_000_000_000L, // 5 seconds
) {
    private val generationSequence = AtomicLong(0L)
    private val currentLease = AtomicReference<ActiveLease?>(null)

    private inner class ActiveLease(
        val remoteId: String,
        override val generation: Long,
        @Volatile var lastActiveNanos: Long,
    ) : SpeakerLease {
        override fun close() {
            currentLease.compareAndSet(this, null)
        }
    }

    fun tryAcquire(remoteId: String, nowNanos: Long = System.nanoTime()): SpeakerLeaseResult {
        while (true) {
            val existing = currentLease.get()
            if (existing != null) {
                // Check if existing lease has expired due to silence/inactivity
                if (nowNanos - existing.lastActiveNanos > idleTimeoutNanos) {
                    if (currentLease.compareAndSet(existing, null)) {
                        continue
                    }
                } else {
                    return SpeakerLeaseResult.Busy
                }
            }
            val newGen = generationSequence.incrementAndGet()
            val created = ActiveLease(
                remoteId = remoteId,
                generation = newGen,
                lastActiveNanos = nowNanos,
            )
            if (currentLease.compareAndSet(null, created)) {
                return SpeakerLeaseResult.Granted(created)
            }
        }
    }

    fun recordActivity(generation: Long, nowNanos: Long = System.nanoTime()): Boolean {
        val active = currentLease.get()
        if (active != null && active.generation == generation) {
            active.lastActiveNanos = nowNanos
            return true
        }
        return false
    }

    fun isLeaseActive(nowNanos: Long = System.nanoTime()): Boolean {
        val active = currentLease.get() ?: return false
        return (nowNanos - active.lastActiveNanos) <= idleTimeoutNanos
    }
}
