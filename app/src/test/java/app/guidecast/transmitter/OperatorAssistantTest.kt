package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class OperatorAssistantTest {
    @Test fun adviceExcludesTranscriptsErrorsUrlsAndNamesAndPrioritizesInputRecovery() {
        val snapshot = BroadcastSnapshot(recognitionErrorMessage = "private-error-secret", listenerUrl = "https://private-host/token",
            transcripts = listOf(TranslationTranscriptLine(1, "private speech", 0, true, firstAudioLatencyMillis = mapOf("en" to 2500))),
            translationChannels = listOf(BroadcastChannelSnapshot("en", "en", "private channel name", translationState = BroadcastChannelWorkerState.DEGRADED)))
        val advice = operatorAdvice(snapshot, TranslationModelUiState())
        assertEquals("recognition", advice.first().id)
        assertFalse(advice.toString().contains("private"))
        assertTrue(advice.any { it.id == "latency" && it.observation.contains("2500ms") })
    }
    @Test fun noLatencyOrStabilityClaimWithoutSamples() {
        val advice = operatorAdvice(BroadcastSnapshot(), TranslationModelUiState())
        assertFalse(advice.any { it.id == "latency" })
        assertTrue(advice.any { it.id == "quality" }); assertTrue(advice.size <= 6)
    }
}
