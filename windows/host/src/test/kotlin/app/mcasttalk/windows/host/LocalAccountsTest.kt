package app.mcasttalk.windows.host

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LocalAccountsTest {
    @Test
    fun firstAdminIsOneTimeAndProductionPasswordHashSurvivesReopenWithoutPlaintext() = withAccountRoot { root ->
        val password = testPassword()
        var id = ""
        try {
            LocalAccounts(root).use { accounts ->
                assertFalse(accounts.isInitialized())
                val admin = accounts.createInitialAdmin("Admin_Name", "관리자", password)
                id = admin.id
                assertEquals("admin_name", admin.username)
                assertEquals(AccountRole.ADMIN, admin.role)
                assertEquals(admin, accounts.authenticate("ADMIN_NAME", password))
                assertNull(accounts.authenticate("admin_name", "definitely incorrect password".toCharArray()))
                assertThrows(IllegalStateException::class.java) { accounts.createInitialAdmin("other", "Other", password) }
                val stored = Files.readString(root.resolve("config/security/accounts.v1.jsonl"))
                assertTrue(stored.contains("\"iterations\":600000"))
                assertTrue(stored.contains("PBKDF2WithHmacSHA256"))
                assertFalse(stored.contains(String(password)))
                assertTrue(UUID.fromString(admin.id).toString() == admin.id)
            }
            LocalAccounts(root).use { accounts ->
                assertTrue(accounts.isInitialized())
                assertEquals(id, accounts.authenticate("admin_name", password)?.id)
            }
        } finally { password.fill('\u0000') }
    }

    @Test
    fun passwordWhitespaceIsSignificantAndValidationIsBounded() {
        val original = "  two secret words  ".toCharArray()
        val hash = PasswordHasher.hash(original)
        assertTrue(PasswordHasher.verify(original, hash))
        assertFalse(PasswordHasher.verify("two secret words".toCharArray(), hash))
        assertThrows(IllegalArgumentException::class.java) { PasswordHasher.hash(CharArray(7) { 'x' }) }
        assertThrows(IllegalArgumentException::class.java) { PasswordHasher.hash(CharArray(129) { 'x' }) }
        for (size in listOf(8, 14, 15, 128)) {
            val boundary = CharArray(size) { 'x' }
            assertTrue(PasswordHasher.verify(boundary, PasswordHasher.hash(boundary)))
        }
        val second = PasswordHasher.hash(original)
        assertFalse(hash.salt.contentEquals(second.salt))
        assertEquals(16, hash.salt.size)
        assertEquals(32, hash.derived.size)
        original.fill('\u0000')
        for (bad in listOf("aa", "x".repeat(33), " admin", "admin ", "한국어", "-admin")) {
            assertThrows(IllegalArgumentException::class.java) { LocalAccounts.normalizeUsername(bad) }
        }
    }

    @Test
    fun eightCharacterPasswordsWorkForInitialAdminUsersGuestsResetChangeAndReopen() = withAccountRoot { root ->
        val original = " A8test ".toCharArray()
        val reset = "B8test! ".toCharArray()
        val changed = "C8test! ".toCharArray()
        try {
            LocalAccounts(root).use { accounts ->
                assertThrows(IllegalArgumentException::class.java) {
                    accounts.createInitialAdmin("admin", "Admin", CharArray(7) { 'x' })
                }
                assertFalse(accounts.isInitialized())
                val admin = accounts.createInitialAdmin("admin", "Admin", original)
                assertEquals(admin, accounts.authenticate("admin", original))
                assertNull(accounts.authenticate("admin", String(original).trim().toCharArray()))
                val user = accounts.createAccount(admin, "user", "User", original, AccountRole.USER)
                val guest = accounts.createAccount(admin, "guest", "Guest", original, AccountRole.GUEST,
                    expiresAt = Instant.now().plusSeconds(3600), guestRoomId = "meeting")
                assertEquals(guest, accounts.authenticate("guest", original))
                assertThrows(IllegalArgumentException::class.java) { accounts.resetPassword(admin, user.id, CharArray(7) { 'x' }) }
                val resetUser = accounts.resetPassword(admin, user.id, reset)
                assertNull(accounts.authenticate("user", original))
                assertEquals(resetUser, accounts.authenticate("user", reset))
                assertThrows(IllegalArgumentException::class.java) { accounts.changePassword(resetUser, reset, CharArray(7) { 'x' }) }
                val changedUser = accounts.changePassword(resetUser, reset, changed)
                assertEquals(changedUser, accounts.authenticate("user", changed))
                assertNull(accounts.authenticate("user", reset))
            }
            LocalAccounts(root).use { accounts ->
                assertNotNull(accounts.authenticate("admin", original))
                assertNotNull(accounts.authenticate("user", changed))
            }
        } finally {
            original.fill('\u0000'); reset.fill('\u0000'); changed.fill('\u0000')
        }
    }

    @Test
    fun onlyCurrentAdministratorsCanManageAccountsAndLastAdminIsPreserved() = withAccountRoot { root ->
        LocalAccounts(root).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            val user = accounts.createAccount(admin, "normal", "User", testPassword(), AccountRole.USER)
            assertThrows(SecurityException::class.java) { accounts.listAccounts(user) }
            assertThrows(SecurityException::class.java) { accounts.createAccount(user, "other", "Other", testPassword(), AccountRole.USER) }
            assertThrows(SecurityException::class.java) { accounts.resetPassword(user, admin.id, testPassword()) }
            assertThrows(IllegalArgumentException::class.java) { accounts.deleteAccount(admin, admin.id) }
            assertThrows(IllegalArgumentException::class.java) { accounts.updateAccount(admin, admin.id, "Admin", AccountRole.USER, true, null, null) }
            assertThrows(IllegalArgumentException::class.java) { accounts.updateAccount(admin, admin.id, "Admin", AccountRole.ADMIN, false, null, null) }
            assertEquals(AccountRole.ADMIN, accounts.listAccounts(admin).first { it.id == admin.id }.role)
            val secondAdmin = accounts.createAccount(admin, "admin_two", "Admin 2", testPassword(), AccountRole.ADMIN)
            assertThrows(IllegalArgumentException::class.java) { accounts.updateAccount(admin, admin.id, "Admin", AccountRole.USER, true, null, null) }
            assertThrows(IllegalArgumentException::class.java) { accounts.updateAccount(admin, admin.id, "Admin", AccountRole.ADMIN, false, null, null) }
            assertThrows(IllegalArgumentException::class.java) { accounts.deleteAccount(admin, admin.id) }
            accounts.updateAccount(secondAdmin, admin.id, "Former Admin", AccountRole.USER, true, null, null)
            assertThrows(SecurityException::class.java) { accounts.listAccounts(admin) }
            assertEquals(3, accounts.listAccounts(secondAdmin).size)
        }
    }

    @Test
    fun guestsRequireFutureExpiryAndRoomScopeAndExpiredGuestsCannotAuthenticate() = withAccountRoot { root ->
        val clock = MutableAccountClock()
        LocalAccounts(root, clock).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            assertThrows(IllegalArgumentException::class.java) { accounts.createAccount(admin, "guest", "Guest", testPassword(), AccountRole.GUEST) }
            assertThrows(IllegalArgumentException::class.java) { accounts.createAccount(admin, "guest", "Guest", testPassword(), AccountRole.GUEST, expiresAt = clock.instant(), guestRoomId = "meeting") }
            assertThrows(IllegalArgumentException::class.java) { accounts.createAccount(admin, "normal", "User", testPassword(), AccountRole.USER, expiresAt = clock.instant().plusSeconds(60), guestRoomId = "meeting") }
            val guest = accounts.createAccount(admin, "guest", "Guest", testPassword(), AccountRole.GUEST, expiresAt = clock.instant().plusSeconds(60), guestRoomId = "meeting")
            assertEquals(guest, accounts.authenticate("guest", testPassword()))
            clock.advance(60)
            assertNull(accounts.authenticate("guest", testPassword()))
        }
    }

    @Test
    fun failedAtomicSavePreservesCurrentMemoryDiskAndCredentialVersion() = withAccountRoot { root ->
        var fail = false
        LocalAccounts(root, persistSnapshot = { path, bytes ->
            if (fail) throw IOException("Injected fixture write failure")
            atomicAccountWrite(path, bytes)
        }).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            val user = accounts.createAccount(admin, "normal", "User", testPassword(), AccountRole.USER)
            val before = Files.readAllBytes(root.resolve("config/security/accounts.v1.jsonl"))
            var changed = false
            accounts.onChanged { changed = true }.use {
                fail = true
                assertThrows(IOException::class.java) { accounts.updateAccount(admin, user.id, "Changed", AccountRole.USER, false, null, null) }
                assertEquals(user, accounts.listAccounts(admin).first { it.id == user.id })
                assertTrue(before.contentEquals(Files.readAllBytes(root.resolve("config/security/accounts.v1.jsonl"))))
                assertFalse(changed)
                assertEquals(user, accounts.authenticate("normal", testPassword()))
            }
        }
    }

    @Test
    fun interruptedInitialSetupAndDamagedStoresNeverReinitialize() = withAccountRoot { root ->
        LocalAccounts(root, persistSnapshot = { _, _ -> throw IOException("Injected initial write failure") }).use { accounts ->
            assertThrows(IOException::class.java) { accounts.createInitialAdmin("admin", "Admin", testPassword()) }
            assertThrows(IllegalStateException::class.java) { accounts.isInitialized() }
        }
        assertTrue(Files.exists(root.resolve("config/security/initialized.v1")))
        assertThrows(IllegalStateException::class.java) { LocalAccounts(root).close() }
    }

    @Test
    fun truncatedDuplicateWeakHashAndAdministratorlessStoresFailClosed() = withAccountRoot { root ->
        LocalAccounts(root).use { it.createInitialAdmin("admin", "Admin", testPassword()) }
        val path = root.resolve("config/security/accounts.v1.jsonl")
        val original = Files.readString(path)
        for (damaged in listOf(
            original.dropLast(1), original.replace("600000", "1"), original.replace("\"role\":\"ADMIN\"", "\"role\":\"USER\""),
            original.replace("\"enabled\":true", "\"enabled\":false"),
            original.replace("\"count\":1", "\"count\":2") + original.lineSequence().drop(1).first() + "\n",
        )) {
            Files.writeString(path, damaged)
            assertThrows(Exception::class.java) { LocalAccounts(root).close() }
        }
        Files.writeString(path, original)
        LocalAccounts(root).use { assertTrue(it.isInitialized()) }
    }

    @Test
    fun workspaceLockPreventsTwoWritersAndIsReleasedOnClose() = withAccountRoot { root ->
        LocalAccounts(root).use {
            assertThrows(IllegalStateException::class.java) { LocalAccounts(root).close() }
        }
        LocalAccounts(root).use { assertFalse(it.isInitialized()) }
    }

    @Test
    fun boundedLoginAttemptsReturnTheSameNullForUnknownWrongAndThrottledCredentials() = withAccountRoot { root ->
        val clock = MutableAccountClock()
        LocalAccounts(root, clock).use { accounts ->
            accounts.createInitialAdmin("admin", "Admin", testPassword())
            assertNull(accounts.authenticate("missing", testPassword()))
            repeat(8) { assertNull(accounts.authenticate("admin", "an incorrect test password".toCharArray())) }
            assertNull(accounts.authenticate("admin", testPassword()))
            clock.advance(301)
            assertNotNull(accounts.authenticate("admin", testPassword()))
        }
    }

    @Test
    fun accountFileAndMutationAreBoundedAt256Accounts() = withAccountRoot { root ->
        LocalAccounts(root).use { it.createInitialAdmin("admin", "Admin", testPassword()) }
        val path = root.resolve("config/security/accounts.v1.jsonl")
        val original = Files.readString(path).lineSequence().drop(1).first()
        val oldId = parseFlatJsonObject(original).requiredString("id")
        // Fixture clones a valid hash, avoiding 255 unnecessary expensive password creations.
        val fixtures = (1 until LocalAccounts.MAX_ACCOUNTS).map { index ->
            original.replace(oldId, UUID.randomUUID().toString()).replace("\"username\":\"admin\"", "\"username\":\"fixture$index\"")
                .replace("\"role\":\"ADMIN\"", "\"role\":\"USER\"")
        }
        Files.writeString(path, "{\"schemaVersion\":1,\"count\":256}\n" + (listOf(original) + fixtures).joinToString("\n", postfix = "\n"))
        LocalAccounts(root).use { accounts ->
            val admin = accounts.authenticate("admin", testPassword())!!
            assertEquals(256, accounts.listAccounts(admin).size)
            assertThrows(IllegalArgumentException::class.java) { accounts.createAccount(admin, "overflow", "Overflow", testPassword(), AccountRole.USER) }
        }
    }
}

internal class MutableAccountClock(private var current: Instant = Instant.parse("2026-09-19T00:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advance(seconds: Long) { current = current.plusSeconds(seconds) }
}

internal fun testPassword(): CharArray = "offline fixture password only".toCharArray()
internal fun withAccountRoot(block: (Path) -> Unit) {
    val root = Files.createTempDirectory("mcasttalk-accounts-")
    try { block(root) } finally { root.toFile().deleteRecursively() }
}
