package app.mcasttalk.windows.host

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

enum class AccountRole { ADMIN, USER, GUEST }

data class LocalAccount(
    val id: String,
    val username: String,
    val displayName: String,
    val role: AccountRole,
    val enabled: Boolean,
    val expiresAt: Instant?,
    val guestRoomId: String?,
    val securityVersion: Int,
    val createdAt: Instant,
)

/** Offline account store. Own exactly one instance per workspace and close it on host shutdown. */
class LocalAccounts internal constructor(
    dataRoot: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val persistSnapshot: (Path, ByteArray) -> Unit = ::atomicAccountWrite,
) : AutoCloseable {
    private class Record(val account: LocalAccount, val password: PasswordHash)
    private val monitor = Any()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val directory = dataRoot.toAbsolutePath().normalize().resolve("config/security")
    private val marker = directory.resolve("initialized.v1")
    private val file = directory.resolve("accounts.v1.jsonl")
    private val lockChannel: FileChannel
    private val fileLock: FileLock
    private var records = linkedMapOf<String, Record>()
    private var closed = false
    private var damaged = false
    private val attempts = LoginAttempts(clock)

    init {
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(directory.parent)) { "Account directory must not be a symbolic link" }
        val lockPath = directory.resolve("accounts.lock")
        check(!Files.isSymbolicLink(lockPath)) { "Invalid account lock path" }
        lockChannel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS)
        try {
            fileLock = try { lockChannel.tryLock() }
            catch (_: java.nio.channels.OverlappingFileLockException) { null }
                ?: error("Workspace accounts are already open by another host")
            load()
        } catch (error: Throwable) {
            lockChannel.close()
            throw error
        }
    }

    fun isInitialized(): Boolean = synchronized(monitor) { ensureOpen(); records.isNotEmpty() }

    fun createInitialAdmin(username: String, displayName: String, password: CharArray): LocalAccount {
        val normalized = normalizeUsername(username)
        validateDisplayName(displayName)
        synchronized(monitor) { ensureOpen(); check(records.isEmpty() && !Files.exists(marker, NOFOLLOW_LINKS)) { "Initial administrator is already configured" } }
        val hash = PasswordHasher.hash(password)
        return synchronized(monitor) {
            ensureOpen()
            check(records.isEmpty() && !Files.exists(marker, NOFOLLOW_LINKS)) { "Initial administrator is already configured" }
            val admin = newAccount(normalized, displayName, AccountRole.ADMIN, true, null, null)
            val next = linkedMapOf(admin.id to Record(admin, hash))
            try {
                // The durable sentinel precedes the account file. A crash in between is locked,
                // not treated as a fresh install. Recovery requires an explicit operator procedure.
                FileChannel.open(marker, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use {
                    writeFully(it, MARKER.toByteArray(Charsets.UTF_8)); it.force(true)
                }
                persist(next)
                records = next
                admin
            } catch (error: Throwable) { damaged = true; throw error }
        }
    }

    /** All failed logins, including throttling and unavailable hash slots, have the same result. */
    fun authenticate(username: String, password: CharArray): LocalAccount? {
        val normalized = runCatching { normalizeUsername(username) }.getOrNull() ?: "<invalid>"
        if (!attempts.allow(normalized)) return null
        if (password.size !in PasswordHasher.MIN_LENGTH..PasswordHasher.MAX_LENGTH) return null
        val record = synchronized(monitor) { ensureOpen(); records.values.firstOrNull { it.account.username == normalized } }
        val matches = try { PasswordHasher.verify(password, record?.password) }
        catch (_: AccountOperationBusyException) { return null }
        if (!matches || record == null) return null
        return synchronized(monitor) {
            ensureOpen()
            // Password reset/disable during the expensive hash must not authenticate old state.
            records[record.account.id]?.takeIf { it === record && usable(it.account) }?.account
        }
    }

    fun listAccounts(actor: LocalAccount): List<LocalAccount> = synchronized(monitor) {
        ensureOpen(); requireAdmin(actor); records.values.map { it.account }.sortedBy { it.username }
    }

    fun createAccount(
        actor: LocalAccount, username: String, displayName: String, password: CharArray,
        role: AccountRole, enabled: Boolean = true, expiresAt: Instant? = null, guestRoomId: String? = null,
    ): LocalAccount {
        val normalized = normalizeUsername(username)
        validateDisplayName(displayName); validateGuest(role, expiresAt, guestRoomId, requireFuture = true)
        synchronized(monitor) { ensureOpen(); requireAdmin(actor); require(records.size < MAX_ACCOUNTS) { "Account limit reached" } }
        val hash = PasswordHasher.hash(password)
        val account = newAccount(normalized, displayName, role, enabled, expiresAt, guestRoomId)
        return mutate(actor, account.id) { next ->
            require(next.size < MAX_ACCOUNTS) { "Account limit reached" }
            require(next.values.none { it.account.username == normalized }) { "Username is unavailable" }
            next[account.id] = Record(account, hash)
            account
        }
    }

    fun updateAccount(
        actor: LocalAccount, accountId: String, displayName: String, role: AccountRole,
        enabled: Boolean, expiresAt: Instant?, guestRoomId: String?,
    ): LocalAccount {
        validateDisplayName(displayName); validateGuest(role, expiresAt, guestRoomId, requireFuture = enabled)
        return mutate(actor, accountId) { next ->
            val previous = next[accountId] ?: throw IllegalArgumentException("Account not found")
            require(actor.id != accountId || (role == AccountRole.ADMIN && enabled)) {
                "An administrator cannot demote or disable their own account"
            }
            val updated = previous.account.copy(displayName = displayName, role = role, enabled = enabled,
                expiresAt = expiresAt, guestRoomId = guestRoomId, securityVersion = Math.addExact(previous.account.securityVersion, 1))
            next[accountId] = Record(updated, previous.password)
            updated
        }
    }

    fun resetPassword(actor: LocalAccount, accountId: String, password: CharArray): LocalAccount {
        synchronized(monitor) { ensureOpen(); requireAdmin(actor); require(records.containsKey(accountId)) { "Account not found" } }
        val hash = PasswordHasher.hash(password)
        return mutate(actor, accountId) { next ->
            val previous = next[accountId] ?: throw IllegalArgumentException("Account not found")
            val updated = previous.account.copy(securityVersion = Math.addExact(previous.account.securityVersion, 1))
            next[accountId] = Record(updated, hash)
            updated
        }
    }

    fun changePassword(actor: LocalAccount, currentPassword: CharArray, newPassword: CharArray): LocalAccount {
        val authenticated = authenticate(actor.username, currentPassword)
        if (authenticated == null || authenticated.id != actor.id || authenticated.securityVersion != actor.securityVersion) {
            throw SecurityException("Current credentials are required")
        }
        val hash = PasswordHasher.hash(newPassword)
        val updated = synchronized(monitor) {
            ensureOpen(); requireCurrent(actor)
            val previous = records[actor.id]!!
            val account = previous.account.copy(securityVersion = Math.addExact(previous.account.securityVersion, 1))
            val next = LinkedHashMap(records)
            next[actor.id] = Record(account, hash)
            persist(next); records = next
            account
        }
        publishChange(updated.id)
        return updated
    }

    fun deleteAccount(actor: LocalAccount, accountId: String) {
        mutate(actor, accountId) { next ->
            require(actor.id != accountId) { "An administrator cannot delete their own account" }
            require(next.remove(accountId) != null) { "Account not found" }
        }
    }

    internal fun activeAccount(accountId: String): LocalAccount? = synchronized(monitor) {
        ensureOpen(); records[accountId]?.account?.takeIf(::usable)
    }

    internal fun onChanged(listener: (String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        val ids = synchronized(monitor) {
            if (closed) return
            closed = true
            val result = records.keys.toList()
            fileLock.release(); lockChannel.close()
            result
        }
        ids.forEach(::publishChange)
        listeners.clear()
    }

    private fun <T> mutate(actor: LocalAccount, accountId: String, change: (LinkedHashMap<String, Record>) -> T): T {
        val result = synchronized(monitor) {
            ensureOpen(); requireAdmin(actor)
            val next = LinkedHashMap(records)
            val value = change(next)
            require(next.values.any { it.account.role == AccountRole.ADMIN && it.account.enabled }) { "The last active administrator must be retained" }
            persist(next) // On failure, neither current memory nor session versions change.
            records = next
            value
        }
        publishChange(accountId) // Never invoke session/WS callbacks while holding the account lock.
        return result
    }

    private fun requireAdmin(actor: LocalAccount) {
        requireCurrent(actor)
        if (records[actor.id]!!.account.role != AccountRole.ADMIN) throw SecurityException("Administrator authorization required")
    }

    private fun requireCurrent(actor: LocalAccount) {
        val current = records[actor.id]?.account
        if (current == null || current.securityVersion != actor.securityVersion || !usable(current)) {
            throw SecurityException("Current account authorization required")
        }
    }

    private fun usable(account: LocalAccount): Boolean = account.enabled &&
        (account.expiresAt == null || clock.instant().isBefore(account.expiresAt))

    private fun ensureOpen() { check(!closed && !damaged) { "Local accounts are unavailable" } }

    private fun publishChange(accountId: String) { listeners.forEach { listener -> runCatching { listener(accountId) } } }

    private fun newAccount(username: String, displayName: String, role: AccountRole, enabled: Boolean, expiresAt: Instant?, guestRoomId: String?) =
        LocalAccount(UUID.randomUUID().toString(), username, displayName, role, enabled, expiresAt, guestRoomId, 1, clock.instant())

    private fun validateGuest(role: AccountRole, expiresAt: Instant?, guestRoomId: String?, requireFuture: Boolean) {
        if (role == AccountRole.GUEST) {
            require(expiresAt != null && guestRoomId != null) { "Guests require an expiry and room scope" }
            requireValidIdentifier(guestRoomId, "guestRoomId")
            if (requireFuture) require(clock.instant().isBefore(expiresAt)) { "Guest expiry must be in the future" }
        } else require(expiresAt == null && guestRoomId == null) { "Only guests may have an expiry or room scope" }
    }

    private fun persist(next: Map<String, Record>) {
        val text = encodeJson(linkedMapOf("schemaVersion" to 1, "count" to next.size)) + "\n" +
            next.values.sortedBy { it.account.id }.joinToString("\n", postfix = "\n", transform = ::encodeRecord)
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_FILE_BYTES) { "Account file exceeds its limit" }
        persistSnapshot(file, bytes)
    }

    private fun load() {
        val hasMarker = Files.exists(marker, NOFOLLOW_LINKS)
        val hasFile = Files.exists(file, NOFOLLOW_LINKS)
        if (!hasMarker && !hasFile) {
            check(Files.list(directory).use { paths -> paths.allMatch { it.fileName.toString() == "accounts.lock" } }) { "Incomplete account setup requires operator recovery" }
            return
        }
        check(hasMarker && hasFile && Files.isRegularFile(marker, NOFOLLOW_LINKS) && Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Incomplete account setup requires operator recovery" }
        check(Files.size(marker) == MARKER.toByteArray().size.toLong() && Files.readString(marker) == MARKER) { "Invalid account initialization marker" }
        check(Files.size(file) in 1..MAX_FILE_BYTES.toLong()) { "Invalid account file size" }
        val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(MAX_FILE_BYTES + 1) }
        check(bytes.size <= MAX_FILE_BYTES) { "Account file exceeds its limit" }
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        check(text.endsWith('\n')) { "Truncated account file" }
        val lines = text.dropLast(1).split('\n')
        check(lines.size in 2..MAX_ACCOUNTS + 1) { "Invalid account count" }
        val header = parseFlatJsonObject(lines.first(), 4096)
        check(header.requiredInt("schemaVersion") == 1 && header.requiredInt("count") == lines.size - 1) { "Invalid account schema" }
        val loaded = linkedMapOf<String, Record>()
        val usernames = hashSetOf<String>()
        lines.drop(1).forEach { line ->
            val value = parseFlatJsonObject(line, 4096)
            val role = AccountRole.valueOf(value.requiredString("role"))
            val account = LocalAccount(value.requiredString("id"), value.requiredString("username"), value.requiredString("displayName"),
                role, value.requiredBoolean("enabled"), value.optionalString("expiresAt")?.let(Instant::parse), value.optionalString("guestRoomId"),
                value.requiredInt("securityVersion"), Instant.parse(value.requiredString("createdAt")))
            check(UUID.fromString(account.id).toString() == account.id && account.securityVersion > 0) { "Invalid account identity" }
            check(normalizeUsername(account.username) == account.username && usernames.add(account.username)) { "Invalid or duplicate username" }
            validateDisplayName(account.displayName); validateGuest(role, account.expiresAt, account.guestRoomId, false)
            check(value.requiredString("algorithm") == PasswordHasher.ALGORITHM && value.requiredInt("iterations") == PasswordHasher.ITERATIONS) { "Unsupported password hash parameters" }
            val hash = PasswordHash(Base64.getDecoder().decode(value.requiredString("salt")), Base64.getDecoder().decode(value.requiredString("passwordHash")))
            check(hash.salt.size == 16 && hash.derived.size == 32) { "Invalid password hash" }
            val record = Record(account, hash)
            check(encodeRecord(record) == line && loaded.put(account.id, record) == null) { "Invalid or duplicate account record" }
        }
        check(loaded.values.any { it.account.role == AccountRole.ADMIN && it.account.enabled }) { "No active administrator in account store" }
        records = loaded
    }

    private fun encodeRecord(record: Record): String {
        val account = record.account
        return encodeJson(linkedMapOf("id" to account.id, "username" to account.username, "displayName" to account.displayName,
            "role" to account.role.name, "enabled" to account.enabled, "expiresAt" to account.expiresAt?.toString(),
            "guestRoomId" to account.guestRoomId, "securityVersion" to account.securityVersion, "createdAt" to account.createdAt.toString(),
            "algorithm" to PasswordHasher.ALGORITHM, "iterations" to PasswordHasher.ITERATIONS,
            "salt" to Base64.getEncoder().encodeToString(record.password.salt), "passwordHash" to Base64.getEncoder().encodeToString(record.password.derived)))
    }

    companion object {
        const val MAX_ACCOUNTS = 256
        private const val MAX_FILE_BYTES = 1024 * 1024
        private const val MARKER = "MCastTalk local accounts v1\n"
        fun normalizeUsername(username: String): String {
            require(Regex("^[A-Za-z0-9][A-Za-z0-9_.-]{2,31}$").matches(username)) { "Username must be 3-32 ASCII letters, digits, dot, dash or underscore" }
            return username.lowercase(Locale.ROOT)
        }
        private fun validateDisplayName(displayName: String) {
            require(displayName.isNotBlank() && displayName.length <= 64 && displayName.none(Char::isISOControl)) { "Display name must contain 1-64 printable characters" }
        }
    }
}

private fun writeFully(channel: FileChannel, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) channel.write(buffer)
}

internal fun atomicAccountWrite(destination: Path, bytes: ByteArray) {
    val temporary = destination.resolveSibling(".accounts-${UUID.randomUUID()}.tmp")
    try {
        FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { writeFully(it, bytes); it.force(true) }
        // No non-atomic fallback: unsupported filesystems fail closed with the old file intact.
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}

private class LoginAttempts(private val clock: Clock) {
    private data class Window(val started: Instant, var count: Int)
    private val names = linkedMapOf<String, Window>()
    private var global = Window(clock.instant(), 0)
    @Synchronized fun allow(username: String): Boolean {
        val now = clock.instant()
        names.entries.removeIf { !now.isBefore(it.value.started.plusSeconds(300)) || now.isBefore(it.value.started) }
        if (!now.isBefore(global.started.plusSeconds(60)) || now.isBefore(global.started)) global = Window(now, 0)
        if (global.count >= 60) return false
        if (!names.containsKey(username) && names.size >= 512) return false
        val local = names.getOrPut(username) { Window(now, 0) }
        if (local.count >= 8) return false
        local.count++; global.count++
        return true
    }
}
