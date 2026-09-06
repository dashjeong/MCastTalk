package app.guidecast.core.server.crypto

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastTlsProxyTest {

    @Test
    fun `tls proxy terminates https and forwards plain http to ktor server`() = runBlocking {
        val tempDir = File.createTempFile("guidecast-proxy-test", "")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val ca = GuideCastCertificateAuthority.getOrCreate(tempDir)
            val loopback = InetAddress.getByName("127.0.0.1")
            val configuredBackendAddress = InetAddress.getByName("192.0.2.10")
            val bundle = ca.issueServerCertificate(
                hostAddress = loopback,
                dnsName = "localhost",
                validityDays = 1,
            )
            val keyStore = bundle.toKeyStore()
            val backendAuthenticator = GuideCastTlsBackendAuthenticator.create()

            // 1. Plain HTTP Ktor server on ephemeral port
            val ktorServer = embeddedServer(CIO, host = requireNotNull(loopback.hostAddress), port = 0) {
                routing {
                    get("/ping") {
                        val peer = backendAuthenticator.verify { name -> call.request.headers.getAll(name) }
                        if (peer == null) {
                            call.respondText(
                                "untrusted",
                                status = HttpStatusCode(421, "Misdirected Request"),
                            )
                        } else {
                            call.respondText("pong from $peer")
                        }
                    }
                }
            }.start(wait = false)

            val ktorPort = ktorServer.engine.resolvedConnectors().first().port
            assertTrue("Ktor port must be allocated", ktorPort > 0)
            var requestedBackendAddress: InetAddress? = null
            val backendConnections = AtomicInteger()

            // 2. GuideCastTlsProxy on ephemeral port
            val proxy = GuideCastTlsProxy(
                bindAddress = loopback,
                httpsPort = 0,
                targetAddress = configuredBackendAddress,
                targetPort = ktorPort,
                keyStore = keyStore,
                backendAuthenticator = backendAuthenticator,
                targetSocketFactory = { address, port ->
                    requestedBackendAddress = address
                    backendConnections.incrementAndGet()
                    // Connect the test transport to loopback while retaining proof that the
                    // proxy asked for the configured alpha backend address.
                    Socket(loopback, port)
                },
            )
            proxy.start()

            try {
                val httpsPort = proxy.actualPort
                assertTrue("Proxy HTTPS port must be allocated", httpsPort > 0)

                // A raw/idle TCP peer must fail TLS before the proxy allocates any backend socket.
                Socket(loopback, httpsPort).use { raw ->
                    raw.soTimeout = 2_000
                    raw.getOutputStream().write(
                        "GET /ping HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(),
                    )
                    runCatching { raw.getInputStream().read() }
                }
                assertEquals(0, backendConnections.get())

                // 3. Client trusting only our Root CA
                val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
                trustStore.load(null, null)
                trustStore.setCertificateEntry("ca", ca.caCertificate)

                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(trustStore)
                val clientSslContext = SSLContext.getInstance("TLS")
                clientSslContext.init(null, tmf.trustManagers, null)

                val url = URL("https://127.0.0.1:$httpsPort/ping")
                val connection = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = clientSslContext.socketFactory
                    hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    connectTimeout = 5000
                    readTimeout = 5000
                    // Client attempts to forge the internal channel; the proxy must strip it and
                    // replace it with its own per-connection attestation.
                    setRequestProperty(GuideCastTlsBackendAuthenticator.VERSION_HEADER, "forged")
                    setRequestProperty(GuideCastTlsBackendAuthenticator.PEER_HEADER, "203.0.113.99")
                    setRequestProperty(GuideCastTlsBackendAuthenticator.NONCE_HEADER, "forged")
                    setRequestProperty(GuideCastTlsBackendAuthenticator.MAC_HEADER, "forged")
                }

                assertEquals(200, connection.responseCode)
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                assertEquals("pong from 127.0.0.1", responseText)
                assertEquals(configuredBackendAddress, requestedBackendAddress)
                assertEquals(1, backendConnections.get())
            } finally {
                proxy.close()
                ktorServer.stop(gracePeriodMillis = 100, timeoutMillis = 500)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `connection limiter bounds aggregate and per peer leases and releases exactly once`() {
        val limiter = GuideCastTlsConnectionLimiter(
            maxConcurrentConnections = 3,
            maxConnectionsPerPeer = 2,
        )
        val peerOneFirst = requireNotNull(limiter.tryAcquire("192.168.45.20"))
        val peerOneSecond = requireNotNull(limiter.tryAcquire("192.168.45.20"))
        assertEquals(null, limiter.tryAcquire("192.168.45.20"))
        val peerTwo = requireNotNull(limiter.tryAcquire("192.168.45.21"))
        assertEquals(null, limiter.tryAcquire("192.168.45.22"))

        peerOneFirst.close()
        peerOneFirst.close()
        val peerThree = requireNotNull(limiter.tryAcquire("192.168.45.22"))

        peerOneSecond.close()
        peerTwo.close()
        peerThree.close()
        requireNotNull(limiter.tryAcquire("192.168.45.20")).close()
    }
}
