package app.guidecast.transmitter

import app.guidecast.core.stream.ChannelAudioPublicationCoordinator
import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CaptureFrameHandoffTest {
    @Test fun busySourceOutputStillDeliversEveryCapturedFrameToRecognition() = runTest {
        val publication = ChannelAudioPublicationCoordinator(listOf("source", "en", "zh", "ja"))
        val occupied = publication.acquireChannel("source")
        val recognition = Channel<PcmAudioFrame>(100)
        var published = 0
        repeat(100) { sequence ->
            val frame = PcmAudioFrame(byteArrayOf(sequence.toByte(), 0), sequence.toLong())
            assertTrue(publishSourceThenForwardRecognitionFrame(frame, true, publication, { published++ }, recognition))
            assertSame(frame, recognition.receive())
        }
        assertEquals(0, published)
        occupied.close()
        val fresh = PcmAudioFrame(byteArrayOf(1, 0), 101)
        assertTrue(publishSourceThenForwardRecognitionFrame(fresh, true, publication, { published++ }, recognition))
        assertSame(fresh, recognition.receive())
        assertEquals(1, published)
        publication.acquireChannel("source").close() // Source lease was released after publication.
    }

    @Test fun replacedRecognitionChannelDoesNotStopIndependentSourceOutput() = runTest {
        val recognition = Channel<PcmAudioFrame>().also { it.close() }
        var published = 0
        assertFalse(publishSourceThenForwardRecognitionFrame(PcmAudioFrame(byteArrayOf(0, 0), 1),
            true, null, { published++ }, recognition))
        assertEquals(1, published)
    }

    @Test fun previewForwardsInputWithoutPublishingBroadcastSource() = runTest {
        val recognition = Channel<PcmAudioFrame>(1)
        val frame = PcmAudioFrame(byteArrayOf(0, 0), 1)
        assertTrue(publishSourceThenForwardRecognitionFrame(frame, false, null, { fail("Preview must not publish source") }, recognition))
        assertSame(frame, recognition.receive())
    }
}
