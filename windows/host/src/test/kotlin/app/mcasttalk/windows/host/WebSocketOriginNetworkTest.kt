package app.mcasttalk.windows.host

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketOriginNetworkTest {
    @Test(timeout = 20_000)
    fun rejectsForeignMissingAndNullOriginsOnActualListenerWithoutAffectingHttpHealth() {
        val root = Files.createTempDirectory("mcasttalk-origin-")
        val identity = WorkspaceSetup.initialize(root)
        val fixture = TestAccountFixture(root)
        val cookie = fixture.cookie()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) { mcastTalkModule(root, accountServices = fixture.services) }
        try {
            val base = startAndAwaitReady(server, identity.instanceId, timeoutMillis = 5_000)
            val host = "127.0.0.1:${base.port}"
            val origin = "http://$host"
            assertEquals(101, handshake(base.port, listOf(host), listOf(origin), cookie))
            assertEquals(401, handshake(base.port, listOf(host), listOf(origin)))
            val guest = fixture.services.accounts.createAccount(fixture.admin, "restricted-guest", "Guest", TestAccountFixture.PASSWORD.toCharArray(), AccountRole.GUEST,
                expiresAt = Instant.now().plusSeconds(600), guestRoomId = "different-room")
            val guestCookie = "$SESSION_COOKIE_NAME=" + checkNotNull(fixture.services.sessions.issue(guest))
            assertEquals(403, handshake(base.port, listOf(host), listOf(origin), guestCookie))
            for (origins in listOf(
                emptyList(), listOf("null"), listOf("https://attacker.invalid"),
                listOf("http://127.0.0.1:1"), listOf("http://localhost:${base.port}"),
                listOf(origin, origin), listOf("$origin/"), listOf("https://$host"),
            )) {
                assertEquals("Origin=$origins", 403, handshake(base.port, listOf(host), origins))
            }
            for (hosts in listOf(listOf("attacker.invalid:${base.port}"), listOf("127.0.0.1:1"))) {
                assertEquals("Host=$hosts", 403, handshake(base.port, hosts, listOf(origin)))
            }
            val ready = base.resolve("health/ready").toURL().openConnection() as HttpURLConnection
            try {
                ready.connectTimeout = 1_000
                ready.readTimeout = 1_000
                assertEquals(200, ready.responseCode)
            } finally { ready.disconnect() }
        } finally {
            server.stop(0, 1_000, TimeUnit.MILLISECONDS)
            fixture.close()
            root.toFile().deleteRecursively()
        }
    }

    // Raw HTTP avoids test-engine implicit Host defaults and the client's protected Upgrade header.
    private fun handshake(port: Int, hosts: List<String>, origins: List<String>, cookie: String? = null): Int = Socket().use { socket ->
        socket.soTimeout = 2_000
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        val request = buildString {
            append("GET /ws/v1/rooms/origin-test HTTP/1.1\r\n")
            hosts.forEach { append("Host: $it\r\n") }
            origins.forEach { append("Origin: $it\r\n") }
            cookie?.let { append("Cookie: $it\r\n") }
            append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n")
        }
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
        checkNotNull(statusLine) { "Listener closed without a response" }
        statusLine.split(' ')[1].toInt()
    }
}
