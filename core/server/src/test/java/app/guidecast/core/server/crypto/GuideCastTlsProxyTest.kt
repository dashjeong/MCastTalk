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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastTlsProxyTest {

    @Test
    fun `concurrent reconnect and close never lose an active peer admission counter`() {
        val limiter = GuideCastTlsConnectionLimiter(32, 2)
        val active = AtomicInteger()
        val maximumObserved = AtomicInteger()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..8).map {
                workers.submit {
                    start.await()
                    repeat(20_000) {
                        val lease = limiter.tryAcquire("192.0.2.1")
                        if (lease != null) {
                            val current = active.incrementAndGet()
                            maximumObserved.updateAndGet { maxOf(it, current) }
                            try { Thread.yield() }
                            finally { active.decrementAndGet(); lease.close(); lease.close() }
                        }
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue("Concurrent peer admission exceeded its cap", maximumObserved.get() <= 2)
            val first = requireNotNull(limiter.tryAcquire("192.0.2.1"))
            val second = requireNotNull(limiter.tryAcquire("192.0.2.1"))
            assertEquals(null, limiter.tryAcquire("192.0.2.1"))
            first.close()
            second.close()
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `tls socket work remains runnable while the shared io admission lane is saturated`() {
        val sharedParallelism = System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
            ?: maxOf(64, Runtime.getRuntime().availableProcessors())
        val blockersStarted = CountDownLatch(sharedParallelism)
        val releaseBlockers = CountDownLatch(1)
        val proxyRan = CountDownLatch(1)
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val proxyScope = CoroutineScope(SupervisorJob() + tlsTransportDispatcher(32))
        try {
            repeat(sharedParallelism) {
                sharedScope.launch {
                    blockersStarted.countDown()
                    releaseBlockers.await()
                }
            }
            assertTrue("Shared IO saturation must be established", blockersStarted.await(10, TimeUnit.SECONDS))
            proxyScope.launch { proxyRan.countDown() }
            assertTrue("Proxy must have independent IO admission", proxyRan.await(3, TimeUnit.SECONDS))
        } finally {
            releaseBlockers.countDown()
            proxyScope.cancel()
            sharedScope.cancel()
        }
    }

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
