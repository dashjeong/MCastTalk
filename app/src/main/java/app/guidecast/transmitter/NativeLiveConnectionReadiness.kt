package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** A connection cannot become ready after close, nor activate capture on setup failure. */
internal class NativeLiveConnectionReadiness {
    private val result = CompletableDeferred<Unit>()
    private var closed = false
    @Synchronized fun markReady(): Boolean {
        if (closed) return false
        result.complete(Unit)
        return true
    }
    @Synchronized fun close() {
        closed = true
        result.completeExceptionally(IllegalStateException("Native connection closed before input activation"))
    }
    suspend fun awaitReady(timeoutMillis: Long = 12_000) {
        require(timeoutMillis > 0)
        withTimeout(timeoutMillis) { result.await() }
        synchronized(this) { check(!closed) { "Native connection ended before input activation" } }
    }
}
