package app.mcasttalk.windows.host

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApplicationTest {
    private lateinit var fixture: TestAccountFixture
    @Test
    fun privateChatReachesOnlySenderAndRecipientAndRoomChatStaysInItsRoom() = withDataRoot { root ->
        testApplication {
            val directory = RoomDirectory()
            application { mcastTalkModule(root, directory, accountServices = fixture.services) }
            val socketClient = createClient { install(WebSockets) }
            val alice = socketClient.join("meeting", "alice", "Alice")
            val bob = socketClient.join("meeting", "bob", "Bob")
            val carol = socketClient.join("meeting", "carol", "Carol")
            // The same participant id in a different room must never receive this room's DM.
            val outsider = socketClient.join("other-room", "bob", "Other Bob")
            val sessions = listOf(alice, bob, carol, outsider)
            try {
                sessions.forEach { it.drainToPong() }
                val bobPresence = directory.snapshot("meeting")!!.participants.first { it.id == "bob" }.presenceId
                alice.send(Frame.Text(encodeJson(mapOf(
                    "type" to "CHAT_SEND", "text" to "private hello", "recipientId" to "bob",
                    "recipientPresenceId" to bobPresence, "senderId" to "carol",
                    "senderDisplayName" to "Forged", "roomId" to "other-room",
                ))))
                val senderCopy = alice.receiveText()
                assertEquals(senderCopy, bob.receiveText())
                val privateEvent = parseFlatJsonObject(senderCopy)
                assertEquals("CHAT_MESSAGE", privateEvent.requiredString("type"))
                assertEquals("private", privateEvent.requiredString("scope"))
                assertEquals("meeting", privateEvent.requiredString("roomId"))
                assertEquals("alice", privateEvent.requiredString("senderId"))
                assertEquals("Alice", privateEvent.requiredString("senderDisplayName"))
                assertEquals("bob", privateEvent.requiredString("recipientId"))
                assertEquals(bobPresence, privateEvent.requiredString("recipientPresenceId"))
                assertEquals(directory.snapshot("meeting")!!.participants.first { it.id == "alice" }.presenceId,
                    privateEvent.requiredString("senderPresenceId"))
                assertEquals("Bob", privateEvent.requiredString("recipientDisplayName"))
                assertEquals("private hello", privateEvent.requiredString("originalText"))
                assertEquals("ko", privateEvent.requiredString("sourceLanguage"))
                assertEquals("unavailable", privateEvent.requiredString("translationStatus"))
                UUID.fromString(privateEvent.requiredString("messageId"))
                Instant.parse(privateEvent.requiredString("sentAt"))
                carol.assertOnlyPong()
                outsider.assertOnlyPong()

                bob.send(Frame.Text("""{"type":"CHAT_SEND","text":"hello everyone"}"""))
                val roomCopy = bob.receiveText()
                assertEquals(roomCopy, alice.receiveText())
                assertEquals(roomCopy, carol.receiveText())
                val roomEvent = parseFlatJsonObject(roomCopy)
                assertEquals("room", roomEvent.requiredString("scope"))
                assertEquals(null, roomEvent.optionalString("recipientId"))
                assertEquals(null, roomEvent.optionalString("recipientDisplayName"))
                assertEquals(null, roomEvent.optionalString("recipientPresenceId"))
                outsider.assertOnlyPong()
            } finally {
                sessions.forEach { it.close() }
            }
        }
    }

    @Test
    fun invalidChatIsRejectedWithoutDisconnectingOrDeliveringToAnotherRoom() = withDataRoot { root ->
        testApplication {
            val directory = RoomDirectory()
            application { mcastTalkModule(root, directory, accountServices = fixture.services) }
            val socketClient = createClient { install(WebSockets) }
            val alice = socketClient.join("meeting", "alice", "Alice")
            val bob = socketClient.join("meeting", "bob", "Bob")
            val outsider = socketClient.join("other-room", "outsider", "Other")
            val sessions = listOf(alice, bob, outsider)
            try {
                sessions.forEach { it.drainToPong() }
                val alicePresence = directory.snapshot("meeting")!!.participants.first { it.id == "alice" }.presenceId
                val outsiderPresence = directory.snapshot("other-room")!!.participants.single().presenceId
                val invalidMessages = listOf(
                    mapOf("type" to "CHAT_SEND", "text" to "   "),
                    mapOf("type" to "CHAT_SEND", "text" to "x".repeat(MAX_CHAT_TEXT_CHARS + 1)),
                    mapOf("type" to "CHAT_SEND", "text" to "self", "recipientId" to "alice", "recipientPresenceId" to alicePresence),
                    mapOf("type" to "CHAT_SEND", "text" to "missing", "recipientId" to "missing", "recipientPresenceId" to UUID.randomUUID().toString()),
                    mapOf("type" to "CHAT_SEND", "text" to "outside", "recipientId" to "outsider", "recipientPresenceId" to outsiderPresence),
                    mapOf("type" to "CHAT_SEND", "text" to "bad\u0000control"),
                    mapOf("type" to "CHAT_SEND", "text" to "missing presence", "recipientId" to "bob"),
                    mapOf("type" to "CHAT_SEND", "text" to "invalid presence", "recipientId" to "bob", "recipientPresenceId" to "not-a-uuid"),
                    mapOf("type" to "CHAT_SEND", "text" to "stale presence", "recipientId" to "bob", "recipientPresenceId" to UUID.randomUUID().toString()),
                    mapOf("type" to "CHAT_SEND", "text" to "not private", "recipientPresenceId" to UUID.randomUUID().toString()),
                )
                invalidMessages.forEach { message ->
                    alice.send(Frame.Text(encodeJson(message)))
                    val error = parseFlatJsonObject(alice.receiveText())
                    assertEquals("ERROR", error.requiredString("type"))
                    assertEquals("INVALID_CHAT", error.requiredString("code"))
                    alice.assertOnlyPong()
                }
                bob.assertOnlyPong()
                outsider.assertOnlyPong()

                // The exact size boundary remains usable after validation errors.
                val boundaryText = "가".repeat(MAX_CHAT_TEXT_CHARS)
                alice.send(Frame.Text(encodeJson(mapOf("type" to "CHAT_SEND", "text" to boundaryText))))
                val accepted = alice.receiveText()
                assertEquals(accepted, bob.receiveText())
                assertEquals(boundaryText, parseFlatJsonObject(accepted).requiredString("originalText"))
                outsider.assertOnlyPong()
            } finally {
                sessions.forEach { it.close() }
            }
        }
    }

    @Test
    fun privateChatNeverRetargetsADepartedIdToANewPresence() = withDataRoot { root ->
        testApplication {
            val directory = RoomDirectory()
            application { mcastTalkModule(root, directory, accountServices = fixture.services) }
            val socketClient = createClient { install(WebSockets) }
            val alice = socketClient.join("meeting", "alice", "Alice")
            val bob = socketClient.join("meeting", "bob", "Bob")
            try {
                alice.drainToPong()
                bob.drainToPong()
                val oldPresence = directory.snapshot("meeting")!!.participants.first { it.id == "bob" }.presenceId
                bob.close()
                assertTrue(alice.receiveText().contains("PARTICIPANT_LEFT"))
                val replacement = socketClient.join("meeting", "bob", "Mallory")
                try {
                    alice.drainToPong()
                    replacement.drainToPong()
                    val newPresence = directory.snapshot("meeting")!!.participants.first { it.id == "bob" }.presenceId
                    assertTrue(oldPresence != newPresence)
                    alice.send(Frame.Text(encodeJson(mapOf(
                        "type" to "CHAT_SEND", "text" to "secret for old Bob", "recipientId" to "bob",
                        "recipientPresenceId" to oldPresence,
                    ))))
                    assertEquals("INVALID_CHAT", parseFlatJsonObject(alice.receiveText()).requiredString("code"))
                    replacement.assertOnlyPong()
                    alice.send(Frame.Text(encodeJson(mapOf(
                        "type" to "CHAT_SEND", "text" to "explicitly selected new participant", "recipientId" to "bob",
                        "recipientPresenceId" to newPresence,
                    ))))
                    val accepted = alice.receiveText()
                    assertEquals(accepted, replacement.receiveText())
                    val event = parseFlatJsonObject(accepted)
                    assertEquals("Mallory", event.requiredString("recipientDisplayName"))
                    assertEquals(newPresence, event.requiredString("recipientPresenceId"))
                } finally { replacement.close() }
            } finally { alice.close(); bob.close() }
        }
    }

    private suspend fun HttpClient.join(roomId: String, participantId: String, displayName: String): DefaultClientWebSocketSession {
        val session = webSocketSession("/ws/v1/rooms/$roomId", block = {
            headers.append(HttpHeaders.Cookie, fixture.cookie(displayName))
            headers.append(HttpHeaders.Host, "localhost")
            headers.append(HttpHeaders.Origin, "http://localhost")
        })
        session.send(Frame.Text(encodeJson(mapOf(
            "type" to "JOIN_ROOM",
            "participantId" to participantId,
            "displayName" to displayName,
            "inputLanguage" to "ko",
            "listenLanguage" to "ko",
        ))))
        assertTrue(session.receiveText().contains("ROOM_JOINED"))
        assertTrue(session.receiveText().contains("PARTICIPANT_JOINED"))
        return session
    }

    private suspend fun DefaultClientWebSocketSession.receiveText(): String =
        withTimeout(5_000) { (incoming.receive() as Frame.Text).readText() }

    private suspend fun DefaultClientWebSocketSession.drainToPong() {
        send(Frame.Text("""{"type":"PING"}"""))
        while (parseFlatJsonObjectOrType(receiveText()) != "PONG") { /* Drain roster events. */ }
    }

    private suspend fun DefaultClientWebSocketSession.assertOnlyPong() {
        send(Frame.Text("""{"type":"PING"}"""))
        assertEquals("PONG", parseFlatJsonObject(receiveText()).requiredString("type"))
    }

    // Roster events contain nested participant objects; the production flat parser is for commands.
    private fun parseFlatJsonObjectOrType(text: String): String =
        if (text.contains("\"type\":\"PONG\"")) "PONG" else "roster-event"

    private fun withDataRoot(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("mcasttalk-chat-test")
        try {
            root.resolve("config").createDirectories().resolve("data-root.json")
                .writeText("""{"schemaVersion":1,"instanceId":"test-chat"}""")
            fixture = TestAccountFixture(root)
            fixture.use { block(root) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
