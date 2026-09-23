package app.mcasttalk.windows.host

import org.junit.Assert.*
import org.junit.Test

class LocalSessionsTest {
    @Test
    fun tokensAreOpaqueDistinctAndLogoutDoesNotRevokeOtherSessions() = withAccountRoot { root ->
        LocalAccounts(root).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            LocalSessions(accounts).use { sessions ->
                val first = sessions.issue(admin)!!
                val second = sessions.issue(admin)!!
                assertEquals(43, first.length)
                assertTrue(first != second)
                assertEquals(admin, sessions.resolve(first))
                assertNull(sessions.resolve("invalid token"))
                var notifications = 0
                sessions.onRevoked { id -> if (id == admin.id) notifications++ }.use {
                    sessions.revoke(first)
                    assertNull(sessions.resolve(first))
                    assertEquals(admin, sessions.resolve(second))
                    assertEquals(1, notifications)
                }
            }
        }
    }

    @Test
    fun idleExpiryIsNotExtendedByOutboundChecksAndAbsoluteExpiryCannotBeRenewed() = withAccountRoot { root ->
        val clock = MutableAccountClock()
        LocalAccounts(root, clock).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            LocalSessions(accounts, clock).use { sessions ->
                val idle = sessions.issue(admin)!!
                clock.advance(1799)
                assertNotNull(sessions.resolve(idle, touch = false))
                clock.advance(1)
                assertNull(sessions.resolve(idle))
                val absolute = sessions.issue(admin)!!
                repeat(31) { clock.advance(900); assertNotNull(sessions.resolve(absolute)) }
                clock.advance(900)
                assertNull(sessions.resolve(absolute))
            }
        }
    }

    @Test
    fun guestExpiryTakesPrecedenceAndAccountMutationsRevokeImmediately() = withAccountRoot { root ->
        val clock = MutableAccountClock()
        LocalAccounts(root, clock).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            val guest = accounts.createAccount(admin, "guest", "Guest", testPassword(), AccountRole.GUEST,
                expiresAt = clock.instant().plusSeconds(60), guestRoomId = "meeting")
            val user = accounts.createAccount(admin, "normal", "User", testPassword(), AccountRole.USER)
            LocalSessions(accounts, clock).use { sessions ->
                val guestToken = sessions.issue(guest)!!
                val userToken = sessions.issue(user)!!
                val changed = mutableListOf<String>()
                sessions.onRevoked { changed.add(it) }.use {
                    clock.advance(60)
                    assertNull(sessions.resolve(guestToken))
                    assertTrue(changed.contains(guest.id))
                    val disabled = accounts.updateAccount(admin, user.id, "User", AccountRole.USER, false, null, null)
                    assertTrue(changed.contains(user.id))
                    assertNull(sessions.resolve(userToken))
                    assertNull(sessions.issue(disabled))
                    val enabled = accounts.updateAccount(admin, user.id, "User", AccountRole.USER, true, null, null)
                    val token = sessions.issue(enabled)!!
                    accounts.resetPassword(admin, user.id, "a different fixture password".toCharArray())
                    assertNull(sessions.resolve(token))
                    assertNull(sessions.issue(enabled)) // Authentication snapshot before reset cannot issue a new session.
                    val authenticated = accounts.authenticate("normal", "a different fixture password".toCharArray())!!
                    val roleToken = sessions.issue(authenticated)!!
                    accounts.updateAccount(admin, user.id, "User", AccountRole.ADMIN, true, null, null)
                    assertNull(sessions.resolve(roleToken))
                }
            }
        }
    }

    @Test
    fun selfPasswordChangeRequiresCurrentPasswordAndRevokesExistingTokens() = withAccountRoot { root ->
        LocalAccounts(root).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            LocalSessions(accounts).use { sessions ->
                val token = sessions.issue(admin)!!
                assertThrows(SecurityException::class.java) { accounts.changePassword(admin, "wrong fixture password".toCharArray(), "new fixture password".toCharArray()) }
                assertNotNull(sessions.resolve(token))
                val updated = accounts.changePassword(admin, testPassword(), "new fixture password".toCharArray())
                assertNull(sessions.resolve(token))
                assertNull(accounts.authenticate("admin", testPassword()))
                assertEquals(updated, accounts.authenticate("admin", "new fixture password".toCharArray()))
            }
        }
    }

    @Test
    fun sessionsAreCappedAndRestartNeverRestoresAnOldToken() = withAccountRoot { root ->
        LocalAccounts(root).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            var oldToken = ""
            LocalSessions(accounts).use { sessions ->
                repeat(LocalSessions.MAX_SESSIONS) { index ->
                    val token = sessions.issue(admin)
                    assertNotNull(token)
                    if (index == 0) oldToken = token!!
                }
                assertNull(sessions.issue(admin))
                sessions.revoke(oldToken)
                assertNotNull(sessions.issue(admin))
            }
            LocalSessions(accounts).use { assertNull(it.resolve(oldToken)) }
        }
    }

    @Test
    fun resolvingManyExpiredSessionsInsideARevocationListenerDoesNotRecurse() = withAccountRoot { root ->
        val clock = MutableAccountClock()
        LocalAccounts(root, clock).use { accounts ->
            val admin = accounts.createInitialAdmin("admin", "Admin", testPassword())
            LocalSessions(accounts, clock).use { sessions ->
                val tokens = List(LocalSessions.MAX_SESSIONS) { sessions.issue(admin)!! }
                var callbackDepth = 0
                var maximumDepth = 0
                var callbackCount = 0
                sessions.onRevoked {
                    callbackDepth++
                    maximumDepth = maxOf(maximumDepth, callbackDepth)
                    callbackCount++
                    try { tokens.forEach { token -> sessions.resolve(token, touch = false) } }
                    finally { callbackDepth-- }
                }.use {
                    clock.advance(LocalSessions.IDLE_SECONDS)
                    assertNull(sessions.resolve(tokens.first(), touch = false))
                    assertTrue(tokens.all { sessions.resolve(it, touch = false) == null })
                    assertEquals(1, maximumDepth)
                    assertEquals(2, callbackCount) // Initial event + one coalesced follow-up pass.
                    assertNotNull(sessions.issue(admin))
                }
            }
        }
    }
}
