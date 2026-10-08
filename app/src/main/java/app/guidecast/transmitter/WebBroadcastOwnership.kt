package app.guidecast.transmitter

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/** One application-local owner of the LAN ports and shared audio channel registry. */
internal class WebBroadcastOwnership {
    private val owner = AtomicReference<WebBroadcastLease?>(null)
    private val starts = java.util.concurrent.atomic.AtomicLong()
    internal val generation: Long get() = starts.get()
    val isOwned: Boolean get() = owner.get() != null
    val currentOwner: String? get() = owner.get()?.owner
    @Synchronized fun tryAcquire(ownerName: String): WebBroadcastLease? {
        require(ownerName.isNotBlank())
        val lease = WebBroadcastLease(ownerName) { released -> synchronized(this) { owner.compareAndSet(released, null) } }
        return if (owner.compareAndSet(null, lease)) { starts.incrementAndGet(); lease } else null
    }
}

internal class WebBroadcastLease internal constructor(val owner: String,
    private val release: (WebBroadcastLease) -> Unit) : Closeable {
    override fun close() { release(this) }
}
