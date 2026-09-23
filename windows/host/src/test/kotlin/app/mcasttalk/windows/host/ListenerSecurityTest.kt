package app.mcasttalk.windows.host

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.net.Socket
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Regression requirements for the independently reviewed 0.4.0 listener proposal. */
class ListenerSecurityTest {
    @Test fun anonymousListenerRequiresAuthentication() = withFixture { root, fixture ->
        assertEquals(401, listenerStatus(root, fixture))
    }

    @Test fun listenerCannotBypassGuestRoomScope() = withFixture { root, fixture ->
        val guest = fixture.services.accounts.createAccount(fixture.admin, "guest-review", "Guest",
            TestAccountFixture.PASSWORD.toCharArray(), AccountRole.GUEST,
            expiresAt = Instant.now().plusSeconds(600), guestRoomId = "allowed-room")
        val cookie = "$SESSION_COOKIE_NAME=" + fixture.services.sessions.issue(guest)
        assertEquals(403, listenerStatus(root, fixture, cookie))
    }

    @Test fun listenerRejectsForeignOriginEvenWithAnAccount() = withFixture { root, fixture ->
        assertEquals(403, listenerStatus(root, fixture, fixture.cookie(), "https://attacker.invalid"))
    }

    @Test fun listenerRejectsUnknownSessionToken() = withFixture { root, fixture ->
        assertEquals(401, listenerStatus(root, fixture, "$SESSION_COOKIE_NAME=invalid-review-token"))
    }

    @Test fun listenerIdentityAndRoleCannotBeForged() = withFixture { root, fixture ->
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val sockets = createClient { install(WebSockets) }
            val socket = sockets.connect(fixture.cookie())
            try {
                val joined = socket.join()
                assertTrue(joined.contains("\"displayName\":\"Alice\""))
                assertTrue(joined.contains("\"role\":\"listener\""))
                assertFalse(joined.contains("Forged moderator"))
            } finally { socket.close() }
        }
    }

    @Test fun administratorRevokesAnIdleListener() = withFixture { root, fixture ->
        val alice = fixture.account("Alice")
        val cookie = fixture.cookie()
        val adminCookie = fixture.adminCookie()
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val socket = createClient { install(WebSockets) }.connect(cookie)
            try {
                socket.join()
                assertEquals(200, client.postJson("/api/v1/admin/accounts/${alice.id}/update", mapOf("enabled" to false), adminCookie).status.value)
                assertEquals(1008, withTimeout(5_000) { socket.closeReason.await() }?.code?.toInt())
            } finally { socket.close() }
        }
    }

    @Test fun listenerCannotPublishChatMediaOrSubtitles() = withFixture { root, fixture ->
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val socket = createClient { install(WebSockets) }.connect(fixture.cookie())
            try {
                socket.join()
                for (frame in listOf(Frame.Text("""{"type":"CHAT_SEND","text":"injected"}"""),
                    Frame.Binary(true, byteArrayOf(1, 2)),
                    Frame.Text("""{"type":"SUBTITLE_CHUNK","translatedText":"forged"}"""))) {
                    socket.send(frame)
                    assertTrue(socket.text().contains("NOT_PERMITTED"))
                }
                socket.send(Frame.Text("""{"type":"PING"}"""))
                assertTrue(socket.text().contains("PONG"))
            } finally { socket.close() }
        }
    }

    @Test fun listenerReceivesRoomChatWithAuthoritativeSpeaker() = withFixture { root, fixture ->
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val sockets = createClient { install(WebSockets) }
            val listener = sockets.connect(fixture.cookie())
            val speaker = sockets.connect(fixture.cookie("Bob"), "")
            try {
                listener.join()
                speaker.join("speaker-one")
                assertTrue(listener.text().contains("PARTICIPANT_JOINED"))
                speaker.send(Frame.Text("""{"type":"CHAT_SEND","text":"room message"}"""))
                val message = listener.text()
                assertTrue(message.contains("room message"))
                assertTrue(message.contains("\"senderDisplayName\":\"Bob\""))
            } finally { listener.close(); speaker.close() }
        }
    }

    @Test fun speakerCannotForgeInferenceOrSendUngatedMedia() = withFixture { root, fixture ->
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val socket = createClient { install(WebSockets) }.connect(fixture.cookie(), "")
            try {
                socket.join()
                socket.send(Frame.Text("""{"type":"SUBTITLE_CHUNK","translatedText":"forged translation"}"""))
                assertTrue(socket.text().contains("INFERENCE_NOT_READY"))
                socket.send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                assertTrue(socket.text().contains("MEDIA_NOT_READY"))
            } finally { socket.close() }
        }
    }

    @Test fun listenerLogoutClosesSocket() = withFixture { root, fixture ->
        val cookie = fixture.cookie()
        testApplication {
            application { mcastTalkModule(root, accountServices = fixture.services) }
            val socket = createClient { install(WebSockets) }.connect(cookie)
            try {
                socket.join()
                assertEquals(200, client.postJson("/api/v1/auth/logout", emptyMap<String, String>(), cookie).status.value)
                assertEquals(1008, withTimeout(5_000) { socket.closeReason.await() }?.code?.toInt())
            } finally { socket.close() }
        }
    }

    private suspend fun HttpClient.connect(cookie: String, suffix: String = "/listen"): DefaultClientWebSocketSession =
        webSocketSession("/ws/v1/rooms/review-room$suffix") {
            headers.append(HttpHeaders.Host, "localhost")
            headers.append(HttpHeaders.Origin, "http://localhost")
            headers.append(HttpHeaders.Cookie, cookie)
        }

    private suspend fun DefaultClientWebSocketSession.join(id: String = "listener-one"): String {
        send(Frame.Text("""{"type":"JOIN_ROOM","participantId":"$id","displayName":"Forged moderator","role":"moderator","inputLanguage":"ko","listenLanguage":"en"}"""))
        val joined = text()
        assertTrue(joined.contains("ROOM_JOINED"))
        assertTrue(text().contains("PARTICIPANT_JOINED"))
        return joined
    }

    private suspend fun DefaultClientWebSocketSession.text(): String = withTimeout(5_000) { (incoming.receive() as Frame.Text).readText() }

    // A real HTTP upgrade: Ktor's ordinary HTTP client intentionally forbids Upgrade headers.
    private fun listenerStatus(root: Path, fixture: TestAccountFixture, cookie: String? = null, origin: String? = null): Int {
        val identity = DataRootGate.requireInitialized(root)
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { mcastTalkModule(root, accountServices = fixture.services) }
        try {
            val base = startAndAwaitReady(server, identity.instanceId, timeoutMillis = 5_000)
            val host = "127.0.0.1:${base.port}"
            return Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(InetSocketAddress("127.0.0.1", base.port), 2_000)
                val request = buildString {
                    append("GET /ws/v1/rooms/private-room/listen HTTP/1.1\r\nHost: $host\r\nOrigin: ${origin ?: "http://$host"}\r\n")
                    cookie?.let { append("Cookie: $it\r\n") }
                    append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")
                }
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                checkNotNull(socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()).split(' ')[1].toInt()
            }
        } finally { server.stop(0, 1_000, TimeUnit.MILLISECONDS) }
    }

    private fun withFixture(block: (Path, TestAccountFixture) -> Unit) {
        val root = Files.createTempDirectory("mcasttalk-listener-review-")
        try { WorkspaceSetup.initialize(root); TestAccountFixture(root).use { block(root, it) } }
        finally { root.toFile().deleteRecursively() }
    }
}
