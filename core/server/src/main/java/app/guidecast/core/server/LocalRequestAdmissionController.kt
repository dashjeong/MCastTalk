package app.guidecast.core.server

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, in-memory admission guard for the hotspot server.
 *
 * The remote address comes from the accepted socket, never from a forwarded-IP header. State is
 * deliberately bounded because an admission controller must not itself become an allocation DoS.
 */
internal class LocalRequestAdmissionController(
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val maxTrackedRemotes: Int = DEFAULT_MAX_TRACKED_REMOTES,
    private val idleRetentionMillis: Long = DEFAULT_IDLE_RETENTION_MILLIS,
    pageLimit: LocalRequestLimit = LocalRequestLimit(
        maxRequestsPerWindow = 180,
        maxConcurrentRequests = 8,
    ),
    onboardingLimit: LocalRequestLimit = LocalRequestLimit(
        maxRequestsPerWindow = 30,
        maxConcurrentRequests = 3,
    ),
    readApiLimit: LocalRequestLimit = LocalRequestLimit(
        // The listener polls transcripts every 1.2 seconds. Keep normal use comfortably below
        // this ceiling while stopping an unbounded tight polling loop.
        maxRequestsPerWindow = 90,
        maxConcurrentRequests = 3,
    ),
    webSocketLimit: LocalRequestLimit = LocalRequestLimit(
        // This lease remains owned for the full WebSocket lifetime. Eight tabs from one handset
        // are ample for recovery/testing, while 14-50 distinct hotspot peers remain independent.
        maxRequestsPerWindow = 120,
        maxConcurrentRequests = 8,
    ),
    private val timeSourceMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val limits = mapOf(
        LocalRequestKind.PAGE_ASSET to pageLimit,
        LocalRequestKind.ONBOARDING to onboardingLimit,
        LocalRequestKind.READ_API to readApiLimit,
        LocalRequestKind.WEBSOCKET_HANDSHAKE to webSocketLimit,
    )
    private val remoteStates = LinkedHashMap<String, RemoteState>()

    init {
        require(windowMillis > 0L) { "windowMillis must be positive" }
        require(maxTrackedRemotes > 0) { "maxTrackedRemotes must be positive" }
        require(idleRetentionMillis >= windowMillis) {
            "idleRetentionMillis must cover at least one request window"
        }
    }

    @Synchronized
    fun tryAcquire(
        remoteAddress: String,
        kind: LocalRequestKind,
    ): LocalRequestAdmission {
        val now = timeSourceMillis()
        val remoteKey = remoteAddress.toRemoteKey()
        val remoteState = remoteStates[remoteKey] ?: run {
            pruneInactiveRemotes(now)
            if (remoteStates.size >= maxTrackedRemotes) {
                return LocalRequestAdmission.Rejected(retryAfterSeconds = 1L)
            }
            RemoteState(lastSeenAtMillis = now).also { remoteStates[remoteKey] = it }
        }
        remoteState.lastSeenAtMillis = now

        val bucket = remoteState.buckets.getOrPut(kind) {
            RequestBucket(windowStartedAtMillis = now)
        }
        if (now < bucket.windowStartedAtMillis || now - bucket.windowStartedAtMillis >= windowMillis) {
            bucket.windowStartedAtMillis = now
            bucket.requestsInWindow = 0
        }

        val limit = limits.getValue(kind)
        if (bucket.requestsInWindow >= limit.maxRequestsPerWindow) {
            val remainingMillis = (windowMillis - (now - bucket.windowStartedAtMillis))
                .coerceAtLeast(1L)
            return LocalRequestAdmission.Rejected(
                retryAfterSeconds = ((remainingMillis - 1L) / 1_000L) + 1L,
            )
        }

        // Concurrent rejections also consume their rate-window attempt. Otherwise a client can
        // spin indefinitely against the cheaper concurrency branch.
        bucket.requestsInWindow += 1
        if (bucket.activeRequests >= limit.maxConcurrentRequests) {
            return LocalRequestAdmission.Rejected(retryAfterSeconds = 1L)
        }
        bucket.activeRequests += 1

        return LocalRequestAdmission.Allowed(
            AdmissionLease {
                release(remoteKey, kind)
            },
        )
    }

    @Synchronized
    private fun release(remoteKey: String, kind: LocalRequestKind) {
        val state = remoteStates[remoteKey] ?: return
        val bucket = state.buckets[kind] ?: return
        if (bucket.activeRequests > 0) bucket.activeRequests -= 1
        state.lastSeenAtMillis = timeSourceMillis()
    }

    @Synchronized
    internal fun activeRequestsForTest(kind: LocalRequestKind): Int =
        remoteStates.values.sumOf { state -> state.buckets[kind]?.activeRequests ?: 0 }

    private fun pruneInactiveRemotes(now: Long) {
        val iterator = remoteStates.entries.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next().value
            val clockMovedBackwards = now < state.lastSeenAtMillis
            val isIdle = state.buckets.values.all { it.activeRequests == 0 }
            val retentionExpired = !clockMovedBackwards &&
                now - state.lastSeenAtMillis >= idleRetentionMillis
            if (isIdle && retentionExpired) iterator.remove()
        }
    }

    private fun String.toRemoteKey(): String {
        val normalized = trim()
        return when {
            normalized.isEmpty() -> UNKNOWN_REMOTE
            normalized.length > MAX_REMOTE_KEY_LENGTH -> UNKNOWN_REMOTE
            else -> normalized
        }
    }

    private data class RemoteState(
        var lastSeenAtMillis: Long,
        val buckets: MutableMap<LocalRequestKind, RequestBucket> = mutableMapOf(),
    )

    private data class RequestBucket(
        var windowStartedAtMillis: Long,
        var requestsInWindow: Int = 0,
        var activeRequests: Int = 0,
    )

    private class AdmissionLease(
        private val releaseAction: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) releaseAction()
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val DEFAULT_IDLE_RETENTION_MILLIS = 120_000L
        const val DEFAULT_MAX_TRACKED_REMOTES = 256
        const val MAX_REMOTE_KEY_LENGTH = 128
        const val UNKNOWN_REMOTE = "<unknown>"
    }
}

internal enum class LocalRequestKind {
    PAGE_ASSET,
    ONBOARDING,
    READ_API,
    WEBSOCKET_HANDSHAKE,
}

internal data class LocalRequestLimit(
    val maxRequestsPerWindow: Int,
    val maxConcurrentRequests: Int,
) {
    init {
        require(maxRequestsPerWindow > 0) { "maxRequestsPerWindow must be positive" }
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be positive" }
    }
}

internal sealed interface LocalRequestAdmission {
    data class Allowed(val lease: Closeable) : LocalRequestAdmission

    data class Rejected(val retryAfterSeconds: Long) : LocalRequestAdmission
}
