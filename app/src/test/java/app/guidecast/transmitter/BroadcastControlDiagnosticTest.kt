package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BroadcastControlDiagnosticTest {
    @Test fun `sentence worker transitions cannot flood control event history`() {
        val live = BroadcastSnapshot(phase = BroadcastPhase.LIVE, inputPhase = InputPhase.ACTIVE)
        val baseline = broadcastControlDiagnostic(live)
        repeat(20_000) { sequence ->
            val working = live.copy(translationChannels = listOf(BroadcastChannelSnapshot(
                channelId = "en", languageTag = "en", displayName = "English",
                translationState = if (sequence % 2 == 0) BroadcastChannelWorkerState.ACTIVE
                    else BroadcastChannelWorkerState.IDLE,
                lastAcceptedSequence = sequence.toLong(), publishedFrameCount = sequence * 50L,
                translationFailures = sequence.toLong(),
            )))
            assertEquals(baseline, broadcastControlDiagnostic(working))
        }
    }

    @Test fun `operator error and recovery remain distinguishable without content`() {
        val active = BroadcastSnapshot(phase = BroadcastPhase.LIVE, inputPhase = InputPhase.ACTIVE)
        val failed = active.copy(inputPhase = InputPhase.FAILED,
            inputErrorMessage = "PRIVATE_TRANSCRIPT_AND_TOKEN", listenerUrl = "https://PRIVATE_URL")
        val record = broadcastControlDiagnostic(failed)
        assertNotEquals(broadcastControlDiagnostic(active), record)
        assertFalse(record.contains("PRIVATE"))
        assertNotEquals(broadcastControlDiagnostic(active),
            broadcastControlDiagnostic(active.copy(runMode = BroadcastRunMode.STANDALONE)))
    }
}
