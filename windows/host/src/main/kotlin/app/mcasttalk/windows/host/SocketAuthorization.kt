package app.mcasttalk.windows.host

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal class SocketAuthorizations(
    private val services: AccountServices,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val entries = ConcurrentHashMap<String, Authorization>()
    private val subscription = services.sessions.onRevoked { accountId ->
        // A logout revokes just its token. Other valid sessions of the same account stay connected.
        entries.values.filter { it.accountId == accountId }.forEach { it.account(touch = false) }
    }
    private val expirySweep = scope.launch {
        while (isActive) {
            delay(1_000)
            entries.values.forEach { it.account(touch = false) }
        }
    }

    inner class Authorization internal constructor(
        internal val accountId: String,
        private val token: String,
        private val roomId: String,
        private val session: DefaultWebSocketServerSession,
    ) : AutoCloseable {
        private val id = UUID.randomUUID().toString()
        private val revoked = AtomicBoolean(false)

        init { entries[id] = this }

        fun account(touch: Boolean): LocalAccount? {
            if (revoked.get()) return null
            val account = services.sessions.resolve(token, touch)
                ?.takeIf { it.id == accountId && it.canEnterRoom(roomId) }
            if (account == null) reject()
            return account
        }

        fun reject() {
            if (!revoked.compareAndSet(false, true)) return
            entries.remove(id, this)
            scope.launch {
                try {
                    withTimeoutOrNull(1_000) {
                        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication expired or revoked"))
                    }
                } finally {
                    session.cancel(CancellationException("Authentication expired or revoked"))
                }
            }
        }

        override fun close() { entries.remove(id, this) }
    }

    fun register(account: LocalAccount, token: String, roomId: String, session: DefaultWebSocketServerSession): Authorization =
        Authorization(account.id, token, roomId, session)

    override fun close() {
        expirySweep.cancel()
        subscription.close()
        entries.values.forEach { it.reject() }
        entries.clear()
    }
}
