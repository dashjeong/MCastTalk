package app.guidecast.core.server

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.RecordedPcmSegment
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastLiveAudioGateTest {
    private val channel = AudioChannelDescriptor("source", "Original", "ko", 16_000)
    private val assets = ListenerAssets("<html></html>".toByteArray(), byteArrayOf(), byteArrayOf())

    @Test
    fun sameAuthenticatedSocketResumesWithoutPausedPcmAndRetainsPublicReplay() = testApplication {
        val directory = Files.createTempDirectory("guidecast-live-gate").toFile()
        try {
            val file = directory.resolve("audio.pcm").apply {
                writeBytes(byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0))
            }
            val enabled = AtomicBoolean(true)
            val registry = AudioStreamRegistry()
            val stream = registry.configure(listOf(channel))
            val auth = BroadcastSessionAuthenticator.create(BroadcastAccess.QrToken)
            val token = requireNotNull(auth.tokenForQr())
            val publicLine = line("public")
            val lines = AtomicReference(listOf(publicLine))
            val recording = AtomicReference(RecordedBroadcast("logical", 1, null, "RECORDING", 0, null,
                listOf(RecordedPcmSegment("logical", 1, channel, 0, 4, file))))
            val captions = AtomicReference("[{\"part\":1,\"sequence\":1,\"sourceText\":\"public\"}]")
            application {
                guideCastModule("192.168.1.1", auth, assets, stream,
                    transcriptProvider = { lines.get() }, replayProvider = { recording.get() },
                    replayCaptionsProvider = { _, _ -> captions.get() },
                    isLiveAudioBroadcastEnabled = enabled::get)
            }
            suspend fun read(path: String) = client.get(path) {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            val publicTranscript = read("/api/transcripts").body<String>()
            val publicReplay = read("/api/replay?channel=source").body<String>()
            val publicCaptions = read("/api/replay-captions").body<String>()
            val firstDelivered = CompletableDeferred<Unit>()
            val delivery = stream.observeWebSocketDelivery { _, frame ->
                if (frame.utteranceSequence == 1L) firstDelivered.complete(Unit)
            }
            val local = stream.subscribeLocalMonitor("source")
            try {
                val websocketClient = createClient { install(ClientWebSockets) }
                websocketClient.webSocket("/ws/source?token=$token") {
                    assertTrue(withTimeout(2_000) { incoming.receive() } is Frame.Text)
                    stream.publish("source", PcmAudioFrame(byteArrayOf(1, 0), 1, utteranceSequence = 1))
                    assertArrayEquals(byteArrayOf(1, 0), withTimeout(2_000) { (incoming.receive() as Frame.Binary).readBytes() })
                    withTimeout(2_000) { firstDelivered.await() }
                    withTimeout(2_000) { local.frames.receive() }
                    enabled.set(false)
                    assertEquals(publicTranscript, read("/api/transcripts").body<String>())
                    lines.set(listOf(line("private paused words")))
                    captions.set("[{\"part\":1,\"sequence\":2,\"sourceText\":\"private paused words\"}]")
                    recording.set(recording.get().copy(segments = listOf(recording.get().segments.single().copy(committedBytes = 8))))
                    stream.publish("source", PcmAudioFrame(byteArrayOf(9, 0), 2, utteranceSequence = 2))
                    // Pausing the server must not clear the local operator queue.
                    assertArrayEquals(byteArrayOf(9, 0), withTimeout(2_000) { local.frames.receive().bytes })
                    assertEquals(null, withTimeoutOrNull(80) { incoming.receive() })
                    assertEquals(publicTranscript, read("/api/transcripts").body<String>())
                    assertEquals(publicReplay, read("/api/replay?channel=source").body<String>())
                    assertEquals(publicCaptions, read("/api/replay-captions").body<String>())
                    assertEquals("[]", read("/api/replay-captions?afterPart=1&afterSequence=1").body<String>())
                    assertArrayEquals(byteArrayOf(1, 0, 2, 0), read("/api/replay/1/0?channel=source&offset=0&count=4").body<ByteArray>())
                    assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable,
                        read("/api/replay/1/0?channel=source&offset=0&count=8").status)
                    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/replay?channel=source").status)
                    val pausedStatus = read("/api/status?channel=source").body<String>()
                    assertTrue(pausedStatus.contains("\"webSocketDeliveredFrames\":1"))
                    assertTrue(pausedStatus.contains("\"lastWebSocketDeliveredSequence\":1"))
                    enabled.set(true)
                    assertTrue(read("/api/transcripts").body<String>().contains("private paused words"))
                    // Transition draining may discard the first queued frame; subsequent live
                    // frames use the same marker so no paused marker can satisfy this assertion.
                    val publisher = launch {
                        repeat(20) {
                            stream.publish("source", PcmAudioFrame(byteArrayOf(7, 0), 3, utteranceSequence = 3))
                            delay(15)
                        }
                    }
                    try {
                        assertArrayEquals(byteArrayOf(7, 0), withTimeout(2_000) { (incoming.receive() as Frame.Binary).readBytes() })
                    } finally { publisher.cancelAndJoin() }
                    assertEquals(HttpStatusCode.OK, read("/api/replay?channel=source").status)
                }
            } finally {
                delivery.close()
                local.close()
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun pauseCancelsSuspendedSendAndDrainsBacklogWithoutCountingDelivery() = runBlocking {
        val enabled = AtomicBoolean(true)
        val gate = LiveAudioBroadcastGate(enabled::get)
        val frames = Channel<PcmAudioFrame>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val delivered = Channel<Long>(Channel.UNLIMITED)
        val sender = launch {
            transmitGatedLiveAudio(frames, gate, { frame ->
                if (frame.utteranceSequence == 1L) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
            }, { frame -> delivered.trySend(requireNotNull(frame.utteranceSequence)) })
        }
        try {
            frames.send(PcmAudioFrame(byteArrayOf(1, 0), 1, utteranceSequence = 1))
            withTimeout(2_000) { entered.await() }
            enabled.set(false)
            gate.observe()
            frames.send(PcmAudioFrame(byteArrayOf(2, 0), 2, utteranceSequence = 2))
            withTimeout(2_000) { cancelled.await() }
            delay(30)
            assertTrue(delivered.tryReceive().isFailure)
            enabled.set(true)
            gate.observe()
            delay(30)
            val publisher = launch {
                repeat(10) {
                    frames.send(PcmAudioFrame(byteArrayOf(3, 0), 3, utteranceSequence = 3))
                    delay(15)
                }
            }
            try { assertEquals(3L, withTimeout(2_000) { delivered.receive() }) }
            finally { publisher.cancelAndJoin() }
        } finally {
            sender.cancelAndJoin()
            frames.close()
            delivered.close()
        }
    }

    @Test
    fun initiallyPausedServerDoesNotPublishContinuingPrivateSnapshots() = testApplication {
        val registry = AudioStreamRegistry()
        val stream = registry.configure(listOf(channel))
        val auth = BroadcastSessionAuthenticator.create(BroadcastAccess.Open)
        val transcriptCalls = java.util.concurrent.atomic.AtomicInteger()
        val replayCalls = java.util.concurrent.atomic.AtomicInteger()
        val captionCalls = java.util.concurrent.atomic.AtomicInteger()
        application {
            guideCastModule("192.168.1.1", auth, assets, stream,
                transcriptProvider = { transcriptCalls.incrementAndGet(); listOf(line("private")) },
                replayProvider = { replayCalls.incrementAndGet(); null },
                replayCaptionsProvider = { _, _ -> captionCalls.incrementAndGet(); "[\"private\"]" },
                isLiveAudioBroadcastEnabled = { false })
        }
        assertFalse(client.get("/api/transcripts").body<String>().contains("private"))
        assertEquals(HttpStatusCode.NotFound, client.get("/api/replay?channel=source").status)
        assertEquals("[]", client.get("/api/replay-captions").body<String>())
        assertEquals(0, transcriptCalls.get())
        assertEquals(0, replayCalls.get())
        assertEquals(0, captionCalls.get())
    }

    @Test
    fun pausedCommittedCaptionHandleSupportsArbitraryPagesBeyondOneHundredRows() = testApplication {
        val enabled = AtomicBoolean(true)
        val committedRows = java.util.concurrent.atomic.AtomicInteger(241)
        val providerCalls = java.util.concurrent.atomic.AtomicInteger()
        val legacyCalls = java.util.concurrent.atomic.AtomicInteger()
        val registry = AudioStreamRegistry()
        val stream = registry.configure(listOf(channel))
        val auth = BroadcastSessionAuthenticator.create(BroadcastAccess.QrToken)
        val token = requireNotNull(auth.tokenForQr())
        application {
            guideCastModule("192.168.1.1", auth, assets, stream,
                isLiveAudioBroadcastEnabled = enabled::get,
                replayCaptionsProvider = { _, _ -> legacyCalls.incrementAndGet(); "[]" },
                replayCaptionSnapshotProvider = {
                    providerCalls.incrementAndGet()
                    val frozenLength = committedRows.get()
                    ReplayCaptionSnapshot(frozenLength.toLong()) { part, sequence ->
                        val first = if (part == null || part < 1L) 1 else requireNotNull(sequence).toInt() + 1
                        (first..minOf(first + 99, frozenLength)).joinToString(prefix = "[", postfix = "]")
                    }
                })
        }
        suspend fun page(query: String = "") = client.get("/api/replay-captions$query") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.body<String>()
        fun expected(first: Int, last: Int) = (first..last).joinToString(prefix = "[", postfix = "]")
        assertEquals(expected(1, 100), page())
        enabled.set(false)
        assertEquals(expected(1, 100), page())
        val callsAtPause = providerCalls.get()
        committedRows.set(441)
        // These cursors were never requested while live. They still page through the frozen
        // prefix, including the third page, without consulting the newer provider revision.
        assertEquals(expected(101, 200), page("?afterPart=1&afterSequence=100"))
        assertEquals(expected(201, 241), page("?afterPart=1&afterSequence=200"))
        assertEquals("[]", page("?afterPart=1&afterSequence=241"))
        assertEquals(callsAtPause, providerCalls.get())
        assertEquals(0, legacyCalls.get())
        enabled.set(true)
        assertEquals(expected(242, 341), page("?afterPart=1&afterSequence=241"))
        assertTrue(providerCalls.get() > callsAtPause)
        assertEquals(0, legacyCalls.get())
    }

    private fun line(text: String) = GuideCastTranscriptLine(
        1, text, true, 1, emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    )
}
