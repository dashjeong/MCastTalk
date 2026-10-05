package app.guidecast.core.stream

import org.junit.Assert.*
import org.junit.Test

class StreamAudioDiscardTest {
    @Test fun perUtteranceCancellationRetainsOtherTurnAndUntaggedFrames() {
        val session = AudioStreamRegistry().configure(descriptors)
        val listener = session.subscribe("en"); val monitor = session.subscribeLocalMonitor("en")
        listOf(1L, 2L, null, 1L, 2L).forEach { session.publish("en", PcmAudioFrame(byteArrayOf(1,0), 0, it)) }
        assertEquals(4, session.discardQueuedAudio("en", 1L))
        for (frames in listOf(listener.frames, monitor.frames)) {
            assertEquals(2L, frames.tryReceive().getOrThrow().utteranceSequence)
            assertNull(frames.tryReceive().getOrThrow().utteranceSequence)
            assertEquals(2L, frames.tryReceive().getOrThrow().utteranceSequence)
            assertTrue(frames.tryReceive().isFailure)
        }
        assertEquals(0, session.discardQueuedAudio("en", 1L))
        session.close()
    }
    private val descriptors = listOf(AudioChannelDescriptor("en", "English", "en", 24_000),
        AudioChannelDescriptor("ja", "Japanese", "ja", 24_000))
    @Test fun interruptionDiscardsSelectedChannelRemoteAndMonitorQueuesWithoutClosingListeners() {
        val session = AudioStreamRegistry().configure(descriptors)
        val english = session.subscribe("en"); val monitor = session.subscribeLocalMonitor("en")
        val japanese = session.subscribe("ja")
        val frame = PcmAudioFrame(byteArrayOf(1, 0), 10, 1)
        session.publish("en", frame); session.publish("ja", frame)
        assertEquals(2, session.discardQueuedAudio("en"))
        assertTrue(english.frames.tryReceive().isFailure); assertTrue(monitor.frames.tryReceive().isFailure)
        assertSame(frame, japanese.frames.tryReceive().getOrNull())
        session.publish("en", frame.copy(utteranceSequence = 2))
        assertEquals(2L, english.frames.tryReceive().getOrThrow().utteranceSequence)
        assertEquals(0L, session.observabilitySnapshot().webSocketDeliveredFrames)
        session.close()
    }
    @Test fun oldSessionCannotDiscardSameChannelInNewGeneration() {
        val registry = AudioStreamRegistry(); val old = registry.configure(descriptors)
        val current = registry.configure(descriptors); val listener = current.subscribe("en")
        val frame = PcmAudioFrame(byteArrayOf(1, 0), 10)
        current.publish("en", frame)
        assertEquals(0, old.discardQueuedAudio("en"))
        assertSame(frame, listener.frames.tryReceive().getOrNull())
        current.close()
    }
}
