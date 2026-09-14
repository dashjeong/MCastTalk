package app.guidecast.transmitter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BoundedCloudHttpsTest {
    private open class Connection(url: URL, private val bytes: ByteArray = "{}".toByteArray(), private val code: Int = 200) : HttpsURLConnection(url) {
        @Volatile var disconnected = false
        var inputReads = 0
        val sent = ByteArrayOutputStream()
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getOutputStream() = sent
        override fun getResponseCode() = code
        override fun getContentLengthLong() = -1L
        override fun getInputStream(): InputStream { inputReads++; return ByteArrayInputStream(bytes) }
    }

    @Test fun rejectsAllNonOfficialOriginsPathsQueriesAndRedirects() = runBlocking {
        var opened = 0
        val http = BoundedCloudHttps { opened++; Connection(it, code = 302) }
        for (url in listOf("http://api.openai.com/v1/responses", "https://api.openai.com.evil.test/v1/responses",
            "https://api.openai.com/v1/responses?key=secret", "https://api.openai.com/other",
            "https://user@api.openai.com/v1/responses")) {
            assertNull(http.post(url, CloudReviewProvider.OPENAI, "synthetic", "{}"))
        }
        assertEquals(0, opened)
        val connection = Connection(URL(OPENAI), code = 307)
        assertNull(BoundedCloudHttps { connection }.post(OPENAI, CloudReviewProvider.OPENAI, "synthetic", "{}"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(0, connection.inputReads)
        assertTrue(connection.disconnected)
    }

    @Test fun responseLimitIsEnforcedEvenWithoutContentLengthAndKeyUsesHeaderOnly() = runBlocking {
        val oversized = Connection(URL(OPENAI), ByteArray(65_537) { 65 })
        assertNull(BoundedCloudHttps { oversized }.post(OPENAI, CloudReviewProvider.OPENAI, "synthetic-secret", "{}"))
        assertTrue(oversized.disconnected)
        assertEquals("Bearer synthetic-secret", oversized.getRequestProperty("Authorization"))
        assertEquals("{}", oversized.sent.toString())
        assertFalse(oversized.url.toString().contains("synthetic-secret"))
        assertTrue(oversized.connectTimeout in 1..500)
        assertTrue(oversized.readTimeout in 1..1_000)
    }

    @Test fun revokedConsentBeforeDispatchSendsNoBodyOrHeaders() = runBlocking {
        var allowed = true
        val connection = Connection(URL(OPENAI))
        val http = BoundedCloudHttps { allowed = false; connection }
        assertNull(http.post(OPENAI, CloudReviewProvider.OPENAI, "synthetic", "{}") { allowed })
        assertEquals(0, connection.sent.size())
        assertNull(connection.getRequestProperty("Authorization"))
        assertTrue(connection.disconnected)
    }

    @Test fun consentRevokedWhileOpeningOutputStreamSendsNoBody() = runBlocking {
        var allowed = true
        val connection = object : Connection(URL(OPENAI)) {
            override fun getOutputStream(): ByteArrayOutputStream {
                // Model consent changing during connection/TLS setup, after the dispatch check.
                allowed = false
                return sent
            }
        }
        assertNull(BoundedCloudHttps { connection }.post(OPENAI, CloudReviewProvider.OPENAI,
            "synthetic", "{\"original\":\"synthetic source\"}") { allowed })
        assertEquals(0, connection.sent.size())
        assertEquals(0, connection.inputReads)
        assertTrue(connection.disconnected)
        assertTrue(connection.connectTimeout in 1..500)
        assertTrue(connection.readTimeout in 1..1_000)
    }

    @Test fun consentRevokedDuringResponseReadDiscardsEvenAnEofResult() = runBlocking {
        var allowed = true
        val connection = object : Connection(URL(OPENAI)) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int { allowed = false; return -1 }
            }
        }
        assertNull(BoundedCloudHttps { connection }.post(OPENAI, CloudReviewProvider.OPENAI,
            "synthetic", "{}") { allowed })
        assertEquals("{}", connection.sent.toString())
        assertTrue(connection.disconnected)
        assertTrue(connection.readTimeout in 1..1_000)
    }

    @Test fun cancellationDuringLateConnectionCreationStillDisconnectsIt() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = Connection(URL(OPENAI))
        val http = BoundedCloudHttps {
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            connection
        }
        val request = launch(Dispatchers.Default) { http.post(OPENAI, CloudReviewProvider.OPENAI, "synthetic", "{}") }
        try {
            withTimeout(1_000L) { while (entered.count > 0) delay(5L) }
            withTimeout(100L) { request.cancelAndJoin() }
            assertFalse(connection.disconnected)
            release.countDown()
            withTimeout(1_000L) { while (!connection.disconnected) delay(5L) }
            assertEquals(0, connection.inputReads)
        } finally { release.countDown(); request.cancelAndJoin() }
    }

    @Test fun cancellationDisconnectsActiveReadAndReturnsPromptly() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = object : Connection(URL(OPENAI)) {
            override fun getInputStream() = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    return -1
                }
            }
            override fun disconnect() { super.disconnect(); release.countDown() }
        }
        val request = launch(Dispatchers.Default) {
            BoundedCloudHttps { connection }.post(OPENAI, CloudReviewProvider.OPENAI, "synthetic", "{}")
        }
        try {
            withTimeout(1_000L) { while (entered.count > 0) delay(5L) }
            withTimeout(100L) { request.cancelAndJoin() }
            assertTrue(connection.disconnected)
        } finally { release.countDown(); request.cancelAndJoin() }
    }

    private companion object { const val OPENAI = "https://api.openai.com/v1/responses" }
}
