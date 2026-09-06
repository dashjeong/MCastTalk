package app.guidecast.core.server

import app.guidecast.core.server.crypto.GuideCastCertificateAuthority
import app.guidecast.core.server.crypto.GuideCastTlsBackendAuthenticator
import app.guidecast.core.server.crypto.GuideCastTlsProxy
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastDirectTlsIntegrationTest {
    @Test
    fun `isolated TLS backend rejects HTTP spoof and accepts cookie authenticated WSS`() = runBlocking {
        val tempDir = File.createTempFile("guidecast-isolated-tls", "")
        tempDir.delete()
        tempDir.mkdirs()
        val loopback = InetAddress.getByName("127.0.0.1")

        try {
            val ca = GuideCastCertificateAuthority.getOrCreate(tempDir)
            val certificate = ca.issueServerCertificate(
                hostAddress = loopback,
                dnsName = "localhost",
                validityDays = 1,
            )
            val authenticator = BroadcastSessionAuthenticator.create(
                access = BroadcastAccess.Open,
                speakerAccess = SpeakerAccess.Pin.from(charArrayOf('2', '4', '6', '8')),
            )
            val streams = AudioStreamRegistry().apply {
                configure(listOf(AudioChannelDescriptor("source", "Original", "ko-KR", 16_000)))
            }
            val host = requireNotNull(loopback.hostAddress)
            val httpsPort = ServerSocket(0, 1, loopback).use { it.localPort }
            val backendAuthenticator = GuideCastTlsBackendAuthenticator.create()
            val secureBackend = embeddedServer(ServerCIO, host = host, port = 0) {
                guideCastModule(
                    expectedHost = host,
                    authenticator = authenticator,
                    assets = listenerAssets(),
                    streamSession = streams.currentSession(),
                    speakerAssets = speakerAssets(),
                    httpsPort = httpsPort,
                    transportSecurity = GuideCastTransportSecurity.TLS_TERMINATED,
                    tlsBackendAuthenticator = backendAuthenticator,
                )
            }.start(wait = false)
            val plainServer = embeddedServer(ServerCIO, host = host, port = 0) {
                guideCastModule(
                    expectedHost = host,
                    authenticator = authenticator,
                    assets = listenerAssets(),
                    streamSession = streams.currentSession(),
                    speakerAssets = speakerAssets(),
                    httpsPort = httpsPort,
                    transportSecurity = GuideCastTransportSecurity.PLAIN_HTTP,
                )
            }.start(wait = false)
            val backendPort = secureBackend.engine.resolvedConnectors().single().port
            val httpPort = plainServer.engine.resolvedConnectors().single().port
            val proxy = GuideCastTlsProxy(
                bindAddress = loopback,
                httpsPort = httpsPort,
                targetAddress = loopback,
                targetPort = backendPort,
                keyStore = certificate.toKeyStore(),
                backendAuthenticator = backendAuthenticator,
            ).apply { start() }

            try {
                val trustManager = trustManagerFor(ca.caCertificate)
                val client = HttpClient(ClientCIO) {
                    install(ClientWebSockets)
                    engine {
                        https { this.trustManager = trustManager }
                    }
                }

                try {
                    val secureOrigin = "https://$host:$httpsPort"
                    val directBackendJoin = client.post("http://$host:$backendPort/api/mic/join") {
                        header(HttpHeaders.Host, "$host:$httpsPort")
                        header(HttpHeaders.Origin, secureOrigin)
                        header(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                        setBody("2468")
                    }
                    assertEquals(421, directBackendJoin.status.value)
                    assertEquals(null, directBackendJoin.headers[HttpHeaders.SetCookie])

                    val forgedBackendJoin = client.post("http://$host:$backendPort/api/mic/join") {
                        header(HttpHeaders.Host, "$host:$httpsPort")
                        header(HttpHeaders.Origin, secureOrigin)
                        header(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                        header("X-GuideCast-Internal-Version", "1")
                        header("X-GuideCast-Internal-Peer", "192.168.45.20")
                        header("X-GuideCast-Internal-Nonce", "AAAAAAAAAAAAAAAAAAAAAA")
                        header("X-GuideCast-Internal-Mac", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
                        setBody("2468")
                    }
                    assertEquals(421, forgedBackendJoin.status.value)
                    assertEquals(null, forgedBackendJoin.headers[HttpHeaders.SetCookie])

                    val spoofedSession = client.get("http://$host:$httpPort/api/mic/session") {
                        header(HttpHeaders.Host, "$host:$httpsPort")
                        header(HttpHeaders.Origin, secureOrigin)
                        header("X-Forwarded-Proto", "https")
                    }
                    assertEquals(426, spoofedSession.status.value)

                    val spoofedJoin = client.post("http://$host:$httpPort/api/mic/join") {
                        header(HttpHeaders.Host, "$host:$httpsPort")
                        header(HttpHeaders.Origin, secureOrigin)
                        header("X-Forwarded-Proto", "https")
                        header(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                        setBody("2468")
                    }
                    assertEquals(426, spoofedJoin.status.value)
                    assertEquals(null, spoofedJoin.headers[HttpHeaders.SetCookie])

                    val secureSession = client.get("$secureOrigin/api/mic/session") {
                        header(HttpHeaders.Origin, secureOrigin)
                    }
                    assertEquals(HttpStatusCode.OK, secureSession.status)
                    assertEquals(
                        "{\"requiresPin\":true,\"authenticated\":false}",
                        secureSession.body<String>(),
                    )

                    val secureJoin = client.post("$secureOrigin/api/mic/join") {
                        header(HttpHeaders.Origin, secureOrigin)
                        header(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                        setBody("2468")
                    }
                    assertEquals(HttpStatusCode.OK, secureJoin.status)
                    val cookie = secureJoin.headers[HttpHeaders.SetCookie]
                    assertNotNull(cookie)
                    assertTrue(requireNotNull(cookie).contains("; Secure;"))
                    assertTrue(cookie.contains("; HttpOnly;"))
                    assertTrue(cookie.contains("SameSite=Strict"))
                    val token = cookie.substringAfter('=').substringBefore(';')
                    assertFalse(secureJoin.body<String>().contains(token))

                    client.webSocket(
                        urlString = "wss://$host:$httpsPort/ws/speaker-input?token=$token",
                        request = { header(HttpHeaders.Origin, secureOrigin) },
                    ) {
                        val reason = closeReason.await()
                        assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
                    }

                    client.webSocket(
                        urlString = "wss://$host:$httpsPort/ws/speaker-input",
                        request = {
                            header(HttpHeaders.Origin, secureOrigin)
                            header(HttpHeaders.Cookie, "guidecast_speaker_session=$token")
                        },
                    ) {
                        val state = incoming.receive() as Frame.Text
                        assertTrue(state.readText().contains("\"ready\":true"))
                        close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
                    }
                } finally {
                    client.close()
                }
            } finally {
                proxy.close()
                plainServer.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
                secureBackend.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun listenerAssets() = ListenerAssets(
        html = "<!doctype html><title>GuideCast</title>".encodeToByteArray(),
        javascript = "'use strict';".encodeToByteArray(),
        css = "body{}".encodeToByteArray(),
    )

    private fun speakerAssets() = SpeakerAssets(
        html = "<!doctype html><title>Speaker</title>".encodeToByteArray(),
        javascript = "'use strict';".encodeToByteArray(),
        css = "body{}".encodeToByteArray(),
    )

    private fun trustManagerFor(certificate: java.security.cert.X509Certificate): X509TrustManager {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("guidecast-root", certificate)
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(trustStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }
}
