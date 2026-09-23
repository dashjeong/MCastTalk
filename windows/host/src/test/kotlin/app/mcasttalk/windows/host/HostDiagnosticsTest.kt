package app.mcasttalk.windows.host

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class HostDiagnosticsTest {
    @Test
    fun reportUsesRealAggregatesAndNeverExposesRoomAccountOrFilesystemIdentifiers() = withAccountRoot { root ->
        val directory = RoomDirectory()
        val coordinator = RoomMembershipCoordinator(directory, RoomSocketHub())
        val report = HostDiagnostics(root, coordinator)
        assertEquals(mapOf("activeRooms" to 0, "joinedConnections" to 0), coordinator.statistics())
        val preferences = ParticipantPreferences.create("ko", null, "en", null, null)
        val alice = Participant("alice-connection", "Private Alice", preferences, username = "private-alice")
        val bob = Participant("bob-connection", "Private Bob", preferences)
        directory.join("private-room", alice)
        directory.join("second-private-room", bob)
        assertEquals(mapOf("activeRooms" to 2, "joinedConnections" to 2), coordinator.statistics())
        val snapshot = report.snapshot()
        val json = encodeJson(snapshot)
        for (secret in listOf("private-room", "Private Alice", "private-alice", "alice-connection", alice.presenceId, root.toString())) {
            assertFalse("Diagnostic leaked $secret", json.contains(secret))
        }
        assertTrue(json.contains("\"sttIntegrated\":false"))
        assertTrue(json.contains("\"gpuCalibration\":\"unverified\""))
        assertTrue((snapshot["uptimeSeconds"] as Long) >= 0)
        directory.leave("private-room", alice.id, alice.presenceId)
        assertEquals(mapOf("activeRooms" to 1, "joinedConnections" to 1), coordinator.statistics())
    }

    @Test
    fun unreadableWorkspaceIsUnknownRatherThanZeroAvailableDisk() = withAccountRoot { root ->
        val report = HostDiagnostics(root.resolve("does-not-exist"), RoomMembershipCoordinator(RoomDirectory(), RoomSocketHub()))
        val runtime = report.snapshot()["runtime"] as Map<*, *>
        assertNull(runtime["workspaceUsableBytes"])
        assertTrue((runtime["jvmHeapUsedBytes"] as Long) >= 0)
    }

    @Test
    fun diagnosticsRequireAdminAndTrustedHostAndOrigin() = withAccountRoot { root ->
        WorkspaceSetup.initialize(root)
        TestAccountFixture(root).use { fixture ->
            val userCookie = fixture.cookie()
            val guest = fixture.services.accounts.createAccount(fixture.admin, "diag-guest", "Guest",
                TestAccountFixture.PASSWORD.toCharArray(), AccountRole.GUEST,
                expiresAt = java.time.Instant.now().plusSeconds(300), guestRoomId = "guest-room")
            val guestCookie = "$SESSION_COOKIE_NAME=" + fixture.services.sessions.issue(guest)
            val adminCookie = fixture.adminCookie()
            testApplication {
                application { mcastTalkModule(root, accountServices = fixture.services) }
                assertEquals(401, client.get("/api/v1/admin/diagnostics") { headers.append(HttpHeaders.Host, "localhost") }.status.value)
                for (cookie in listOf(userCookie, guestCookie)) {
                    assertEquals(403, client.get("/api/v1/admin/diagnostics") {
                        headers.append(HttpHeaders.Host, "localhost"); headers.append(HttpHeaders.Cookie, cookie)
                    }.status.value)
                }
                assertEquals(403, client.get("/api/v1/admin/diagnostics") {
                    headers.append(HttpHeaders.Host, "localhost"); headers.append(HttpHeaders.Cookie, adminCookie)
                    headers.append(HttpHeaders.Origin, "https://attacker.invalid")
                }.status.value)
                val response = client.get("/api/v1/admin/diagnostics") {
                    headers.append(HttpHeaders.Host, "localhost"); headers.append(HttpHeaders.Cookie, adminCookie)
                }
                assertEquals(200, response.status.value)
                assertTrue(response.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
                assertTrue(response.bodyAsText().contains("\"activeRooms\":0"))
                assertFalse(response.bodyAsText().contains(fixture.admin.username))
            }
        }
    }

    @Test
    fun diagnosticReadsDoNotKeepAnIdleAdministratorSessionAlive() = withAccountRoot { root ->
        WorkspaceSetup.initialize(root)
        val clock = MutableAccountClock()
        val accounts = LocalAccounts(root, clock)
        AccountServices(accounts, LocalSessions(accounts, clock)).use { services ->
            val admin = accounts.createInitialAdmin("diag-admin", "Admin", TestAccountFixture.PASSWORD.toCharArray())
            val cookie = "$SESSION_COOKIE_NAME=" + services.sessions.issue(admin)
            testApplication {
                application { mcastTalkModule(root, accountServices = services) }
                clock.advance(1799)
                assertEquals(200, client.get("/api/v1/admin/diagnostics") {
                    headers.append(HttpHeaders.Host, "localhost"); headers.append(HttpHeaders.Cookie, cookie)
                }.status.value)
                clock.advance(1)
                assertEquals(401, client.get("/api/v1/admin/diagnostics") {
                    headers.append(HttpHeaders.Host, "localhost"); headers.append(HttpHeaders.Cookie, cookie)
                }.status.value)
            }
        }
    }
}
