package app.guidecast.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientLanguageSelectionTest {
    @Test
    fun languageIsLockedThroughoutPreparationAndListening() {
        for (phase in listOf(
            ClientSessionPhase.PREPARING_MODELS, ClientSessionPhase.CONNECTING,
            ClientSessionPhase.LISTENING, ClientSessionPhase.PAUSED,
        )) {
            assertFalse(phase.name, ClientUiState(phase = phase).canSelectTarget)
        }
    }

    @Test
    fun languageCanBeChangedAfterPreparationOrFailure() {
        assertTrue(ClientUiState(phase = ClientSessionPhase.IDLE, modelReady = true).canSelectTarget)
        assertTrue(ClientUiState(phase = ClientSessionPhase.ERROR).canSelectTarget)
    }
}
