package app.guidecast.core.stream

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamRegistryTest {
    private val korean = AudioChannelDescriptor("ko", "한국어", "ko-KR", 16_000)
    private val english = AudioChannelDescriptor("en", "English", "en-US", 16_000)

    @Test
    fun `existing five channel sessions remain supported in requested order`() {
        val registry = AudioStreamRegistry()
        registry.configure(
            listOf(
                korean,
                english,
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 16_000),
                AudioChannelDescriptor("nl", "Nederlands", "nl-NL", 16_000),
                AudioChannelDescriptor("zh", "中文", "zh-CN", 16_000),
            ),
        )

        assertEquals(listOf("ko", "en", "ja", "nl", "zh"), registry.channels.value.map { it.id })
        assertEquals(
            linkedMapOf("ko" to 0, "en" to 0, "ja" to 0, "nl" to 0, "zh" to 0),
            registry.listenerCounts.value,
        )
    }

    @Test
    fun `rejects eight translations plus original`() {
        val registry = AudioStreamRegistry()

        assertThrows(IllegalArgumentException::class.java) {
            registry.configure(
                listOf("source", "en", "ja", "zh", "zh-tw", "vi", "nl", "es", "ar")
                    .map { id ->
                        AudioChannelDescriptor(
                            id,
                            id,
                            if (id == "source") "ko" else id,
                            16_000,
                        )
                    },
            )
        }
    }

    @Test
    fun `seven translations and original are independently routable`() {
        val registry = AudioStreamRegistry()
        val descriptors = listOf("source", "en", "ja", "zh", "zh-tw", "vi", "nl", "es")
            .map { id ->
                AudioChannelDescriptor(id, id, if (id == "source") "ko" else id, 16_000)
            }

        val session = registry.configure(descriptors)

        assertEquals(descriptors.map { it.id }, session.channels.map { it.id })
        val original = session.subscribe("source")
        val seventhTranslation = session.subscribe("es")
        try {
            val frame = PcmAudioFrame(byteArrayOf(1, 0), 1L)
            session.tryPublish("source", frame)
            assertEquals(frame, original.frames.tryReceive().getOrNull())
            assertTrue(seventhTranslation.frames.tryReceive().isFailure)
        } finally {
            original.close()
            seventhTranslation.close()
        }
    }

    @Test fun `five translations and original are independently routed`() {
        val registry = AudioStreamRegistry()
        val descriptors = listOf("source", "en", "ja", "zh", "nl", "fr").map {
            AudioChannelDescriptor(it, it, if (it == "source") "ko" else it, 16_000)
        }
        val session = registry.configure(descriptors)
        assertEquals(descriptors.map { it.id }, session.channels.map { it.id })
        val original = session.subscribe("source")
        val english = session.subscribe("en")
        try {
            val frame = PcmAudioFrame(byteArrayOf(1, 0), 1L)
            session.tryPublish("source", frame)
            assertEquals(frame, original.frames.tryReceive().getOrNull())
            assertTrue(english.frames.tryReceive().isFailure)
        } finally {
            original.close()
            english.close()
        }
    }

    @Test
    fun `enforces listener limit across all channels`() {
        val registry = AudioStreamRegistry(maxListeners = 2)
        registry.configure(listOf(korean, english))
        val first = registry.subscribe("ko")
        val second = registry.subscribe("en")

        assertEquals(2, registry.listenerCount.value)
        assertEquals(mapOf("ko" to 1, "en" to 1), registry.listenerCounts.value)
        assertThrows(ListenerLimitExceededException::class.java) { registry.subscribe("ko") }

        first.close()
        assertEquals(mapOf("ko" to 0, "en" to 1), registry.listenerCounts.value)
        second.close()
        assertEquals(0, registry.listenerCount.value)
        assertEquals(mapOf("ko" to 0, "en" to 0), registry.listenerCounts.value)
    }

    @Test
    fun `production limit admits fifty listeners and rejects fifty first`() {
        val registry = AudioStreamRegistry(maxListeners = 50)
        registry.configure(listOf(korean))
        val listeners = List(50) { registry.subscribe("ko") }

        assertEquals(50, registry.listenerCount.value)
        assertThrows(ListenerLimitExceededException::class.java) { registry.subscribe("ko") }

        listeners.forEach { it.close() }
        assertEquals(0, registry.listenerCount.value)
    }

    @Test
    fun `fifty concurrent listeners use one immutable publish snapshot until topology changes`() {
        val registry = AudioStreamRegistry(maxListeners = 50)
        val session = registry.configure(listOf(english))
        val executor = Executors.newFixedThreadPool(12)
        val listeners = mutableListOf<AudioListenerSubscription>()
        try {
            val subscribeStart = CountDownLatch(1)
            val subscribeFutures = List(50) {
                executor.submit<AudioListenerSubscription> {
                    subscribeStart.await()
                    session.subscribe("en")
                }
            }
            subscribeStart.countDown()
            listeners += subscribeFutures.map { it.get(3L, TimeUnit.SECONDS) }

            val admittedTopology = session.state.publishTargetsByChannel.getValue("en")
            assertEquals(50, admittedTopology.remote.size)
            assertEquals(50, session.observabilitySnapshot().totalListeners)
            @Suppress("UNCHECKED_CAST")
            assertThrows(UnsupportedOperationException::class.java) {
                (admittedTopology.remote as MutableList<ListenerMailbox>).clear()
            }

            repeat(200) { sequence ->
                session.publish(
                    "en",
                    PcmAudioFrame(
                        bytes = byteArrayOf(sequence.toByte(), 0),
                        capturedAtElapsedRealtimeNanos = sequence.toLong(),
                    ),
                )
                assertSame(
                    "frame publication must not rebuild the listener topology",
                    admittedTopology,
                    session.state.publishTargetsByChannel.getValue("en"),
                )
            }

            val closeStart = CountDownLatch(1)
            val closeFutures = listeners.map { listener ->
                executor.submit<Unit> {
                    closeStart.await()
                    listener.close()
                }
            }
            val concurrentPublisher = executor.submit<Unit> {
                closeStart.await()
                repeat(200) { sequence ->
                    session.tryPublish(
                        "en",
                        PcmAudioFrame(byteArrayOf(1, 0), 1_000L + sequence),
                    )
                }
            }
            closeStart.countDown()
            closeFutures.forEach { it.get(3L, TimeUnit.SECONDS) }
            concurrentPublisher.get(3L, TimeUnit.SECONDS)

            val emptyTopology = session.state.publishTargetsByChannel.getValue("en")
            assertNotSame(admittedTopology, emptyTopology)
            assertTrue(emptyTopology.remote.isEmpty())
            assertEquals(0, session.observabilitySnapshot().totalListeners)
            assertEquals(
                0,
                session.publish("en", PcmAudioFrame(byteArrayOf(1, 0), 2_000L)).targetListeners,
            )
        } finally {
            listeners.forEach(AudioListenerSubscription::close)
            executor.shutdownNow()
            session.close()
        }
    }

    @Test
    fun `manual close is idempotent`() {
        val registry = AudioStreamRegistry()
        registry.configure(listOf(korean))
        val subscription = registry.subscribe("ko")

        subscription.close()
        subscription.close()

        assertEquals(0, registry.listenerCount.value)
    }

    @Test
    fun `reconfiguring closes every prior listener including a reused channel id`() {
        val registry = AudioStreamRegistry()
        val original = registry.configure(listOf(korean, english))
        val koreanListener = registry.subscribe("ko")
        val englishListener = registry.subscribe("en")

        val replacement = registry.configure(listOf(english))

        assertEquals(0, registry.listenerCount.value)
        assertEquals(mapOf("en" to 0), registry.listenerCounts.value)
        assertTrue(koreanListener.frames.tryReceive().isClosed)
        assertTrue(englishListener.frames.tryReceive().isClosed)
        assertFalse(original.isActive())
        assertTrue(replacement.isActive())
        assertEquals(0, original.observabilitySnapshot().totalListeners)
        assertEquals(replacement.generation, registry.observability.value.generation)

        koreanListener.close()
        englishListener.close()
        assertEquals(0, registry.listenerCount.value)
        assertEquals(mapOf("en" to 0), registry.listenerCounts.value)
    }

    @Test
    fun `capture boundary drops frame when server channel was replaced`() {
        val registry = AudioStreamRegistry()
        registry.configure(listOf(korean))
        val frame = PcmAudioFrame(byteArrayOf(1, 0), 1L)

        assertFalse(registry.tryPublish("source", frame))
        assertTrue(registry.tryPublish("ko", frame))
    }

    @Test
    fun `stale session cannot publish pcm into a replacement with the same channel id`() {
        val registry = AudioStreamRegistry()
        val original = registry.configure(listOf(english))
        val oldListener = original.subscribe("en")
        val replacement = registry.configure(listOf(english))
        val newListener = replacement.subscribe("en")
        val stalePcm = PcmAudioFrame(byteArrayOf(1, 0), 1L)
        val currentPcm = PcmAudioFrame(byteArrayOf(2, 0), 2L)

        assertEquals(StreamPublishStatus.STALE_SESSION, original.tryPublish("en", stalePcm).status)
        assertEquals(StreamPublishStatus.PUBLISHED, replacement.tryPublish("en", currentPcm).status)
        assertTrue(oldListener.frames.tryReceive().isClosed)
        assertTrue(currentPcm.bytes.contentEquals(newListener.frames.tryReceive().getOrThrow().bytes))

        oldListener.close()
        newListener.close()
    }

    @Test
    fun `closing exact session cancels listeners and rejects later pcm`() {
        val registry = AudioStreamRegistry()
        val session = registry.configure(listOf(english))
        val listener = session.subscribe("en")

        session.close()
        session.close()

        assertFalse(session.isActive())
        assertTrue(listener.frames.tryReceive().isClosed)
        assertEquals(
            StreamPublishStatus.STALE_SESSION,
            session.tryPublish("en", PcmAudioFrame(byteArrayOf(1, 0), 1L)).status,
        )
        assertFalse(registry.observability.value.isActive)
        assertEquals(0, registry.observability.value.totalListeners)
        assertEquals(emptyList<AudioChannelDescriptor>(), registry.channels.value)
        listener.close()
    }

    @Test
    fun `closing stale session cannot stop replacement`() {
        val registry = AudioStreamRegistry()
        val stale = registry.configure(listOf(korean))
        val replacement = registry.configure(listOf(english))

        stale.close()

        assertFalse(stale.isActive())
        assertTrue(replacement.isActive())
        assertEquals(replacement.generation, registry.observability.value.generation)
        assertEquals(listOf(english), registry.channels.value)
    }

    @Test
    fun `five channel admission reserves the first listener slot for every language`() {
        val registry = AudioStreamRegistry(maxListeners = 10)
        val session = registry.configure(
            listOf(
                english,
                AudioChannelDescriptor("ja", "日本語", "ja-JP", 16_000),
                AudioChannelDescriptor("zh", "中文", "zh-CN", 16_000),
                AudioChannelDescriptor("nl", "Nederlands", "nl-NL", 16_000),
                AudioChannelDescriptor("es", "Español", "es-ES", 16_000),
            ),
        )
        val subscriptions = mutableListOf<AudioListenerSubscription>()
        repeat(6) { subscriptions += session.subscribe("en") }

        assertThrows(ListenerLimitExceededException::class.java) { session.subscribe("en") }
        listOf("ja", "zh", "nl", "es").forEach { subscriptions += session.subscribe(it) }
        assertEquals(
            mapOf("en" to 6, "ja" to 1, "zh" to 1, "nl" to 1, "es" to 1),
            registry.observability.value.listenersByChannel,
        )
        assertEquals(10, registry.observability.value.totalListeners)

        subscriptions.forEach { it.close() }
    }

    @Test
    fun `observability atomically reports counts and bounded queue drops`() {
        val registry = AudioStreamRegistry(listenerBufferFrames = 1)
        val session = registry.configure(listOf(english))
        val listener = session.subscribe("en")

        val afterSubscribe = registry.observability.value
        assertEquals(1, afterSubscribe.totalListeners)
        assertEquals(mapOf("en" to 1), afterSubscribe.listenersByChannel)
        assertEquals(0L, afterSubscribe.droppedFrames)

        session.publish("en", PcmAudioFrame(byteArrayOf(1, 0), 1L))
        val overflow = session.publish("en", PcmAudioFrame(byteArrayOf(2, 0), 2L))
        val afterOverflow = session.observabilitySnapshot()

        assertEquals(1, overflow.droppedFrames)
        assertEquals(1L, afterOverflow.droppedFrames)
        assertEquals(mapOf("en" to 1L), afterOverflow.droppedFramesByChannel)
        assertEquals(1, afterOverflow.totalListeners)
        assertEquals(mapOf("en" to 1), afterOverflow.listenersByChannel)

        listener.close()
        val afterClose = registry.observability.value
        assertEquals(0, afterClose.totalListeners)
        assertEquals(mapOf("en" to 0), afterClose.listenersByChannel)
        assertEquals(1L, afterClose.droppedFrames)
    }

    @Test
    fun `publishing with zero listeners is not reported as websocket delivery`() {
        val registry = AudioStreamRegistry()
        val session = registry.configure(listOf(english))

        val result = session.publish(
            "en",
            PcmAudioFrame(byteArrayOf(1, 0), 1L, utteranceSequence = 11L),
        )
        val snapshot = session.observabilitySnapshot()

        assertEquals(StreamPublishStatus.PUBLISHED, result.status)
        assertEquals(0, result.targetListeners)
        assertEquals(0, result.enqueuedFrames)
        assertEquals(0L, snapshot.webSocketDeliveredFrames)
        assertEquals(mapOf("en" to 0L), snapshot.webSocketDeliveredFramesByChannel)
        assertEquals(mapOf("en" to null), snapshot.lastWebSocketDeliveredSequenceByChannel)
        session.close()
    }

    @Test
    fun `healthy listener records delivery only after websocket send succeeds`() {
        val registry = AudioStreamRegistry()
        val session = registry.configure(listOf(english))
        val listener = session.subscribe("en")
        val frame = PcmAudioFrame(
            byteArrayOf(1, 0),
            capturedAtElapsedRealtimeNanos = 1L,
            utteranceSequence = 12L,
        )

        val publish = session.publish("en", frame)
        assertEquals(1, publish.enqueuedFrames)
        assertEquals(0L, session.observabilitySnapshot().webSocketDeliveredFrames)

        val dequeued = listener.frames.tryReceive().getOrThrow()
        listener.recordWebSocketDelivery(dequeued)
        val delivered = session.observabilitySnapshot()

        assertEquals(1L, delivered.webSocketDeliveredFrames)
        assertEquals(mapOf("en" to 1L), delivered.webSocketDeliveredFramesByChannel)
        assertEquals(12L, delivered.lastWebSocketDeliveredSequenceByChannel["en"])
        listener.close()
        session.close()
    }

    @Test
    fun `saturated listener drop is distinct from later websocket delivery`() {
        val registry = AudioStreamRegistry(listenerBufferFrames = 1)
        val session = registry.configure(listOf(english))
        val listener = session.subscribe("en")
        session.publish(
            "en",
            PcmAudioFrame(byteArrayOf(1, 0), 1L, utteranceSequence = 20L),
        )

        val replacement = session.publish(
            "en",
            PcmAudioFrame(byteArrayOf(2, 0), 2L, utteranceSequence = 21L),
        )
        val beforeSend = session.observabilitySnapshot()

        assertEquals(1, replacement.enqueuedFrames)
        assertEquals(1, replacement.droppedFrames)
        assertEquals(1L, beforeSend.droppedFrames)
        assertEquals(0L, beforeSend.webSocketDeliveredFrames)

        val latest = listener.frames.tryReceive().getOrThrow()
        listener.recordWebSocketDelivery(latest)
        val afterSend = session.observabilitySnapshot()
        assertEquals(1L, afterSend.droppedFrames)
        assertEquals(1L, afterSend.webSocketDeliveredFrames)
        assertEquals(21L, afterSend.lastWebSocketDeliveredSequenceByChannel["en"])
        listener.close()
        session.close()
    }

    @Test
    fun `frame telemetry is coalesced until an exact snapshot refresh`() = runBlocking {
        val registry = AudioStreamRegistry(listenerBufferFrames = 1)
        val session = registry.configure(listOf(english))
        val listener = session.subscribe("en")
        var emissionCount = 0
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            registry.observability.collect { emissionCount += 1 }
        }
        assertEquals(1, emissionCount)

        repeat(64) { index ->
            val firstSequence = index.toLong() * 2L
            session.publish(
                "en",
                PcmAudioFrame(byteArrayOf(1, 0), firstSequence, firstSequence),
            )
            val latestSequence = firstSequence + 1L
            session.publish(
                "en",
                PcmAudioFrame(byteArrayOf(2, 0), latestSequence, latestSequence),
            )
            listener.recordWebSocketDelivery(listener.frames.tryReceive().getOrThrow())
        }
        yield()
        assertEquals("frame events must not emit one StateFlow value each", 1, emissionCount)

        val exact = session.observabilitySnapshot()
        yield()
        assertEquals(64L, exact.droppedFrames)
        assertEquals(64L, exact.webSocketDeliveredFrames)
        assertEquals(2, emissionCount)

        collector.cancel()
        listener.close()
        session.close()
    }

    @Test
    fun `subscription descriptor belongs to the atomically admitted generation`() {
        val registry = AudioStreamRegistry()
        val session = registry.configure(listOf(english))

        val subscription = session.subscribe("en")

        assertEquals(session.generation, registry.observability.value.generation)
        assertEquals(english, subscription.descriptor)
        subscription.close()
    }

    @Test
    fun `session descriptors and observability maps cannot be mutated by consumers`() {
        val registry = AudioStreamRegistry()
        val session = registry.configure(listOf(english))
        val listeners = session.observabilitySnapshot().listenersByChannel

        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (session.channels as MutableList<AudioChannelDescriptor>).clear()
        }
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (listeners as MutableMap<String, Int>)["en"] = 99
        }
    }

    @Test
    fun `local monitor receives published pcm without becoming a remote listener`() {
        val registry = AudioStreamRegistry(maxListeners = 1)
        val session = registry.configure(listOf(english))
        val monitor = session.subscribeLocalMonitor("en")
        val frame = PcmAudioFrame(byteArrayOf(7, 0), 1L, utteranceSequence = 41L)

        val result = session.publish("en", frame)
        val monitored = monitor.frames.tryReceive().getOrThrow()
        val snapshot = session.observabilitySnapshot()

        assertTrue(frame.bytes.contentEquals(monitored.bytes))
        assertEquals(0, result.targetListeners)
        assertEquals(0, result.enqueuedFrames)
        assertEquals(0, snapshot.totalListeners)
        assertEquals(0L, snapshot.droppedFrames)
        assertEquals(0L, snapshot.webSocketDeliveredFrames)

        // The reserved remote slot remains available while local monitoring is active.
        val remote = session.subscribe("en")
        assertEquals(1, session.observabilitySnapshot().totalListeners)
        remote.close()
        monitor.close()
        session.close()
    }

    @Test
    fun `slow local monitor never increments remote listener drop telemetry`() {
        val registry = AudioStreamRegistry(listenerBufferFrames = 1)
        val session = registry.configure(listOf(english))
        val monitor = session.subscribeLocalMonitor("en")

        session.publish("en", PcmAudioFrame(byteArrayOf(1, 0), 1L))
        session.publish("en", PcmAudioFrame(byteArrayOf(2, 0), 2L))

        val latest = monitor.frames.tryReceive().getOrThrow()
        val snapshot = session.observabilitySnapshot()
        assertTrue(byteArrayOf(2, 0).contentEquals(latest.bytes))
        assertEquals(0L, snapshot.droppedFrames)
        assertEquals(0, snapshot.totalListeners)

        monitor.close()
        session.close()
    }

    @Test
    fun `session replacement cancels local monitor even when channel id is reused`() {
        val registry = AudioStreamRegistry()
        val original = registry.configure(listOf(english))
        val monitor = original.subscribeLocalMonitor("en")

        registry.configure(listOf(english))

        assertTrue(monitor.frames.tryReceive().isClosed)
        assertFalse(original.isActive())
        monitor.close()
    }
}
