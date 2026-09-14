package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LongRunningSessionSafetyTest {
    @Test fun stalledRecognitionCannotHoldCaptureAndNewConsumerAcceptsNextFrame() = runTest {
        val frame = PcmAudioFrame(byteArrayOf(1, 0), 1)
        val stalled = Channel<PcmAudioFrame>()
        val send = async { forwardRecognitionFrame(stalled, frame) }
        advanceTimeBy(101); runCurrent()
        assertFalse(send.await())
        stalled.cancel()
        assertFalse(forwardRecognitionFrame(stalled, frame))
        val replacement = Channel<PcmAudioFrame>(1)
        assertTrue(forwardRecognitionFrame(replacement, frame))
        assertSame(frame, replacement.receive())
        replacement.cancel()
    }

    @Test fun stoppingInputCancelsEvenWhenRecognitionIsBlocked() = runTest {
        val channel = Channel<PcmAudioFrame>()
        val send = async { forwardRecognitionFrame(channel, PcmAudioFrame(byteArrayOf(1, 0), 1)) }
        runCurrent(); send.cancel(); runCurrent()
        assertTrue(send.isCancelled)
        channel.cancel()
    }

    @Test fun boundedLeaseSurvivesNineVirtualHoursAndStopsRenewingWhenReleased() = runTest {
        var currentOwner = true
        var renewals = 0
        var expiry = testScheduler.currentTime + 600_000L
        val job = renewSessionLease(300_000L) {
            if (!currentOwner) false else {
                assertTrue(testScheduler.currentTime < expiry)
                expiry = testScheduler.currentTime + 600_000L
                renewals++
                true
            }
        }
        advanceTimeBy(9 * 60 * 60 * 1000L); runCurrent()
        assertEquals(108, renewals)
        currentOwner = false
        advanceTimeBy(300_001L); runCurrent()
        assertTrue(job.isCompleted)
        advanceTimeBy(600_000L); runCurrent()
        assertEquals(108, renewals)
    }

    @Test fun heartbeatExcludesSpeechIdentifiersAndErrorDetails() {
        val secret = "private speech URL package and error"
        val current = BroadcastSnapshot(
            inputFrameCount = 200, inputAudibleFrameCount = 100,
            listenerUrl = secret, inputLabel = secret, errorMessage = secret,
            transcripts = listOf(TranslationTranscriptLine(1, secret, 1)),
            translationChannels = listOf(BroadcastChannelSnapshot(secret, secret, secret,
                lastAcceptedSequence = 4, lastTranslatedSequence = 3, lastPublishedSequence = 2)),
        )
        val record = pipelineHeartbeat(current, BroadcastSnapshot(inputFrameCount = 50))
        assertTrue(record.contains("frames=150"))
        assertTrue(record.contains("channels=0:4,3,2,0,0"))
        assertFalse(record.contains(secret))
    }
}
