package app.guidecast.transmitter

import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeAudioInterruptionTest {
    @Test fun currentTurnCancellationPreservesCompletedPriorTurnAndOtherLanguage() = runTest {
        val session = AudioStreamRegistry().configure(listOf(
            AudioChannelDescriptor("en", "English", "en", 24000),
            AudioChannelDescriptor("ja", "Japanese", "ja", 24000)))
        val local = session.subscribeLocalMonitor("en", preserveNativeAudio = true)
        val remote = session.subscribe("en")
        val other = session.subscribe("ja")
        val retired = NativeAudioRetiredTurns()
        val segments = GeminiLiveSegments("en", "ko", 100)
        fun event(finished: Boolean = false, interrupted: Boolean = false, audio: Boolean = false) =
            GeminiLiveEvent(null, null, finished, interrupted, if (audio) listOf(ByteArray(960)) else emptyList(), null)
        try {
            val a = requireNotNull(segments.accept(event(audio = true), 1))
            assertTrue(requireNotNull(segments.accept(event(finished = true), 2)).isFinal)
            val b = requireNotNull(segments.accept(event(audio = true), 3))
            repeat(2) { session.tryPublish("en", PcmAudioFrame(ByteArray(960), 1, a.sequence)) }
            repeat(2) { session.tryPublish("en", PcmAudioFrame(ByteArray(960), 2, b.sequence)) }
            session.tryPublish("ja", PcmAudioFrame(ByteArray(960), 3, 500))
            val cancelled = requireNotNull(segments.accept(event(interrupted = true), 4))
            val flushes = mutableListOf<Long?>()
            cancelNativeAudioOutputTurn(cancelled.sequence, retired,
                { session.discardQueuedAudio("en", it) }, { flushes += it })
            assertEquals(2, local.bufferSnapshot().pendingFrames)
            assertEquals(listOf(a.sequence, a.sequence), List(2) { requireNotNull(local.receiveNext()).utteranceSequence })
            assertEquals(listOf(a.sequence, a.sequence), List(2) { requireNotNull(remote.frames.tryReceive().getOrNull()).utteranceSequence })
            assertEquals(500L, requireNotNull(other.frames.tryReceive().getOrNull()).utteranceSequence)
            assertTrue(retired.allows(a.sequence)); assertFalse(retired.allows(b.sequence))
            assertEquals(listOf(b.sequence), flushes)
            val c = requireNotNull(segments.accept(event(audio = true), 5))
            session.tryPublish("en", PcmAudioFrame(ByteArray(960), 5, c.sequence))
            assertEquals(c.sequence, requireNotNull(local.receiveNext()).utteranceSequence)
            assertTrue(retired.allows(c.sequence))
        } finally { local.close(); remote.close(); other.close(); session.close() }
    }

    @Test fun dequeuedCancelledFrameCannotUseEpochCapturedAfterCancellation() = runTest {
        val session = AudioStreamRegistry().configure(listOf(AudioChannelDescriptor("en", "English", "en", 24000)))
        val local = session.subscribeLocalMonitor("en", preserveNativeAudio = true)
        val retired = NativeAudioRetiredTurns()
        try {
            session.tryPublish("en", PcmAudioFrame(ByteArray(960), 1, 101))
            val dequeued = requireNotNull(local.receiveNext())
            var epoch = 0L
            cancelNativeAudioOutputTurn(101, retired, { session.discardQueuedAudio("en", it) }, { epoch++ })
            val capturedEpoch = epoch
            var written = 0
            writeLocalMonitorPcm(dequeued.bytes,
                isCurrent = { capturedEpoch == epoch && retired.allows(dequeued.utteranceSequence) },
                writeNonBlocking = { _, _, count -> written += count; count })
            assertEquals(0, written)
        } finally { local.close(); session.close() }
    }

    @Test fun interruptionWithoutIdentifiedOutputCannotEraseQueuedPriorOutput() {
        val retired = NativeAudioRetiredTurns()
        var discarded = false
        var flushed = false
        cancelNativeAudioOutputTurn(null, retired, { discarded = true }, { flushed = true })
        assertFalse(discarded); assertFalse(flushed)
    }
}
