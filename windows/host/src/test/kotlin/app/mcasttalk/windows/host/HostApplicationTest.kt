package app.mcasttalk.windows.host

import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostApplicationTest {
    private lateinit var fixture: TestAccountFixture
    @Test
    fun reportsLivenessAndReadiness() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }

                val live = client.get("/health/live")
                assertEquals(200, live.status.value)
                assertTrue(live.bodyAsText().contains("mcasttalk-windows-host"))

                val ready = client.get("/health/ready")
                assertEquals(200, ready.status.value)
                assertTrue(ready.bodyAsText().contains("test-instance"))
            }
        }

    @Test
    fun servesOfflineRoomUiWithRestrictiveBrowserHeaders() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }

                val page = client.get("/")
                assertEquals(200, page.status.value)
                val body = page.bodyAsText()
                assertTrue(body.contains("MCastTalk"))
                assertTrue(body.contains("input-language"))
                assertTrue(body.contains("listen-language"))
                assertTrue(
                    page.headers["Content-Security-Policy"]
                        ?.contains("object-src 'none'") == true
                )
                assertTrue(page.headers["Content-Security-Policy"]?.contains("ws://[::1]") == false)
                assertEquals("nosniff", page.headers["X-Content-Type-Options"])
                assertTrue(
                    page.headers["Permissions-Policy"]
                        ?.contains("microphone=(self)") == true
                )
                assertEquals("no-store, max-age=0", page.headers[HttpHeaders.CacheControl])
            }
        }

    @Test
    fun declaresMediaAndPublicNetworkGatesHonestly() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }

                val capabilities = client.get("/api/v1/capabilities")
                assertEquals(200, capabilities.status.value)
                val body = capabilities.bodyAsText()
                assertTrue(body.contains("\"roomChat\":true"))
                assertTrue(body.contains("\"privateChat\":true"))
                assertTrue(body.contains("\"chatTranslation\":false"))
                assertTrue(body.contains("\"mediaTransport\":\"webrtc-lan-mesh\""))
                assertTrue(body.contains("\"lanHttps\":false"))
                assertTrue(body.contains("\"publicNetworkReady\":false"))
                assertTrue(body.contains("\"offlineInferenceRequired\":true"))
            }
        }

    @Test
    fun joinsRoomWithIndependentLanguagePreferences() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }
                val webSocketClient = createClient {
                    install(WebSockets)
                }

                webSocketClient.webSocket("/ws/v1/rooms/room-101", request = {
                    headers.append(HttpHeaders.Cookie, fixture.cookie())
                    headers.append(HttpHeaders.Host, "localhost")
                    headers.append(HttpHeaders.Origin, "http://localhost")
                }) {
                    send(
                        Frame.Text(
                            """
                            {
                              "type": "JOIN_ROOM",
                              "participantId": "user-a",
                              "displayName": "Alice",
                              "inputLanguage": "ko",
                              "publishLanguage": "en",
                              "listenLanguage": "ko",
                              "displayLanguage": "ko",
                              "audioMode": "translated"
                            }
                            """.trimIndent()
                        )
                    )
                    val response = incoming.receive() as Frame.Text
                    val text = response.readText()
                    assertTrue(text.contains("ROOM_JOINED"))
                    assertTrue(text.contains("\"publishLanguage\":\"en\""))
                    assertTrue(text.contains("\"listenLanguage\":\"ko\""))
                }
            }
        }

    @Test
    fun rejectedDuplicateJoinCannotRemoveTheExistingParticipant() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }
                val webSocketClient = createClient {
                    install(WebSockets)
                }
                val first = webSocketClient.webSocketSession("/ws/v1/rooms/room-duplicate", block = {
                    headers.append(HttpHeaders.Cookie, fixture.cookie())
                    headers.append(HttpHeaders.Host, "localhost")
                    headers.append(HttpHeaders.Origin, "http://localhost")
                })
                try {
                    first.send(Frame.Text(joinMessage("same-user", "First")))
                    assertTrue(first.receiveText().contains("ROOM_JOINED"))
                    assertTrue(first.receiveText().contains("PARTICIPANT_JOINED"))

                    val duplicate =
                        webSocketClient.webSocketSession("/ws/v1/rooms/room-duplicate", block = {
                            headers.append(HttpHeaders.Cookie, fixture.cookie())
                            headers.append(HttpHeaders.Host, "localhost")
                            headers.append(HttpHeaders.Origin, "http://localhost")
                        })
                    try {
                        duplicate.send(Frame.Text(joinMessage("same-user", "Duplicate")))
                        assertTrue(duplicate.receiveText().contains("INVALID_MESSAGE"))
                        // Wait for the rejected handler's finally block before checking ownership.
                        withTimeout(5_000) { duplicate.incoming.receiveCatching() }
                    } finally {
                        duplicate.close()
                    }

                    val third = webSocketClient.webSocketSession("/ws/v1/rooms/room-duplicate", block = {
                        headers.append(HttpHeaders.Cookie, fixture.cookie())
                        headers.append(HttpHeaders.Host, "localhost")
                        headers.append(HttpHeaders.Origin, "http://localhost")
                    })
                    try {
                        third.send(Frame.Text(joinMessage("same-user", "Third")))
                        assertTrue(
                            "The original socket must retain ownership of the participant id",
                            third.receiveText().contains("INVALID_MESSAGE"),
                        )
                    } finally {
                        third.close()
                    }
                    first.send(
                        Frame.Text(
                            """{"type":"UPDATE_PREFERENCES","inputLanguage":"ko","listenLanguage":"en"}"""
                        )
                    )
                    val update = withTimeout(5_000) { first.receiveText() }
                    assertTrue(update.contains("PARTICIPANT_UPDATED"))
                    assertTrue(update.contains("\"listenLanguage\":\"en\""))
                } finally {
                    first.close()
                }
            }
        }

    @Test
    fun authenticatedListenerUsesGatedMediaAndCannotPublish() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }
                val webSocketClient = createClient {
                    install(WebSockets)
                }

                val speaker = webSocketClient.webSocketSession("/ws/v1/rooms/room-media", block = {
                    headers.append(HttpHeaders.Cookie, fixture.cookie())
                    headers.append(HttpHeaders.Host, "localhost")
                    headers.append(HttpHeaders.Origin, "http://localhost")
                })

                val listener = webSocketClient.webSocketSession("/ws/v1/rooms/room-media/listen", block = {
                    headers.append(HttpHeaders.Cookie, fixture.cookie("Listener"))
                    headers.append(HttpHeaders.Host, "localhost")
                    headers.append(HttpHeaders.Origin, "http://localhost")
                })

                try {
                    speaker.send(Frame.Text(joinMessage("speaker-1", "HostSpeaker")))
                    val speakerJoined = speaker.receiveText()
                    assertTrue(speakerJoined.contains("ROOM_JOINED"))
                    val speakerSelfEvent = speaker.receiveText()
                    assertTrue(speakerSelfEvent.contains("PARTICIPANT_JOINED"))
                    assertTrue(speakerSelfEvent.contains("speaker-1"))

                    listener.send(Frame.Text(
                        """
                        {
                          "type": "JOIN_ROOM",
                          "participantId": "listener-guest",
                          "displayName": "모바일청취자",
                          "inputLanguage": "ko",
                          "listenLanguage": "en",
                          "role": "listener"
                        }
                        """.trimIndent()
                    ))
                    val listenerJoined = listener.receiveText()
                    assertTrue(listenerJoined.contains("ROOM_JOINED"))
                    assertTrue(listenerJoined.contains("\"role\":\"listener\""))

                    val listenerSelfEvent = listener.receiveText()
                    assertTrue(listenerSelfEvent.contains("PARTICIPANT_JOINED"))
                    assertTrue(listenerSelfEvent.contains("listener-guest"))

                    val speakerNotice = speaker.receiveText()
                    assertTrue(speakerNotice.contains("PARTICIPANT_JOINED"))
                    assertTrue(speakerNotice.contains("listener-guest"))

                    // Speaker broadcasts binary audio frame
                    val testPcm = byteArrayOf(10, 20, 30, 40, 50)
                    speaker.send(Frame.Binary(true, testPcm))

                    assertTrue(withTimeout(5_000) { speaker.receiveText() }.contains("MEDIA_NOT_READY"))

                    // Speaker broadcasts subtitle chunk
                    speaker.send(Frame.Text(
                        """
                        {
                          "type": "SUBTITLE_CHUNK",
                          "originalText": "안녕하세요",
                          "translatedText": "Hello everyone",
                          "sourceLanguage": "ko",
                          "targetLanguage": "en",
                          "isFinal": true
                        }
                        """.trimIndent()
                    ))
                    assertTrue(withTimeout(5_000) { speaker.receiveText() }.contains("INFERENCE_NOT_READY"))

                    // Listener sending binary audio is rejected
                    listener.send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                    val rejectNotice = withTimeout(5_000) { listener.receiveText() }
                    assertTrue(rejectNotice.contains("NOT_PERMITTED"))
                } finally {
                    speaker.close()
                    listener.close()
                }
            }
        }

    @Test
    fun servesDirectListenerWebRoute() =
        withDataRoot { root ->
            testApplication {
                application {
                    mcastTalkModule(root, accountServices = fixture.services)
                }

                val page = client.get("/listen/room-abc")
                assertEquals(200, page.status.value)
                val body = page.bodyAsText()
                assertTrue(body.contains("MCastTalk"))
            }
        }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.receiveText(): String =
        (incoming.receive() as Frame.Text).readText()

    private fun joinMessage(participantId: String, displayName: String): String =
        """
        {
          "type": "JOIN_ROOM",
          "participantId": "$participantId",
          "displayName": "$displayName",
          "inputLanguage": "ko",
          "listenLanguage": "ko"
        }
        """.trimIndent()

    private fun withDataRoot(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("mcasttalk-host-test")
        try {
            val config = root.resolve("config").createDirectories()
            config.resolve("data-root.json").writeText(
                """{"schemaVersion":1,"instanceId":"test-instance"}"""
            )
            fixture = TestAccountFixture(root)
            fixture.use { block(root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

}
