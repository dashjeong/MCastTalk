package app.guidecast.core.server

import app.guidecast.core.server.crypto.GuideCastTlsBackendAuthenticator
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastServerSecurityTest {
    @Test
    fun `listener page sends restrictive browser security headers`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertTrue(response.headers["Content-Security-Policy"].orEmpty().contains("object-src 'none'"))
        assertEquals("camera=(), microphone=(), geolocation=()", response.headers["Permissions-Policy"])
    }

    @Test
    fun `configured language path serves pinned player and unknown path is 404`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        fixture.streams.configure(
            listOf(
                AudioChannelDescriptor("en", "English", "en-US", 24_000),
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000),
            ),
        )
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }

        val english = client.get("/en")
        val unknown = client.get("/de")

        assertEquals(HttpStatusCode.OK, english.status)
        assertEquals("DENY", english.headers["X-Frame-Options"])
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }

    @Test
    fun `legacy jp path permanently redirects to canonical ja path`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        fixture.streams.configure(
            listOf(AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000)),
        )
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }
        val noRedirectClient = createClient { followRedirects = false }

        val response = noRedirectClient.get("/jp")

        assertEquals(HttpStatusCode.PermanentRedirect, response.status)
        assertEquals("/ja", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `invalid host is rejected to limit DNS rebinding`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }

        val response = client.get("/api/status") {
            header(HttpHeaders.Host, "attacker.example")
        }

        assertEquals(421, response.status.value)
    }

    @Test
    fun `cross origin api request is rejected while matching or absent origin is accepted`() =
        testApplication {
            val fixture = fixture(BroadcastAccess.Open)
            application {
                guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams)
            }

            val crossOrigin = client.get("/api/status") {
                header(HttpHeaders.Origin, "http://attacker.example")
            }
            val wrongPort = client.get("/api/status") {
                header(HttpHeaders.Origin, "http://localhost:8787")
            }
            val matchingOrigin = client.get("/api/status") {
                header(HttpHeaders.Origin, "http://localhost")
            }
            val absentOrigin = client.get("/api/status")

            assertEquals(HttpStatusCode.Forbidden, crossOrigin.status)
            assertEquals(HttpStatusCode.Forbidden, wrongPort.status)
            assertEquals(HttpStatusCode.OK, matchingOrigin.status)
            assertEquals(HttpStatusCode.OK, absentOrigin.status)
        }

    @Test
    fun `read api limit is enforced before bearer authentication`() = testApplication {
        val fixture = fixture(BroadcastAccess.QrToken)
        val admissionController = LocalRequestAdmissionController(
            readApiLimit = LocalRequestLimit(
                maxRequestsPerWindow = 1,
                maxConcurrentRequests = 1,
            ),
        )
        application {
            guideCastModule(
                "192.168.1.1",
                fixture.authenticator,
                assets(),
                fixture.streams,
                admissionController = admissionController,
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)
        val limited = client.get("/api/status")

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertFalse(limited.headers["Retry-After"].isNullOrBlank())
    }

    @Test
    fun `status and transcript polling share a bounded per remote read budget`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        val admissionController = LocalRequestAdmissionController(
            readApiLimit = LocalRequestLimit(
                maxRequestsPerWindow = 2,
                maxConcurrentRequests = 1,
            ),
        )
        application {
            guideCastModule(
                "192.168.1.1",
                fixture.authenticator,
                assets(),
                fixture.streams,
                fixture.transcriptProvider,
                admissionController,
            )
        }

        assertEquals(HttpStatusCode.OK, client.get("/api/status").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/transcripts").status)
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/transcripts").status)
    }

    @Test
    fun `certificate assets share the page rate budget and return every completed lease`() =
        testApplication {
            val tempDir = java.io.File.createTempFile("guidecast-ca-admission-rate", "")
            tempDir.delete()
            tempDir.mkdirs()
            try {
                val ca = app.guidecast.core.server.crypto.GuideCastCertificateAuthority
                    .getOrCreate(tempDir)
                val fixture = fixture(BroadcastAccess.Open)
                val admissionController = LocalRequestAdmissionController(
                    pageLimit = LocalRequestLimit(
                        maxRequestsPerWindow = 3,
                        maxConcurrentRequests = 1,
                    ),
                )
                application {
                    guideCastModule(
                        expectedHost = "192.168.1.1",
                        authenticator = fixture.authenticator,
                        assets = assets(),
                        streamSession = fixture.streams.currentSession(),
                        admissionController = admissionController,
                        ca = ca,
                    )
                }

                for (path in listOf("/ca.crt", "/ca.der", "/guidecast.mobileconfig")) {
                    assertEquals(HttpStatusCode.OK, client.get(path).status)
                    assertEquals(
                        "Completed certificate response leaked its PAGE_ASSET lease: $path",
                        0,
                        admissionController.activeRequestsForTest(LocalRequestKind.PAGE_ASSET),
                    )
                }

                val limited = client.get("/ca.crt")
                assertEquals(HttpStatusCode.TooManyRequests, limited.status)
                assertFalse(limited.headers["Retry-After"].isNullOrBlank())
                assertEquals(
                    0,
                    admissionController.activeRequestsForTest(LocalRequestKind.PAGE_ASSET),
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `certificate assets reject a concurrent page lease and recover after it closes`() =
        testApplication {
            val tempDir = java.io.File.createTempFile("guidecast-ca-admission-concurrent", "")
            tempDir.delete()
            tempDir.mkdirs()
            try {
                val ca = app.guidecast.core.server.crypto.GuideCastCertificateAuthority
                    .getOrCreate(tempDir)
                val fixture = fixture(BroadcastAccess.Open)
                val admissionController = LocalRequestAdmissionController(
                    pageLimit = LocalRequestLimit(
                        maxRequestsPerWindow = 20,
                        maxConcurrentRequests = 1,
                    ),
                )
                application {
                    guideCastModule(
                        expectedHost = "192.168.1.1",
                        authenticator = fixture.authenticator,
                        assets = assets(),
                        streamSession = fixture.streams.currentSession(),
                        admissionController = admissionController,
                        ca = ca,
                    )
                }

                // Ktor's test peer address is localhost, the same accepted-socket identity used
                // by acquireAdmission(). Holding this lease deterministically exercises the
                // concurrency branch without making certificate generation artificially block.
                val held = admissionController.tryAcquire(
                    "localhost",
                    LocalRequestKind.PAGE_ASSET,
                ) as LocalRequestAdmission.Allowed
                assertEquals(
                    1,
                    admissionController.activeRequestsForTest(LocalRequestKind.PAGE_ASSET),
                )

                for (path in listOf("/ca.crt", "/ca.der", "/guidecast.mobileconfig")) {
                    val limited = client.get(path)
                    assertEquals(HttpStatusCode.TooManyRequests, limited.status)
                    assertFalse(limited.headers["Retry-After"].isNullOrBlank())
                    assertEquals(
                        1,
                        admissionController.activeRequestsForTest(LocalRequestKind.PAGE_ASSET),
                    )
                }

                held.lease.close()
                assertEquals(
                    0,
                    admissionController.activeRequestsForTest(LocalRequestKind.PAGE_ASSET),
                )
                for (path in listOf("/ca.crt", "/ca.der", "/guidecast.mobileconfig")) {
                    assertEquals(HttpStatusCode.OK, client.get(path).status)
                    assertEquals(
                        0,
                        admissionController.activeRequestsForTest(LocalRequestKind.PAGE_ASSET),
                    )
                }
            } finally {
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `qr protected status requires the generated bearer token`() = testApplication {
        val fixture = fixture(BroadcastAccess.QrToken)
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/status").status)

        val token = requireNotNull(fixture.authenticator.tokenForQr())
        val authorized = client.get("/api/status") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
        assertFalse(authorized.headers[HttpHeaders.CacheControl].isNullOrBlank())
    }

    @Test
    fun `channel filtered status offers original without exposing other translations`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        fixture.streams.configure(
            listOf(
                AudioChannelDescriptor("en", "English", "en-US", 24_000),
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000),
                AudioChannelDescriptor("source", "원음", "ko", 24_000),
            ),
        )
        val englishListener = fixture.streams.subscribe("en")
        val japaneseListenerOne = fixture.streams.subscribe("ja")
        val japaneseListenerTwo = fixture.streams.subscribe("ja")
        val japaneseFrame = PcmAudioFrame(
            bytes = byteArrayOf(1, 0),
            capturedAtElapsedRealtimeNanos = 1L,
            utteranceSequence = 7L,
        )
        fixture.streams.publish("ja", japaneseFrame)
        japaneseListenerOne.recordWebSocketDelivery(japaneseFrame)
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }

        try {
            val response = client.get("/api/status?channel=en")
            val body = response.body<String>()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(body.contains("\"scope\":\"channel\""))
            assertTrue(body.contains("\"id\":\"en\""))
            assertTrue(body.contains("\"id\":\"source\""))
            assertTrue(body.contains("\"listenerCount\":1"))
            assertTrue(body.contains("\"droppedFrames\":0"))
            assertTrue(body.contains("\"webSocketDeliveredFrames\":0"))
            assertTrue(body.contains("\"listenerPath\":\"/en\""))
            assertFalse(body.contains("\"id\":\"ja\""))
            assertFalse(body.contains("\"listenerCount\":3"))
            assertFalse(body.contains("\"webSocketDeliveredFrames\":1"))
            assertEquals(HttpStatusCode.NotFound, client.get("/api/status?channel=de").status)
        } finally {
            englishListener.close()
            japaneseListenerOne.close()
            japaneseListenerTwo.close()
        }
    }

    @Test
    fun `open websocket sends config followed by pcm audio`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }
        val websocketClient = createClient { install(ClientWebSockets) }

        websocketClient.webSocket("/ws/source") {
            val config = incoming.receive() as Frame.Text
            val configText = config.readText()
            assertTrue(configText.contains("\"format\":\"pcm_s16le\""))
            assertTrue(configText.contains("\"generation\":"))

            val expected = byteArrayOf(1, 0, 2, 0)
            fixture.streams.publish(
                "source",
                PcmAudioFrame(expected, 1L, utteranceSequence = 33L),
            )
            val audio = incoming.receive() as Frame.Binary
            assertTrue(expected.contentEquals(audio.readBytes()))

            val status = client.get("/api/status?channel=source").body<String>()
            assertTrue(status.contains("\"webSocketDeliveredFrames\":1"))
            assertTrue(status.contains("\"lastWebSocketDeliveredSequence\":33"))
        }
    }

    @Test
    fun `websocket rejects a cross origin browser before subscribing`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application {
            guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams)
        }
        val websocketClient = createClient { install(ClientWebSockets) }

        websocketClient.webSocket(
            urlString = "/ws/source",
            request = { header(HttpHeaders.Origin, "http://attacker.example") },
        ) {
            val reason = withTimeout(2_000L) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
        assertEquals(0, fixture.streams.listenerCount.value)
    }

    @Test
    fun `websocket accepts a matching browser origin`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application {
            guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams)
        }
        val websocketClient = createClient { install(ClientWebSockets) }

        websocketClient.webSocket(
            urlString = "/ws/source",
            request = { header(HttpHeaders.Origin, "http://localhost") },
        ) {
            val config = incoming.receive() as Frame.Text
            assertTrue(config.readText().contains("\"format\":\"pcm_s16le\""))
        }
    }

    @Test
    fun `websocket handshake limit is enforced before token authentication`() = testApplication {
        val fixture = fixture(BroadcastAccess.QrToken)
        val admissionController = LocalRequestAdmissionController(
            webSocketLimit = LocalRequestLimit(
                maxRequestsPerWindow = 1,
                maxConcurrentRequests = 1,
            ),
        )
        application {
            guideCastModule(
                "192.168.1.1",
                fixture.authenticator,
                assets(),
                fixture.streams,
                admissionController = admissionController,
            )
        }
        val websocketClient = createClient { install(ClientWebSockets) }

        websocketClient.webSocket("/ws/source") {
            val reason = withTimeout(2_000L) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
        websocketClient.webSocket("/ws/source") {
            val reason = withTimeout(2_000L) { closeReason.await() }
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
        }
        assertEquals(0, fixture.streams.listenerCount.value)
    }

    @Test
    fun `authenticated websocket retains per remote lease until listener disconnects`() =
        testApplication {
            val fixture = fixture(BroadcastAccess.Open)
            val admissionController = LocalRequestAdmissionController(
                webSocketLimit = LocalRequestLimit(
                    maxRequestsPerWindow = 10,
                    maxConcurrentRequests = 1,
                ),
            )
            application {
                guideCastModule(
                    "192.168.1.1",
                    fixture.authenticator,
                    assets(),
                    fixture.streams,
                    admissionController = admissionController,
                )
            }
            val websocketClient = createClient { install(ClientWebSockets) }

            websocketClient.webSocket("/ws/source") {
                incoming.receive() as Frame.Text
                assertEquals(1, fixture.streams.listenerCount.value)
                websocketClient.webSocket("/ws/source") {
                    val reason = withTimeout(2_000L) { closeReason.await() }
                    assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
                }
                assertEquals(1, fixture.streams.listenerCount.value)
                close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
            }
            withTimeout(2_000L) {
                while (
                    fixture.streams.listenerCount.value != 0 ||
                    admissionController.activeRequestsForTest(
                        LocalRequestKind.WEBSOCKET_HANDSHAKE,
                    ) != 0
                ) {
                    delay(10L)
                }
            }

            websocketClient.webSocket("/ws/source") {
                incoming.receive() as Frame.Text
                assertEquals(1, fixture.streams.listenerCount.value)
                close(CloseReason(CloseReason.Codes.NORMAL, "test complete"))
            }
            withTimeout(2_000L) {
                while (
                    fixture.streams.listenerCount.value != 0 ||
                    admissionController.activeRequestsForTest(
                        LocalRequestKind.WEBSOCKET_HANDSHAKE,
                    ) != 0
                ) {
                    delay(10L)
                }
            }
            assertEquals(0, fixture.streams.listenerCount.value)
        }

    @Test
    fun `language websocket receives only its own channel pcm`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        fixture.streams.configure(
            listOf(
                AudioChannelDescriptor("en", "English", "en-US", 24_000),
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000),
            ),
        )
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }
        val websocketClient = createClient { install(ClientWebSockets) }

        websocketClient.webSocket("/ws/en") {
            incoming.receive() as Frame.Text
            val japaneseOnly = byteArrayOf(9, 0, 8, 0)
            val englishOnly = byteArrayOf(1, 0, 2, 0)
            fixture.streams.publish("ja", PcmAudioFrame(japaneseOnly, 1L))
            fixture.streams.publish("en", PcmAudioFrame(englishOnly, 2L))

            val audio = incoming.receive() as Frame.Binary
            assertTrue(englishOnly.contentEquals(audio.readBytes()))
        }
    }

    @Test
    fun `transcript endpoint requires the same authorization as status`() = testApplication {
        val fixture = fixture(BroadcastAccess.QrToken)
        application {
            guideCastModule(
                "192.168.1.1",
                fixture.authenticator,
                assets(),
                fixture.streams,
                fixture.transcriptProvider,
            )
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/transcripts").status)

        val token = fixture.authenticator.tokenForQr() ?: error("QR token is required for test")
        val authorized = client.get("/api/transcripts") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, authorized.status)
        val body = authorized.body<String>()
        assertTrue(body.contains("\"transcripts\":"))
        assertTrue(body.contains("\"firstAudioLatencyMillis\":{\"en\":580}"))
    }

    @Test
    fun `transcript endpoint reuses an etag and returns 304 until channel content changes`() =
        testApplication {
            val fixture = fixture(BroadcastAccess.Open)
            fixture.streams.configure(
                listOf(AudioChannelDescriptor("en", "English", "en-US", 24_000)),
            )
            var transcriptLines = listOf(
                GuideCastTranscriptLine(
                    sequence = 8L,
                    sourceText = "평화를 함께 걷습니다.",
                    isFinal = true,
                    capturedAtElapsedRealtimeNanos = 8_000L,
                    translations = mapOf("en" to "We walk together for peace."),
                    translationLatencyMillis = mapOf("en" to 90L),
                    firstAudioLatencyMillis = mapOf("en" to 490L),
                    synthesisLatencyMillis = mapOf("en" to 190L),
                ),
            )
            application {
                guideCastModule(
                    "192.168.1.1",
                    fixture.authenticator,
                    assets(),
                    fixture.streams,
                    transcriptProvider = { transcriptLines },
                )
            }

            val first = client.get("/api/transcripts?channel=en")
            val firstEtag = requireNotNull(first.headers[HttpHeaders.ETag])
            val unchanged = client.get("/api/transcripts?channel=en") {
                header(HttpHeaders.IfNoneMatch, "\"obsolete\", W/$firstEtag")
            }

            assertEquals(HttpStatusCode.OK, first.status)
            assertTrue(firstEtag.matches(Regex("\\\"gc-[0-9a-f]{64}\\\"")))
            assertEquals("no-store, max-age=0", first.headers[HttpHeaders.CacheControl])
            assertEquals(HttpStatusCode.NotModified, unchanged.status)
            assertEquals(firstEtag, unchanged.headers[HttpHeaders.ETag])
            assertTrue(unchanged.body<String>().isEmpty())

            transcriptLines = listOf(
                transcriptLines.single().copy(
                    translations = mapOf("en" to "We are walking together for peace."),
                ),
            )
            val changed = client.get("/api/transcripts?channel=en") {
                header(HttpHeaders.IfNoneMatch, firstEtag)
            }
            val changedEtag = requireNotNull(changed.headers[HttpHeaders.ETag])

            assertEquals(HttpStatusCode.OK, changed.status)
            assertFalse(firstEtag == changedEtag)
            assertTrue(changed.body<String>().contains("We are walking together for peace."))
        }

    @Test
    fun `channel transcript excludes other translations and escapes active html characters`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        fixture.streams.configure(
            listOf(
                AudioChannelDescriptor("en", "English", "en-US", 24_000),
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000),
            ),
        )
        val transcriptProvider = {
            listOf(
                GuideCastTranscriptLine(
                    sequence = 2L,
                    sourceText = "</script><img src=x onerror=alert(1)>",
                    isFinal = true,
                    capturedAtElapsedRealtimeNanos = 2_000L,
                    translations = mapOf(
                        "en" to "Safe English <b>text</b>",
                        "ja" to "다른 채널 비공개 문장",
                    ),
                    translationLatencyMillis = mapOf("en" to 100L, "ja" to 200L),
                    firstAudioLatencyMillis = mapOf("en" to 500L, "ja" to 700L),
                    synthesisLatencyMillis = mapOf("en" to 300L, "ja" to 400L),
                ),
            )
        }
        application {
            guideCastModule(
                "192.168.1.1",
                fixture.authenticator,
                assets(),
                fixture.streams,
                transcriptProvider,
            )
        }

        val response = client.get("/api/transcripts?channel=en")
        val body = response.body<String>()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("Safe English"))
        assertFalse(body.contains("다른 채널 비공개 문장"))
        assertFalse(body.contains("<script>"))
        assertFalse(body.contains("<b>"))
        assertTrue(body.contains("\\u003c/script\\u003e"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/transcripts?channel=de").status)
    }

    @Test
    fun `running server exposes one qr url per configured channel`() {
        val streams = AudioStreamRegistry()
        val streamSession = streams.configure(
            listOf(
                AudioChannelDescriptor("en", "English", "en-US", 24_000),
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000),
            ),
        )
        val server = RunningGuideCastServer(
            bindAddress = InetAddress.getByName("192.168.45.1") as Inet4Address,
            port = 8787,
            listenerUrl = "http://192.168.45.1:8787/#token=test",
            accessMode = BroadcastAccessMode.QR_TOKEN,
            streamSession = streamSession,
            channelUrlFactory = { "http://192.168.45.1:8787/$it#token=test" },
            stopServer = {},
        )

        assertEquals(
            mapOf(
                "en" to "http://192.168.45.1:8787/en#token=test",
                "ja" to "http://192.168.45.1:8787/ja#token=test",
            ),
            server.listenerUrlsByChannel,
        )
        assertEquals("http://192.168.45.1:8787/en#token=test", server.listenerUrlFor("en"))
        assertEquals(null, server.listenerUrlFor("de"))

        streams.configure(
            listOf(AudioChannelDescriptor("de", "Deutsch", "de-DE", 24_000)),
        )
        assertEquals(
            mapOf(
                "en" to "http://192.168.45.1:8787/en#token=test",
                "ja" to "http://192.168.45.1:8787/ja#token=test",
            ),
            server.listenerUrlsByChannel,
        )
        assertEquals(null, server.listenerUrlFor("de"))
        server.close()
    }

    @Test
    fun `failed server stop remains retryable so the socket owner can recover`() {
        val streams = AudioStreamRegistry()
        val session = streams.configure(
            listOf(AudioChannelDescriptor("en", "English", "en", 24_000)),
        )
        var stopAttempts = 0
        val server = RunningGuideCastServer(
            bindAddress = InetAddress.getByName("192.168.45.1") as Inet4Address,
            port = 8787,
            listenerUrl = "http://192.168.45.1:8787/en",
            accessMode = BroadcastAccessMode.OPEN,
            streamSession = session,
            channelUrlFactory = { "http://192.168.45.1:8787/$it" },
            stopServer = {
                stopAttempts += 1
                if (stopAttempts == 1) error("injected engine stop failure")
            },
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) { server.close() }
        server.close()

        assertEquals(2, stopAttempts)
        session.close()
    }

    @Test
    fun `server routes and status stay bound to one immutable stream generation`() = testApplication {
        val streams = AudioStreamRegistry()
        val englishSession = streams.configure(
            listOf(AudioChannelDescriptor("en", "English", "en-US", 24_000)),
        )
        val authenticator = BroadcastSessionAuthenticator.create(BroadcastAccess.Open)
        application {
            guideCastModule(
                "192.168.1.1",
                authenticator,
                assets(),
                englishSession,
            )
        }

        streams.configure(
            listOf(AudioChannelDescriptor("ja", "日本語", "ja-JP", 24_000)),
        )

        val english = client.get("/en")
        val japanese = client.get("/ja")
        val status = client.get("/api/status?channel=en").body<String>()

        assertEquals(HttpStatusCode.OK, english.status)
        assertEquals(HttpStatusCode.NotFound, japanese.status)
        assertTrue(status.contains("\"generation\":${englishSession.generation}"))
        assertTrue(status.contains("\"active\":false"))
        assertTrue(status.contains("\"id\":\"en\""))
        assertTrue(status.contains("\"listenerCount\":0"))
    }

    private fun fixture(access: BroadcastAccess): Fixture {
        val streams = AudioStreamRegistry()
        streams.configure(listOf(AudioChannelDescriptor("source", "원음", "ko-KR", 16_000)))
        val transcriptProvider = {
            listOf(
                GuideCastTranscriptLine(
                    sequence = 1L,
                    sourceText = "테스트",
                    isFinal = true,
                    capturedAtElapsedRealtimeNanos = 1_000L,
                    translations = mapOf("en" to "test"),
                    translationLatencyMillis = mapOf("en" to 120L),
                    firstAudioLatencyMillis = mapOf("en" to 580L),
                    synthesisLatencyMillis = mapOf("en" to 40L),
                ),
            )
        }
        return Fixture(
            authenticator = BroadcastSessionAuthenticator.create(access),
            streams = streams,
            transcriptProvider = transcriptProvider,
        )
    }

    @Test
    fun `mic routes permit microphone self while restricting other capabilities`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application {
            guideCastModule(
                "192.168.1.1",
                fixture.authenticator,
                assets(),
                fixture.streams,
                speakerAssets = speakerAssets(),
            )
        }

        val htmlResponse = client.get("/mic")
        val jsResponse = client.get("/speaker.js")
        val cssResponse = client.get("/speaker.css")

        for (response in listOf(htmlResponse, jsResponse, cssResponse)) {
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertTrue(response.headers["Content-Security-Policy"].orEmpty().contains("frame-ancestors 'none'"))
            assertEquals("camera=(), microphone=(self), geolocation=()", response.headers["Permissions-Policy"])
        }
    }

    @Test
    fun `listener routes explicitly deny microphone access`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        fixture.streams.configure(
            listOf(AudioChannelDescriptor("en", "English", "en-US", 24_000)),
        )
        application { guideCastModule("192.168.1.1", fixture.authenticator, assets(), fixture.streams) }

        val rootResponse = client.get("/")
        val channelResponse = client.get("/en")

        assertEquals("camera=(), microphone=(), geolocation=()", rootResponse.headers["Permissions-Policy"])
        assertEquals("camera=(), microphone=(), geolocation=()", channelResponse.headers["Permissions-Policy"])
    }

    @Test
    fun `speaker websocket endpoint enforces host, origin, and speaker capability token`() = testApplication {
        val fixture = fixture(BroadcastAccess.QrToken)
        val leaseManager = SpeakerLeaseManager()
        val receivedFrames = mutableListOf<ByteArray>()
        application {
            guideCastModule(
                expectedHost = "192.168.1.1",
                authenticator = fixture.authenticator,
                assets = assets(),
                streamSession = fixture.streams.currentSession(),
                speakerAssets = speakerAssets(),
                speakerLeaseManager = leaseManager,
                onSpeakerFrameReceived = { bytes, _ -> receivedFrames.add(bytes) },
            )
        }
        val wsClient = createClient { install(ClientWebSockets) }

        // 1. Invalid host rejected
        wsClient.webSocket(
            urlString = "/ws/speaker-input",
            request = {
                header(HttpHeaders.Host, "attacker.example")
                header(HttpHeaders.Origin, "http://192.168.1.1")
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }

        // 2. Missing origin rejected
        val speakerToken = fixture.authenticator.tokenForSpeaker()
        wsClient.webSocket(
            urlString = "/ws/speaker-input?token=$speakerToken",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }

        // 3. Cross-origin rejected
        wsClient.webSocket(
            urlString = "/ws/speaker-input?token=$speakerToken",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
                header(HttpHeaders.Origin, "http://attacker.example")
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }

        // 4. Listener QR token rejected on speaker endpoint
        val listenerToken = requireNotNull(fixture.authenticator.tokenForQr())
        wsClient.webSocket(
            urlString = "/ws/speaker-input?token=$listenerToken",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
                header(HttpHeaders.Origin, "http://192.168.1.1")
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }

        // 5. Valid speaker capability token accepted and frames received with ACK
        wsClient.webSocket(
            urlString = "/ws/speaker-input?token=$speakerToken",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
                header(HttpHeaders.Origin, "http://192.168.1.1")
            },
        ) {
            val initialState = incoming.receive() as Frame.Text
            assertTrue(initialState.readText().contains("\"ready\":true"))
            // Send sequence-framed packet (4-byte sequence 0 + 640 bytes PCM)
            val packet = ByteArray(4 + 640)
            packet[0] = 0; packet[1] = 0; packet[2] = 0; packet[3] = 0
            send(Frame.Binary(true, packet))

            // Check ACK received for sequence 0
            val ackFrame = incoming.receive()
            assertTrue(ackFrame is Frame.Text)
            assertTrue((ackFrame as Frame.Text).readText().contains("\"seq\":0"))
            assertEquals(1, receivedFrames.size)
            assertEquals(640, receivedFrames[0].size)

            // 6. Concurrent second speaker is rejected with TRY_AGAIN_LATER while lease is active
            wsClient.webSocket(
                urlString = "/ws/speaker-input?token=$speakerToken",
                request = {
                    header(HttpHeaders.Host, "192.168.1.1")
                    header(HttpHeaders.Origin, "http://192.168.1.1")
                },
            ) {
                val secondReason = closeReason.await()
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, secondReason?.code)
            }
        }
    }

    @Test
    fun `open access mode strictly requires speaker capability token`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        application {
            guideCastModule(
                expectedHost = "192.168.1.1",
                authenticator = fixture.authenticator,
                assets = assets(),
                streamSession = fixture.streams.currentSession(),
                speakerAssets = speakerAssets(),
            )
        }
        val wsClient = createClient { install(ClientWebSockets) }

        // OPEN mode without token rejected
        wsClient.webSocket(
            urlString = "/ws/speaker-input",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
                header(HttpHeaders.Origin, "http://192.168.1.1")
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }

        // OPEN mode with valid speakerToken accepted
        val speakerToken = fixture.authenticator.tokenForSpeaker()
        wsClient.webSocket(
            urlString = "/ws/speaker-input?speakerToken=$speakerToken",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
                header(HttpHeaders.Origin, "http://192.168.1.1")
            },
        ) {
            val initialState = incoming.receive() as Frame.Text
            assertTrue(initialState.readText().contains("\"ready\":true"))
            val packet = ByteArray(4 + 320)
            packet[0] = 0; packet[1] = 0; packet[2] = 0; packet[3] = 0
            send(Frame.Binary(true, packet))
            val ackFrame = incoming.receive()
            assertTrue(ackFrame is Frame.Text)
        }
    }

    @Test
    fun `speaker frames are rejected until transmitter web input is subscribed`() = testApplication {
        val fixture = fixture(BroadcastAccess.Open)
        var inputReady = false
        val receivedFrames = mutableListOf<ByteArray>()
        application {
            guideCastModule(
                expectedHost = "192.168.1.1",
                authenticator = fixture.authenticator,
                assets = assets(),
                streamSession = fixture.streams.currentSession(),
                speakerAssets = speakerAssets(),
                isSpeakerInputReady = { inputReady },
                onSpeakerFrameReceived = { bytes, _ -> receivedFrames.add(bytes) },
            )
        }
        val wsClient = createClient { install(ClientWebSockets) }
        val speakerToken = fixture.authenticator.tokenForSpeaker()

        wsClient.webSocket(
            urlString = "/ws/speaker-input?speakerToken=$speakerToken",
            request = {
                header(HttpHeaders.Host, "192.168.1.1")
                header(HttpHeaders.Origin, "http://192.168.1.1")
            },
        ) {
            val initialState = incoming.receive() as Frame.Text
            assertTrue(initialState.readText().contains("\"ready\":false"))

            send(Frame.Binary(true, ByteArray(4 + 320)))
            val rejectedAck = incoming.receive() as Frame.Text
            assertTrue(rejectedAck.readText().contains("\"accepted\":false"))
            assertTrue(receivedFrames.isEmpty())

            inputReady = true
            send(Frame.Text("probe"))
            val readyState = incoming.receive() as Frame.Text
            assertTrue(readyState.readText().contains("\"ready\":true"))

            val acceptedPacket = ByteArray(4 + 320).apply { this[3] = 25 }
            send(Frame.Binary(true, acceptedPacket))
            val acceptedAck = incoming.receive() as Frame.Text
            assertTrue(acceptedAck.readText().contains("\"accepted\":true"))
            assertEquals(1, receivedFrames.size)
        }
    }

    private fun assets() = ListenerAssets(
        html = "<!doctype html><title>GuideCast</title>".encodeToByteArray(),
        javascript = "'use strict';".encodeToByteArray(),
        css = "body{}".encodeToByteArray(),
    )

    private fun speakerAssets() = SpeakerAssets(
        html = "<!doctype html><title>Speaker</title>".encodeToByteArray(),
        javascript = "'use strict';".encodeToByteArray(),
        css = "body{}".encodeToByteArray(),
    )

    @Test
    fun `mic is the token-free HTTP onboarding entry and legacy pages redirect to it`() =
        testApplication {
            val tempDir = java.io.File.createTempFile("guidecast-mic-entry-test", "")
            tempDir.delete()
            tempDir.mkdirs()
            try {
                val ca = app.guidecast.core.server.crypto.GuideCastCertificateAuthority.getOrCreate(tempDir)
                val authenticator = BroadcastSessionAuthenticator.create(
                    access = BroadcastAccess.QrToken,
                    speakerAccess = SpeakerAccess.Pin.from(charArrayOf('2', '4', '6', '8')),
                )
                val streams = AudioStreamRegistry().apply {
                    configure(listOf(AudioChannelDescriptor("source", "원음", "ko-KR", 16_000)))
                }
                val renderedAssets = SpeakerAssets(
                    html = (
                        "<!doctype html><body data-https-port=\"__GUIDECAST_HTTPS_PORT__\" " +
                            "data-ca-fingerprint=\"__GUIDECAST_CA_FINGERPRINT__\">" +
                            "<a href=\"/ca.crt\">인증서 등록</a><title>Mic</title>"
                        ).encodeToByteArray(),
                    javascript = "'use strict';".encodeToByteArray(),
                    css = "body{}".encodeToByteArray(),
                )
                application {
                    guideCastModule(
                        expectedHost = "192.168.1.1",
                        authenticator = authenticator,
                        assets = assets(),
                        streamSession = streams.currentSession(),
                        speakerAssets = renderedAssets,
                        ca = ca,
                        httpsPort = 8443,
                    )
                }

                val micResponse = client.get("/mic") {
                    header(HttpHeaders.Host, "192.168.1.1:8787")
                }
                val micBody = micResponse.body<String>()
                assertEquals(HttpStatusCode.OK, micResponse.status)
                assertTrue(micBody.contains("data-https-port=\"8443\""))
                assertTrue(micBody.contains("data-ca-fingerprint=\"${ca.caSha256Fingerprint()}\""))
                assertTrue(micBody.contains("/ca.crt"))
                assertFalse(micBody.contains("__GUIDECAST_HTTPS_PORT__"))
                assertFalse(micBody.contains("__GUIDECAST_CA_FINGERPRINT__"))
                assertFalse(micBody.contains(authenticator.tokenForSpeaker()))
                assertEquals(
                    "camera=(), microphone=(self), geolocation=()",
                    micResponse.headers["Permissions-Policy"],
                )

                val noRedirectClient = createClient { followRedirects = false }
                for (legacyPath in listOf("/speaker", "/setup-trust")) {
                    val redirect = noRedirectClient.get(legacyPath) {
                        header(HttpHeaders.Host, "192.168.1.1:8787")
                    }
                    assertEquals(HttpStatusCode.PermanentRedirect, redirect.status)
                    assertEquals("/mic", redirect.headers[HttpHeaders.Location])
                    assertFalse(redirect.body<String>().contains(authenticator.tokenForSpeaker()))
                }

                val pemResponse = client.get("/ca.crt") {
                    header(HttpHeaders.Host, "192.168.1.1:8787")
                }
                assertEquals(HttpStatusCode.OK, pemResponse.status)
                assertEquals(
                    "attachment; filename=\"guidecast-local-root-ca.crt\"",
                    pemResponse.headers["Content-Disposition"],
                )
                assertTrue(pemResponse.body<String>().startsWith("-----BEGIN CERTIFICATE-----"))

                val derResponse = client.get("/ca.der") {
                    header(HttpHeaders.Host, "192.168.1.1:8787")
                }
                assertEquals(HttpStatusCode.OK, derResponse.status)
                val downloadedDer = derResponse.body<ByteArray>()
                assertTrue(ca.caCertificate.encoded.contentEquals(downloadedDer))
                val downloadedFingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(downloadedDer)
                    .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
                assertEquals(ca.caSha256Fingerprint(), downloadedFingerprint)
                assertTrue(
                    "The /mic fingerprint must describe the exact /ca.der response bytes",
                    micBody.contains("data-ca-fingerprint=\"$downloadedFingerprint\""),
                )

                val configResponse = client.get("/guidecast.mobileconfig") {
                    header(HttpHeaders.Host, "192.168.1.1:8787")
                }
                assertEquals(HttpStatusCode.OK, configResponse.status)
                assertTrue(configResponse.body<String>().contains("com.apple.security.root"))
            } finally {
                tempDir.deleteRecursively()
            }
        }

    @Test
    fun `plain mic transport cannot spoof TLS with host or forwarded headers`() = testApplication {
        val authenticator = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.Open,
            speakerAccess = SpeakerAccess.Pin.from(charArrayOf('2', '4', '6', '8')),
        )
        val streams = AudioStreamRegistry().apply {
            configure(listOf(AudioChannelDescriptor("source", "원음", "ko-KR", 16_000)))
        }
        application {
            guideCastModule(
                expectedHost = "192.168.1.1",
                authenticator = authenticator,
                assets = assets(),
                streamSession = streams.currentSession(),
                speakerAssets = speakerAssets(),
                httpsPort = 8443,
            )
        }

        val insecureSession = client.get("/api/mic/session") {
            header(HttpHeaders.Host, "192.168.1.1:8787")
            header(HttpHeaders.Origin, "http://192.168.1.1:8787")
        }
        assertEquals(426, insecureSession.status.value)
        assertFalse(insecureSession.body<String>().contains(authenticator.tokenForSpeaker()))

        val spoofedSession = client.get("/api/mic/session") {
            header(HttpHeaders.Host, "192.168.1.1:8443")
            header(HttpHeaders.Origin, "https://192.168.1.1:8443")
            header("X-Forwarded-Proto", "https")
        }
        assertEquals(426, spoofedSession.status.value)
        assertFalse(spoofedSession.body<String>().contains(authenticator.tokenForSpeaker()))

        val spoofedJoin = client.post("/api/mic/join") {
            header(HttpHeaders.Host, "192.168.1.1:8443")
            header(HttpHeaders.Origin, "https://192.168.1.1:8443")
            header("X-Forwarded-Proto", "https")
            setBody("2468")
        }
        assertEquals(426, spoofedJoin.status.value)
        assertEquals(null, spoofedJoin.headers[HttpHeaders.SetCookie])

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket(
            urlString = "/ws/speaker-input",
            request = {
                header(HttpHeaders.Host, "192.168.1.1:8443")
                header(HttpHeaders.Origin, "https://192.168.1.1:8443")
                header("X-Forwarded-Proto", "https")
                header(
                    HttpHeaders.Cookie,
                    "guidecast_speaker_session=${authenticator.tokenForSpeaker()}",
                )
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun `missing TLS proxy never reopens plaintext speaker query capability`() = testApplication {
        val authenticator = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.Open,
            speakerAccess = SpeakerAccess.Open,
        )
        val streams = AudioStreamRegistry().apply {
            configure(listOf(AudioChannelDescriptor("source", "원음", "ko-KR", 16_000)))
        }
        application {
            guideCastModule(
                expectedHost = "192.168.1.1",
                authenticator = authenticator,
                assets = assets(),
                streamSession = streams.currentSession(),
                speakerAssets = speakerAssets(),
                httpsPort = null,
                transportSecurity = GuideCastTransportSecurity.PLAIN_HTTP,
                requireTlsSpeakerTransport = true,
            )
        }

        val wsClient = createClient { install(ClientWebSockets) }
        wsClient.webSocket(
            urlString = "/ws/speaker-input?token=${authenticator.tokenForSpeaker()}",
            request = {
                header(HttpHeaders.Host, "192.168.1.1:8787")
                header(HttpHeaders.Origin, "http://192.168.1.1:8787")
            },
        ) {
            val reason = closeReason.await()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun `mic session and PIN join require same-origin HTTPS and never expose a token before join`() =
        testApplication {
            val authenticator = BroadcastSessionAuthenticator.create(
                access = BroadcastAccess.Pin.from(charArrayOf('1', '3', '5', '7')),
                speakerAccess = SpeakerAccess.Pin.from(charArrayOf('2', '4', '6', '8')),
            )
            val streams = AudioStreamRegistry().apply {
                configure(listOf(AudioChannelDescriptor("source", "원음", "ko-KR", 16_000)))
            }
            val tlsBackendAuthenticator = GuideCastTlsBackendAuthenticator.create()
            application {
                guideCastModule(
                    expectedHost = "192.168.1.1",
                    authenticator = authenticator,
                    assets = assets(),
                    streamSession = streams.currentSession(),
                    speakerAssets = speakerAssets(),
                    httpsPort = 8443,
                    transportSecurity = GuideCastTransportSecurity.TLS_TERMINATED,
                    tlsBackendAuthenticator = tlsBackendAuthenticator,
                )
            }

            val secureSession = client.get("/api/mic/session") {
                secureMicHeaders(tlsBackendAuthenticator)
            }
            assertEquals(HttpStatusCode.OK, secureSession.status)
            assertEquals(
                "{\"requiresPin\":true,\"authenticated\":false}",
                secureSession.body<String>(),
            )
            assertFalse(secureSession.body<String>().contains(authenticator.tokenForSpeaker()))

            val crossOriginJoin = client.post("/api/mic/join") {
                secureMicHeaders(tlsBackendAuthenticator)
                header(HttpHeaders.Origin, "https://attacker.example")
                setBody("2468")
            }
            assertEquals(HttpStatusCode.Forbidden, crossOriginJoin.status)

            val listenerPinJoin = client.post("/api/mic/join") {
                secureMicHeaders(tlsBackendAuthenticator)
                setBody("1357")
            }
            assertEquals(HttpStatusCode.Unauthorized, listenerPinJoin.status)

            val speakerPinJoin = client.post("/api/mic/join") {
                secureMicHeaders(tlsBackendAuthenticator)
                setBody("2468")
            }
            assertEquals(HttpStatusCode.OK, speakerPinJoin.status)
            assertEquals("{\"authenticated\":true}", speakerPinJoin.body<String>())
            assertFalse(speakerPinJoin.body<String>().contains(authenticator.tokenForSpeaker()))
            val speakerCookie = requireNotNull(speakerPinJoin.headers[HttpHeaders.SetCookie])
            assertTrue(speakerCookie.startsWith("guidecast_speaker_session="))
            assertTrue(speakerCookie.contains("; Secure;"))
            assertTrue(speakerCookie.contains("; HttpOnly;"))
            assertTrue(speakerCookie.contains("SameSite=Strict"))
            val speakerToken = speakerCookie.substringAfter('=').substringBefore(';')
            assertEquals(authenticator.tokenForSpeaker(), speakerToken)
            assertTrue(authenticator.authorizeSpeaker(speakerToken))
            assertFalse(authenticator.authorize(speakerToken))

            val authenticatedSession = client.get("/api/mic/session") {
                secureMicHeaders(tlsBackendAuthenticator)
                header(HttpHeaders.Cookie, "guidecast_speaker_session=$speakerToken")
            }
            assertEquals(HttpStatusCode.OK, authenticatedSession.status)
            assertEquals(
                "{\"requiresPin\":true,\"authenticated\":true}",
                authenticatedSession.body<String>(),
            )

            repeat(5) {
                val invalid = client.post("/api/mic/join") {
                    secureMicHeaders(tlsBackendAuthenticator)
                    setBody("0000")
                }
                assertEquals(HttpStatusCode.Unauthorized, invalid.status)
            }
            val limited = client.post("/api/mic/join") {
                secureMicHeaders(tlsBackendAuthenticator)
                setBody("2468")
            }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        }

    @Test
    fun `open mic session exchanges an empty PIN for a speaker-only capability`() = testApplication {
        val authenticator = BroadcastSessionAuthenticator.create(
            access = BroadcastAccess.QrToken,
            speakerAccess = SpeakerAccess.Open,
        )
        val streams = AudioStreamRegistry().apply {
            configure(listOf(AudioChannelDescriptor("source", "원음", "ko-KR", 16_000)))
        }
        val tlsBackendAuthenticator = GuideCastTlsBackendAuthenticator.create()
        application {
            guideCastModule(
                expectedHost = "192.168.1.1",
                authenticator = authenticator,
                assets = assets(),
                streamSession = streams.currentSession(),
                speakerAssets = speakerAssets(),
                httpsPort = 8443,
                transportSecurity = GuideCastTransportSecurity.TLS_TERMINATED,
                tlsBackendAuthenticator = tlsBackendAuthenticator,
            )
        }

        val session = client.get("/api/mic/session") {
            secureMicHeaders(tlsBackendAuthenticator)
        }
        assertEquals(HttpStatusCode.OK, session.status)
        assertEquals(
            "{\"requiresPin\":false,\"authenticated\":false}",
            session.body<String>(),
        )

        val join = client.post("/api/mic/join") {
            secureMicHeaders(tlsBackendAuthenticator)
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, join.status)
        assertEquals("{\"authenticated\":true}", join.body<String>())
        assertFalse(join.body<String>().contains(authenticator.tokenForSpeaker()))
        val cookie = requireNotNull(join.headers[HttpHeaders.SetCookie])
        assertTrue(cookie.contains("; Secure;"))
        assertTrue(cookie.contains("; HttpOnly;"))
        assertTrue(cookie.contains("SameSite=Strict"))
        val token = cookie.substringAfter('=').substringBefore(';')
        assertEquals(authenticator.tokenForSpeaker(), token)
        assertTrue(authenticator.authorizeSpeaker(token))
        assertFalse(authenticator.authorize(token))
        assertFalse(authenticator.authorizeSpeaker(requireNotNull(authenticator.tokenForQr())))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.secureMicHeaders(
        backendAuthenticator: GuideCastTlsBackendAuthenticator,
        peerAddress: String = "192.168.45.20",
    ) {
        val attestation = backendAuthenticator.attest(peerAddress)
        header(HttpHeaders.Host, "192.168.1.1:8443")
        header(HttpHeaders.Origin, "https://192.168.1.1:8443")
        header(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
        header(GuideCastTlsBackendAuthenticator.VERSION_HEADER, GuideCastTlsBackendAuthenticator.PROTOCOL_VERSION)
        header(GuideCastTlsBackendAuthenticator.PEER_HEADER, attestation.peerAddress)
        header(GuideCastTlsBackendAuthenticator.NONCE_HEADER, attestation.nonce)
        header(GuideCastTlsBackendAuthenticator.MAC_HEADER, attestation.mac)
    }

    private data class Fixture(
        val authenticator: BroadcastSessionAuthenticator,
        val streams: AudioStreamRegistry,
        val transcriptProvider: () -> List<GuideCastTranscriptLine>,
    )
}
