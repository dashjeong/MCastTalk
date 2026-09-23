package app.mcasttalk.windows.host

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRoutesTest {
    @Test
    fun accountEndpointsFailClosedBeforeNativeAdministratorSetup() {
        val root = Files.createTempDirectory("mcasttalk-setup-api-")
        WorkspaceSetup.initialize(root)
        try {
            testApplication {
                application { mcastTalkModule(root) }
                assertEquals(503, client.get("/api/v1/auth/session").status.value)
                assertEquals(503, client.postJson("/api/v1/auth/login", mapOf("username" to "admin", "password" to TestAccountFixture.PASSWORD)).status.value)
                assertEquals(503, client.get("/api/v1/admin/accounts").status.value)
                assertEquals(404, client.postJson("/api/v1/auth/bootstrap", emptyMap<String, String>()).status.value)
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun loginUsesGenericFailuresOpaqueHttpOnlyCookieAndLogoutRevokesIt() = withFixture { root, fixture ->
        val alice = fixture.account("Alice")
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val wrong = client.postJson("/api/v1/auth/login", mapOf("username" to alice.username, "password" to "Wrong test password 9!"))
            val unknown = client.postJson("/api/v1/auth/login", mapOf("username" to "unknown-user", "password" to "Wrong test password 9!"))
            assertEquals(401, wrong.status.value)
            assertEquals(wrong.bodyAsText(), unknown.bodyAsText())
            val login = client.postJson("/api/v1/auth/login", mapOf("username" to alice.username, "password" to TestAccountFixture.PASSWORD))
            assertEquals(200, login.status.value)
            val setCookie = checkNotNull(login.headers[HttpHeaders.SetCookie])
            assertTrue(setCookie.contains("HttpOnly", ignoreCase = true))
            assertTrue(setCookie.contains("SameSite=Strict", ignoreCase = true))
            assertTrue(setCookie.contains("Path=/", ignoreCase = true))
            val cookie = setCookie.substringBefore(';')
            assertEquals(43, cookie.substringAfter('=').length)
            val response = login.bodyAsText()
            assertTrue(response.contains("\"username\":\"${alice.username}\""))
            assertFalse(response.contains(TestAccountFixture.PASSWORD))
            assertFalse(response.contains("securityVersion"))
            val current = client.get("/api/v1/auth/session") {
                headers.append(HttpHeaders.Host, "localhost")
                headers.append(HttpHeaders.Cookie, cookie)
            }
            assertTrue(current.bodyAsText().contains("\"authenticated\":true"))
            assertEquals(200, client.postJson("/api/v1/auth/logout", emptyMap<String, String>(), cookie).status.value)
            val revoked = client.get("/api/v1/auth/session") {
                headers.append(HttpHeaders.Host, "localhost")
                headers.append(HttpHeaders.Cookie, cookie)
            }
            assertTrue(revoked.bodyAsText().contains("\"authenticated\":false"))
        }
    }

    @Test
    fun adminRoutesRejectRegularUsersAndEnforceOriginContentTypeAndByteLimit() = withFixture { root, fixture ->
        val userCookie = fixture.cookie()
        val adminCookie = fixture.adminCookie()
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            assertEquals(401, client.get("/api/v1/admin/accounts") { headers.append(HttpHeaders.Host, "localhost") }.status.value)
            assertEquals(403, client.get("/api/v1/admin/accounts") {
                headers.append(HttpHeaders.Host, "localhost")
                headers.append(HttpHeaders.Cookie, userCookie)
            }.status.value)
            assertEquals(403, client.postJson("/api/v1/admin/accounts", emptyMap<String, String>(), userCookie).status.value)
            assertEquals(403, client.postJson("/api/v1/auth/logout", emptyMap<String, String>(), adminCookie, "https://attacker.invalid").status.value)
            assertEquals(403, client.postJson("/api/v1/auth/logout", emptyMap<String, String>(), adminCookie, null).status.value)
            val form = client.post("/api/v1/auth/logout") {
                headers.append(HttpHeaders.Host, "localhost")
                headers.append(HttpHeaders.Origin, "http://localhost")
                headers.append(HttpHeaders.Cookie, adminCookie)
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("value=1")
            }
            assertEquals(415, form.status.value)
            val oversized = client.postJson("/api/v1/auth/login", mapOf("username" to "test-admin", "password" to "가".repeat(6_000)))
            assertEquals(413, oversized.status.value)
            assertEquals(200, client.get("/api/v1/admin/accounts") {
                headers.append(HttpHeaders.Host, "localhost")
                headers.append(HttpHeaders.Cookie, adminCookie)
            }.status.value)
        }
    }

    @Test
    fun adminCanCreateRestrictedGuestDisableAndResetExistingAccounts() = withFixture { root, fixture ->
        val alice = fixture.account("Alice")
        val oldToken = checkNotNull(fixture.services.sessions.issue(alice))
        val cookie = fixture.adminCookie()
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val invalidGuest = client.postJson("/api/v1/admin/accounts", mapOf(
                "username" to "guest-one", "displayName" to "Guest", "password" to TestAccountFixture.PASSWORD, "role" to "GUEST",
            ), cookie)
            assertEquals(400, invalidGuest.status.value)
            val validGuest = client.postJson("/api/v1/admin/accounts", mapOf(
                "username" to "guest-one", "displayName" to "Guest", "password" to TestAccountFixture.PASSWORD, "role" to "GUEST",
                "expiresAt" to Instant.now().plusSeconds(600).toString(), "guestRoomId" to "meeting",
            ), cookie)
            assertEquals(201, validGuest.status.value)
            assertTrue(validGuest.bodyAsText().contains("\"guestRoomId\":\"meeting\""))
            val reset = client.postJson("/api/v1/admin/accounts/${alice.id}/password", mapOf("password" to "New account password 9!"), cookie)
            assertEquals(200, reset.status.value)
            assertEquals(null, fixture.services.sessions.resolve(oldToken))
            val updated = client.postJson("/api/v1/admin/accounts/${alice.id}/update", mapOf("enabled" to false), cookie)
            assertEquals(200, updated.status.value)
            assertTrue(updated.bodyAsText().contains("\"enabled\":false"))
            val denied = client.postJson("/api/v1/auth/login", mapOf("username" to alice.username, "password" to "New account password 9!"))
            assertEquals(401, denied.status.value)
        }
    }

    @Test
    fun passwordChangeRequiresCurrentPasswordAndRevokesAllAccountSessions() = withFixture { root, fixture ->
        val alice = fixture.account("Alice")
        val first = checkNotNull(fixture.services.sessions.issue(alice))
        val second = checkNotNull(fixture.services.sessions.issue(alice))
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val bad = client.postJson("/api/v1/auth/password", mapOf(
                "currentPassword" to "Wrong password for test", "newPassword" to "Replacement password 9!",
            ), "$SESSION_COOKIE_NAME=$first")
            assertEquals(403, bad.status.value)
            val changed = client.postJson("/api/v1/auth/password", mapOf(
                "currentPassword" to TestAccountFixture.PASSWORD, "newPassword" to "Replacement password 9!",
            ), "$SESSION_COOKIE_NAME=$first")
            assertEquals(200, changed.status.value)
            assertEquals(null, fixture.services.sessions.resolve(first))
            assertEquals(null, fixture.services.sessions.resolve(second))
            assertTrue(changed.headers[HttpHeaders.SetCookie].orEmpty().contains("Max-Age=0", ignoreCase = true))
        }
    }

    @Test
    fun eightCharacterPasswordsAreAcceptedAndSevenRejectedAcrossHttpMutations() = withFixture { root, fixture ->
        val adminCookie = fixture.adminCookie()
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            for (invalid in listOf("x".repeat(7), "x".repeat(129))) {
                assertEquals(400, client.postJson("/api/v1/admin/accounts", mapOf(
                    "username" to "short-user", "displayName" to "Short", "password" to invalid, "role" to "USER",
                ), adminCookie).status.value)
            }
            val password = "Http8! A"
            assertEquals(201, client.postJson("/api/v1/admin/accounts", mapOf(
                "username" to "short-user", "displayName" to "Short", "password" to password, "role" to "USER",
            ), adminCookie).status.value)
            val login = client.postJson("/api/v1/auth/login", mapOf("username" to "short-user", "password" to password))
            assertEquals(200, login.status.value)
            val cookie = checkNotNull(login.headers[HttpHeaders.SetCookie]).substringBefore(';')
            assertEquals(400, client.postJson("/api/v1/auth/password", mapOf(
                "currentPassword" to password, "newPassword" to "x".repeat(7),
            ), cookie).status.value)
            val changed = "Http8! B"
            assertEquals(200, client.postJson("/api/v1/auth/password", mapOf(
                "currentPassword" to password, "newPassword" to changed,
            ), cookie).status.value)
            assertEquals(200, client.postJson("/api/v1/auth/login", mapOf("username" to "short-user", "password" to changed)).status.value)
            val user = checkNotNull(fixture.services.accounts.authenticate("short-user", changed.toCharArray()))
            assertEquals(400, client.postJson("/api/v1/admin/accounts/${user.id}/password", mapOf("password" to "x".repeat(7)), adminCookie).status.value)
            val reset = "Http8! C"
            assertEquals(200, client.postJson("/api/v1/admin/accounts/${user.id}/password", mapOf("password" to reset), adminCookie).status.value)
            assertEquals(200, client.postJson("/api/v1/auth/login", mapOf("username" to "short-user", "password" to reset)).status.value)
        }
    }

    @Test
    fun repeatedLoginRequestsAreRateLimitedWithoutCreatingSessions() = withFixture { root, fixture ->
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            repeat(20) {
                assertEquals(401, client.postJson("/api/v1/auth/login", mapOf("username" to "unknown-user", "password" to TestAccountFixture.PASSWORD)).status.value)
            }
            assertEquals(429, client.postJson("/api/v1/auth/login", mapOf("username" to "unknown-user", "password" to TestAccountFixture.PASSWORD)).status.value)
        }
    }

    private fun withFixture(block: (Path, TestAccountFixture) -> Unit) {
        val root = Files.createTempDirectory("mcasttalk-auth-api-")
        try {
            WorkspaceSetup.initialize(root)
            TestAccountFixture(root).use { block(root, it) }
        } finally { root.toFile().deleteRecursively() }
    }
}

internal suspend fun HttpClient.postJson(
    path: String,
    body: Any?,
    cookie: String? = null,
    origin: String? = "http://localhost",
): HttpResponse = post(path) {
    headers.append(HttpHeaders.Host, "localhost")
    origin?.let { headers.append(HttpHeaders.Origin, it) }
    cookie?.let { headers.append(HttpHeaders.Cookie, it) }
    contentType(ContentType.Application.Json)
    setBody(encodeJson(body))
}
