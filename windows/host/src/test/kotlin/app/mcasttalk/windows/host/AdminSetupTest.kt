package app.mcasttalk.windows.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminSetupTest {
    @Test
    fun existingAdministratorNeverPromptsOrCreatesADefault() {
        AdminSetup.ensureAdministrator(
            initialized = { true },
            requestCredentials = { error("Existing setup must not prompt") },
            createAdministrator = { error("Existing setup must not be overwritten") },
            onInvalidInput = { error("No input was requested") },
        )
    }

    @Test
    fun chosenCredentialsInitializeOnceAndPasswordBufferIsCleared() {
        var initialized = false
        val chosen = InitialAdminCredentials("owner-choice", "설치 사용자", "A chosen passphrase 2026".toCharArray())
        var calls = 0
        AdminSetup.ensureAdministrator(
            initialized = { initialized },
            requestCredentials = { chosen },
            createAdministrator = {
                assertEquals("owner-choice", it.username)
                assertEquals("설치 사용자", it.displayName)
                assertTrue(it.password.contentEquals("A chosen passphrase 2026".toCharArray()))
                initialized = true
                calls++
            },
            onInvalidInput = { error("Valid credentials") },
        )
        assertEquals(1, calls)
        assertTrue(chosen.password.all { it == '\u0000' })
    }

    @Test
    fun firstRunAcceptsEightCharactersThroughTheRealAccountStore() = withAccountRoot { root ->
        LocalAccounts(root).use { accounts ->
            val chosen = InitialAdminCredentials("owner", "Owner", "Setup8! ".toCharArray())
            AdminSetup.ensureAdministrator(
                accounts::isInitialized,
                { chosen },
                { accounts.createInitialAdmin(it.username, it.displayName, it.password) },
                { error("Eight characters must be accepted") },
            )
            assertTrue(accounts.isInitialized())
            assertEquals("owner", accounts.authenticate("owner", "Setup8! ".toCharArray())?.username)
            assertTrue(chosen.password.all { it == '\u0000' })
        }
    }

    @Test
    fun cancellationDoesNotStartOrCreateAnAccount() {
        var created = false
        assertThrows(AdminSetupCancelled::class.java) {
            AdminSetup.ensureAdministrator({ false }, { null }, { created = true }, {})
        }
        assertFalse(created)
    }

    @Test
    fun validationFailureClearsSecretBeforeRetryAndStorageFailurePropagates() {
        val first = InitialAdminCredentials("bad", "bad", "short".toCharArray())
        val second = InitialAdminCredentials("owner", "Owner", "Long second passphrase".toCharArray())
        var prompts = 0
        var initialized = false
        var warnings = 0
        AdminSetup.ensureAdministrator(
            { initialized },
            {
                prompts++
                if (prompts == 1) first else {
                    assertTrue(first.password.all { it == '\u0000' })
                    second
                }
            },
            { if (it === first) throw IllegalArgumentException("Invalid input") else initialized = true },
            { warnings++ },
        )
        assertEquals(1, warnings)
        assertTrue(second.password.all { it == '\u0000' })
        val failed = InitialAdminCredentials("owner", "Owner", "Another passphrase 2026".toCharArray())
        assertThrows(java.io.IOException::class.java) {
            AdminSetup.ensureAdministrator({ false }, { failed }, { throw java.io.IOException("Disk failure") }, {})
        }
        assertTrue(failed.password.all { it == '\u0000' })
    }
}
