package app.mcasttalk.windows.host

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory opaque sessions: no raw token is retained, persisted or written to diagnostics. */
class LocalSessions(
    private val accounts: LocalAccounts,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private data class Session(val accountId: String, val version: Int, val issuedAt: Instant, var lastSeenAt: Instant)
    private val random = SecureRandom()
    private val monitor = Any()
    private val sessions = linkedMapOf<String, Session>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val notificationMonitor = Any()
    private val pendingNotifications = linkedSetOf<String>()
    private var notificationOwner: Any? = null
    private var closed = false
    private val accountSubscription = accounts.onChanged(::revokeStaleAccountSessions)

    /** Requires the exact account version returned by password authentication, not just its id. */
    fun issue(authenticated: LocalAccount): String? {
        val revoked = linkedSetOf<String>()
        val token = synchronized(monitor) {
            if (closed) return@synchronized null
            val now = clock.instant()
            val iterator = sessions.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (currentFor(entry.value, now) == null) { revoked.add(entry.value.accountId); iterator.remove() }
            }
            val current = runCatching { accounts.activeAccount(authenticated.id) }.getOrNull()
            if (current == null || current.securityVersion != authenticated.securityVersion || sessions.size >= MAX_SESSIONS) {
                return@synchronized null
            }
            val raw = ByteArray(32).also(random::nextBytes)
            val value = try { Base64.getUrlEncoder().withoutPadding().encodeToString(raw) } finally { raw.fill(0) }
            val key = tokenKey(value)!!
            check(!sessions.containsKey(key)) { "Cannot allocate session" }
            sessions[key] = Session(current.id, current.securityVersion, now, now)
            value
        }
        revoked.forEach(::notifyRevoked)
        return token
    }

    /** WS sweep/outbound checks use touch=false; only authenticated activity extends idle time. */
    fun resolve(token: String, touch: Boolean = true): LocalAccount? {
        val key = tokenKey(token) ?: return null
        var revoked: String? = null
        val result = synchronized(monitor) {
            if (closed) return@synchronized null
            val session = sessions[key] ?: return@synchronized null
            val now = clock.instant()
            val current = currentFor(session, now)
            if (current == null) {
                sessions.remove(key)
                revoked = session.accountId
                return@synchronized null
            }
            if (touch) session.lastSeenAt = now
            current
        }
        revoked?.let(::notifyRevoked)
        return result
    }

    fun revoke(token: String) {
        val key = tokenKey(token) ?: return
        val removed = synchronized(monitor) { sessions.remove(key)?.accountId }
        removed?.let(::notifyRevoked)
    }

    fun revokeAccount(accountId: String) {
        synchronized(monitor) { sessions.entries.removeIf { it.value.accountId == accountId } }
        // Listeners receive only account ids; they can re-resolve their own token without touch.
        notifyRevoked(accountId)
    }

    private fun revokeStaleAccountSessions(accountId: String) {
        synchronized(monitor) {
            val current = runCatching { accounts.activeAccount(accountId) }.getOrNull()
            // A valid login may finish after the account commit but before this callback.
            // Keep that new-version session; revoke only credentials that were invalidated.
            sessions.entries.removeIf { it.value.accountId == accountId &&
                (current == null || it.value.version != current.securityVersion) }
        }
        notifyRevoked(accountId)
    }

    fun onRevoked(listener: (String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        accountSubscription.close()
        val revoked = synchronized(monitor) {
            if (closed) return
            closed = true
            val ids = sessions.values.map { it.accountId }.distinct()
            sessions.clear()
            ids
        }
        revoked.forEach(::notifyRevoked)
        listeners.clear()
    }

    private fun currentFor(session: Session, now: Instant): LocalAccount? {
        if (now.isBefore(session.issuedAt) || now.isBefore(session.lastSeenAt) ||
            !now.isBefore(session.issuedAt.plusSeconds(ABSOLUTE_SECONDS)) ||
            !now.isBefore(session.lastSeenAt.plusSeconds(IDLE_SECONDS))) return null
        val account = runCatching { accounts.activeAccount(session.accountId) }.getOrNull()
        return account?.takeIf { it.securityVersion == session.version }
    }

    private fun notifyRevoked(accountId: String) {
        val owner = Any()
        synchronized(notificationMonitor) {
            pendingNotifications.add(accountId)
            if (notificationOwner != null) return
            notificationOwner = owner
        }
        try {
            while (true) {
                val next = synchronized(notificationMonitor) {
                    val id = pendingNotifications.firstOrNull()
                    if (id == null) {
                        notificationOwner = null
                        return
                    }
                    pendingNotifications.remove(id)
                    id
                }
                // A listener can resolve other expired tokens. Their notifications coalesce
                // into the next drain pass instead of recursively invoking this listener.
                // No session/account/notification lock is held while callbacks execute.
                listeners.forEach { listener -> runCatching { listener(next) } }
            }
        } finally {
            synchronized(notificationMonitor) {
                // Do not clear a new drainer's ownership after the normal empty-queue exit.
                if (notificationOwner === owner) notificationOwner = null
            }
        }
    }

    private fun tokenKey(token: String): String? {
        if (token.length != 43 || token.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
        val bytes = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        const val MAX_SESSIONS = 1024
        const val IDLE_SECONDS = 30L * 60
        const val ABSOLUTE_SECONDS = 8L * 60 * 60
    }
}
