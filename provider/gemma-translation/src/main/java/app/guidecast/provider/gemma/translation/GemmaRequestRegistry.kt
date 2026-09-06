package app.guidecast.provider.gemma.translation

import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates callback completion with Binder death-recipient linking without leaking the link. */
internal class GemmaRequestCompletionState {
    private val completed = AtomicBoolean(false)
    private val deathRecipientLinked = AtomicBoolean(false)

    val isCompleted: Boolean
        get() = completed.get()

    fun mayReleaseDeathRecipient(
        nativeSubmissionPlanned: Boolean,
        nativeFinished: Boolean,
    ): Boolean = completed.get() && (!nativeSubmissionPlanned || nativeFinished)

    fun tryComplete(): Boolean = completed.compareAndSet(false, true)

    /** Returns true when completion won before or while the link was being installed. */
    fun markDeathRecipientLinked(): Boolean {
        deathRecipientLinked.set(true)
        return completed.get()
    }

    /** Returns true exactly once for a link that must be unregistered. */
    fun takeDeathRecipientLink(): Boolean = deathRecipientLinked.compareAndSet(true, false)
}

/**
 * Owns cancellable service work and distinguishes a client timeout from server-side teardown.
 *
 * A client timeout has already resumed its continuation and therefore needs no callback. Reset or
 * service destruction, however, must wake a still-active client instead of leaving it suspended
 * until its (potentially long) local deadline. The server-side terminal signal is emitted once even
 * when reset and onDestroy race.
 */
internal class GemmaCancellableRequest<J : Any>(
    val work: J,
    private val cancelWork: (J) -> Unit,
    private val reportServerCancellation: (String) -> Unit,
) {
    private val serverCancellationReported = AtomicBoolean(false)

    fun cancelFromClient() {
        cancelWork(work)
    }

    fun cancelFromServer(message: String) {
        if (serverCancellationReported.compareAndSet(false, true)) {
            reportServerCancellation(message)
        }
        cancelWork(work)
    }
}

/** Thread-safe request ownership used by concurrent one-way Binder transactions. */
internal class GemmaRequestRegistry<J : Any>(
    private val maxPendingRequests: Int,
    private val maxEarlyCancellations: Int = 32,
) {
    private val lock = Any()
    private val jobs = mutableMapOf<Long, J>()
    private val cancelledBeforeArrival = LinkedHashSet<Long>()
    private var cancelledThroughRequestId = NO_REQUEST_ID
    private var acceptingRequests = true

    init {
        require(maxPendingRequests > 0)
        require(maxEarlyCancellations > 0)
    }

    fun register(requestId: Long, job: J): GemmaRequestAdmission = synchronized(lock) {
        when {
            !acceptingRequests -> GemmaRequestAdmission.CLOSED
            requestId < 0L || requestId <= cancelledThroughRequestId ||
                cancelledBeforeArrival.remove(requestId) -> GemmaRequestAdmission.OBSOLETE
            jobs.containsKey(requestId) || jobs.size >= maxPendingRequests ->
                GemmaRequestAdmission.BUSY
            else -> {
                jobs[requestId] = job
                GemmaRequestAdmission.ACCEPTED
            }
        }
    }

    /**
     * Returns the registered job, or records a bounded tombstone if cancel overtook registration
     * on another Binder thread.
     */
    fun cancel(requestId: Long): J? = synchronized(lock) {
        jobs[requestId] ?: run {
            if (acceptingRequests && requestId >= 0L && requestId > cancelledThroughRequestId) {
                cancelledBeforeArrival += requestId
                while (cancelledBeforeArrival.size > maxEarlyCancellations) {
                    val oldest = cancelledBeforeArrival.iterator().next()
                    cancelledBeforeArrival.remove(oldest)
                }
            }
            null
        }
    }

    /**
     * Cancels only requests allocated before the caller issued reset. A delayed one-way reset can
     * therefore never cancel a later session's higher request ids.
     */
    fun resetThrough(requestId: Long): List<J>? = synchronized(lock) {
        if (!acceptingRequests) return@synchronized null
        if (requestId > cancelledThroughRequestId) {
            cancelledThroughRequestId = requestId
            cancelledBeforeArrival.removeAll { it <= cancelledThroughRequestId }
        }
        jobs.filterKeys { it <= cancelledThroughRequestId }.values.toList()
    }

    fun complete(requestId: Long, job: J): Boolean = synchronized(lock) {
        if (jobs[requestId] !== job) {
            false
        } else {
            jobs.remove(requestId)
            true
        }
    }

    fun close(): List<J> = synchronized(lock) {
        acceptingRequests = false
        cancelledBeforeArrival.clear()
        jobs.values.toList()
    }

    internal fun pendingCount(): Int = synchronized(lock) { jobs.size }

    private companion object {
        const val NO_REQUEST_ID = -1L
    }
}

internal enum class GemmaRequestAdmission {
    ACCEPTED,
    OBSOLETE,
    BUSY,
    CLOSED,
}
