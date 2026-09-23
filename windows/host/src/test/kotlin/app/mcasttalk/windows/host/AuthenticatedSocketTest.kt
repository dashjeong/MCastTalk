package app.mcasttalk.windows.host

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedSocketTest {
    @Test
    fun passiveSessionPollingAndAutomaticPingDoNotExtendIdleSession() {
        val root = Files.createTempDirectory("mcasttalk-idle-socket-")
        WorkspaceSetup.initialize(root)
        val now = AtomicReference(Instant.parse("2026-09-19T00:00:00Z"))
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now.get()
        }
        val accounts = LocalAccounts(root, clock)
        val services = AccountServices(accounts, LocalSessions(accounts, clock))
        try {
            val admin = accounts.createInitialAdmin("admin", "Admin", TestAccountFixture.PASSWORD.toCharArray())
            val token = checkNotNull(services.sessions.issue(admin))
            val cookie = "$SESSION_COOKIE_NAME=$token"
            testApplication {
                application { mcastTalkModule(root, accountServices = services) }
                val sockets = createClient { install(WebSockets) }
                val session = sockets.connect("meeting", cookie)
                try {
                    session.join("admin-one", "Admin")
                    now.set(now.get().plusSeconds(LocalSessions.IDLE_SECONDS - 1))
                    val polled = client.get("/api/v1/auth/session") {
                        headers.append(HttpHeaders.Host, "localhost")
                        headers.append(HttpHeaders.Cookie, cookie)
                    }
                    assertEquals(200, polled.status.value)
                    assertTrue(polled.bodyAsText().contains("\"authenticated\":true"))
                    session.drainToPong()
                    now.set(now.get().plusSeconds(1))
                    assertEquals(1008, withTimeout(5_000) { session.closeReason.await() }?.code?.toInt())
                    assertEquals(null, services.sessions.resolve(token, touch = false))
                } finally { session.close() }
            }
        } finally { services.close(); root.toFile().deleteRecursively() }
    }

    @Test
    fun successfulBrowserAccountSwitchRevokesOldSocketButFailedLoginPreservesIt() = withFixture { root, fixture ->
        fixture.account("Alice")
        val bob = fixture.account("Bob")
        val aliceCookie = fixture.cookie("Alice")
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val sockets = createClient { install(WebSockets) }
            val session = sockets.connect("meeting", aliceCookie)
            try {
                session.join("alice-one", "Alice")
                val failed = client.postJson("/api/v1/auth/login", mapOf(
                    "username" to bob.username, "password" to "Wrong password for test",
                ), aliceCookie)
                assertEquals(401, failed.status.value)
                session.drainToPong()
                assertTrue(fixture.services.sessions.resolve(aliceCookie.substringAfter('='), touch = false) != null)

                val switched = client.postJson("/api/v1/auth/login", mapOf(
                    "username" to bob.username, "password" to TestAccountFixture.PASSWORD,
                ), aliceCookie)
                assertEquals(200, switched.status.value)
                assertEquals(1008, withTimeout(5_000) { session.closeReason.await() }?.code?.toInt())
                assertEquals(null, fixture.services.sessions.resolve(aliceCookie.substringAfter('='), touch = false))
                val replacement = checkNotNull(switched.headers[HttpHeaders.SetCookie]).substringBefore(';').substringAfter('=')
                assertEquals(bob.id, fixture.services.sessions.resolve(replacement, touch = false)?.id)
            } finally { session.close() }
        }
    }

    @Test
    fun websocketIdentityComesFromAccountAndLogoutClosesOnlyTheRevokedSession() = withFixture { root, fixture ->
        val alice = fixture.account("Alice")
        val firstCookie = fixture.cookie("Alice")
        val secondCookie = fixture.cookie("Alice")
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val sockets = createClient { install(WebSockets) }
            val first = sockets.connect("meeting", firstCookie)
            val second = sockets.connect("meeting", secondCookie)
            try {
                val snapshot = first.join("alice-one", "Forged administrator")
                assertTrue(snapshot.contains("\"displayName\":\"Alice\""))
                assertTrue(snapshot.contains("\"accountId\":\"${alice.id}\""))
                assertTrue(snapshot.contains("\"username\":\"${alice.username}\""))
                assertFalse(snapshot.contains("Forged administrator"))
                second.join("alice-two", "Also forged")
                first.drainToPong()
                second.drainToPong()
                assertEquals(200, client.postJson("/api/v1/auth/logout", emptyMap<String, String>(), firstCookie).status.value)
                assertEquals(1008, withTimeout(5_000) { first.closeReason.await() }?.code?.toInt())
                second.drainToPong()
                assertTrue(fixture.services.sessions.resolve(secondCookie.substringAfter('='), touch = false) != null)
            } finally { first.close(); second.close() }
        }
    }

    @Test
    fun administratorDisableClosesConnectedUserAndRemovesMembership() = withFixture { root, fixture ->
        val alice = fixture.account("Alice")
        val cookie = fixture.cookie("Alice")
        val adminCookie = fixture.adminCookie()
        testApplication {
            val directory = RoomDirectory()
            application { mcastTalkModule(root, directory, accountServices = fixture.services) }
            val sockets = createClient { install(WebSockets) }
            val session = sockets.connect("meeting", cookie)
            try {
                session.join("alice-one", "Alice")
                val disabled = client.postJson("/api/v1/admin/accounts/${alice.id}/update", mapOf("enabled" to false), adminCookie)
                assertEquals(200, disabled.status.value)
                assertEquals(1008, withTimeout(5_000) { session.closeReason.await() }?.code?.toInt())
                withTimeout(5_000) {
                    while (!directory.snapshot("meeting")?.participants.isNullOrEmpty()) delay(10)
                }
                assertEquals(null, fixture.services.sessions.resolve(cookie.substringAfter('=')))
            } finally { session.close() }
        }
    }

    @Test
    fun guestExpiresWhileIdleAndIsRemovedWithoutWaitingForAnotherMessage() {
        val root = Files.createTempDirectory("mcasttalk-guest-socket-")
        WorkspaceSetup.initialize(root)
        val now = AtomicReference(Instant.parse("2026-09-19T00:00:00Z"))
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now.get()
        }
        val accounts = LocalAccounts(root, clock)
        val services = AccountServices(accounts, LocalSessions(accounts, clock))
        try {
            val admin = accounts.createInitialAdmin("admin", "Admin", TestAccountFixture.PASSWORD.toCharArray())
            val guest = accounts.createAccount(admin, "guest-one", "Guest", TestAccountFixture.PASSWORD.toCharArray(), AccountRole.GUEST,
                expiresAt = now.get().plusSeconds(60), guestRoomId = "meeting")
            val token = checkNotNull(services.sessions.issue(guest))
            testApplication {
                val directory = RoomDirectory()
                application { mcastTalkModule(root, directory, accountServices = services) }
                val sockets = createClient { install(WebSockets) }
                val session = sockets.connect("meeting", "$SESSION_COOKIE_NAME=$token")
                try {
                    session.join("guest-one", "Guest")
                    now.set(now.get().plusSeconds(61))
                    assertEquals(1008, withTimeout(5_000) { session.closeReason.await() }?.code?.toInt())
                    withTimeout(5_000) {
                        while (!directory.snapshot("meeting")?.participants.isNullOrEmpty()) delay(10)
                    }
                } finally { session.close() }
            }
        } finally { services.close(); root.toFile().deleteRecursively() }
    }

    private suspend fun HttpClient.connect(room: String, cookie: String): DefaultClientWebSocketSession =
        webSocketSession("/ws/v1/rooms/$room") {
            headers.append(HttpHeaders.Host, "localhost")
            headers.append(HttpHeaders.Origin, "http://localhost")
            headers.append(HttpHeaders.Cookie, cookie)
        }

    private suspend fun DefaultClientWebSocketSession.join(id: String, name: String): String {
        send(Frame.Text(encodeJson(mapOf(
            "type" to "JOIN_ROOM", "participantId" to id, "displayName" to name,
            "accountId" to "forged-id", "username" to "forged-user",
            "inputLanguage" to "ko", "listenLanguage" to "ko",
        ))))
        val joined = receiveText()
        assertTrue(joined.contains("ROOM_JOINED"))
        assertTrue(receiveText().contains("PARTICIPANT_JOINED"))
        return joined
    }

    private suspend fun DefaultClientWebSocketSession.receiveText(): String =
        withTimeout(5_000) { (incoming.receive() as Frame.Text).readText() }

    private suspend fun DefaultClientWebSocketSession.drainToPong() {
        send(Frame.Text("""{"type":"PING"}"""))
        while (!receiveText().contains("\"type\":\"PONG\"")) { }
    }

    private fun withFixture(block: (Path, TestAccountFixture) -> Unit) {
        val root = Files.createTempDirectory("mcasttalk-auth-socket-")
        try {
            WorkspaceSetup.initialize(root)
            TestAccountFixture(root).use { block(root, it) }
        } finally { root.toFile().deleteRecursively() }
    }
}
