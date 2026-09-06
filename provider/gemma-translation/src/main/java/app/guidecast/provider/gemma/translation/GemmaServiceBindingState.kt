package app.guidecast.provider.gemma.translation

/**
 * Owns exactly one service connection and its published Binder proxy.
 *
 * Android may deliver a service callback after the coroutine that requested the binding was
 * cancelled. Identity-based release keeps that obsolete callback from clearing a newer session.
 */
internal class GemmaServiceBindingState<C : Any, S : Any> {
    private val lock = Any()
    private var connection: C? = null
    private var service: S? = null

    fun snapshot(): GemmaServiceBindingSnapshot<C, S> = synchronized(lock) {
        GemmaServiceBindingSnapshot(connection, service)
    }

    fun install(candidate: C): Boolean = synchronized(lock) {
        if (connection != null || service != null) {
            false
        } else {
            connection = candidate
            true
        }
    }

    fun publishIfOwned(candidate: C, connectedService: S, accepting: Boolean): Boolean =
        synchronized(lock) {
            if (connection === candidate && service == null && accepting) {
                service = connectedService
                true
            } else {
                false
            }
        }

    /**
     * A null expected connection releases the current owner. A non-null expected connection may
     * release only itself; stale callbacks receive [owned] = false and must leave the new owner
     * untouched.
     */
    fun release(expectedConnection: C?): GemmaServiceBindingRelease<C, S> = synchronized(lock) {
        if (expectedConnection != null && connection !== expectedConnection) {
            GemmaServiceBindingRelease(owned = false, connection = null, service = null)
        } else {
            GemmaServiceBindingRelease(
                owned = true,
                connection = connection,
                service = service,
            ).also {
                connection = null
                service = null
            }
        }
    }
}

internal data class GemmaServiceBindingSnapshot<C : Any, S : Any>(
    val connection: C?,
    val service: S?,
)

internal data class GemmaServiceBindingRelease<C : Any, S : Any>(
    val owned: Boolean,
    val connection: C?,
    val service: S?,
)
