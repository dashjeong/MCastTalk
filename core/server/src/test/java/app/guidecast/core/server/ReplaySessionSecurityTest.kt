package app.guidecast.core.server

import app.guidecast.core.stream.*
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReplaySessionSecurityTest {
    private val channel = AudioChannelDescriptor("en", "English", "en", 24_000)
    private val assets = ListenerAssets("<html></html>".toByteArray(), byteArrayOf(), byteArrayOf())

    @Test fun authenticatedReplayReadsFrozenPrefixAndRejectsOtherPartsChannelsAndOversizedReads() = testApplication {
        val folder = Files.createTempDirectory("replay-server").toFile()
        try {
            val file = folder.resolve("audio.pcm").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)) }
            val registry = AudioStreamRegistry(); val stream = registry.configure(listOf(channel))
            val auth = BroadcastSessionAuthenticator.create(BroadcastAccess.QrToken)
            val token = auth.tokenForQr()
            val recording = RecordedBroadcast("own-logical-id", 1, null, "RECORDING", 2, null,
                listOf(RecordedPcmSegment("own-logical-id", 1, channel, 0, 4, file)))
            application { guideCastModule("192.168.1.1", auth, assets, stream, replayProvider = { recording }) }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/replay?channel=en").status)
            val accepted = client.get("/api/replay/1/0?channel=en&offset=0&count=4") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, accepted.status)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), accepted.body<ByteArray>())
            assertTrue(accepted.headers[HttpHeaders.CacheControl].orEmpty().contains("no-store"))
            for (path in listOf("/api/replay/999/0?channel=en&offset=0&count=4", "/api/replay/1/99?channel=en&offset=0&count=4"))
                assertEquals(HttpStatusCode.NotFound, client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }.status)
            for (query in listOf("offset=0&count=8", "offset=1&count=2", "offset=-2&count=2", "offset=0&count=262146", "offset=9223372036854775807&count=2"))
                assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, client.get("/api/replay/1/0?channel=en&$query") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
            assertEquals(HttpStatusCode.Gone, client.get("/api/replay?channel=ru") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
            val crossed = client.get("/api/replay?channel=en") { header(HttpHeaders.Authorization, "Bearer $token"); header(HttpHeaders.Origin, "https://evil.example") }
            assertEquals(HttpStatusCode.Forbidden, crossed.status)
            registry.configure(listOf(channel))
            assertEquals(HttpStatusCode.Gone, client.get("/api/replay?channel=en") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        } finally { folder.deleteRecursively() }
    }
}
