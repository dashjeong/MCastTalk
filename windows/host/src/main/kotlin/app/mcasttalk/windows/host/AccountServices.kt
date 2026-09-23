package app.mcasttalk.windows.host

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class AccountServices(
    val accounts: LocalAccounts,
    val sessions: LocalSessions = LocalSessions(accounts),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { sessions.close() } finally { accounts.close() }
        }
    }

    companion object {
        fun open(dataRoot: Path): AccountServices = AccountServices(LocalAccounts(dataRoot))
    }
}

internal fun LocalAccount.canEnterRoom(roomId: String): Boolean =
    role != AccountRole.GUEST || guestRoomId == roomId
