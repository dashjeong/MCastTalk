package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class ServiceFlowDiagnosticsTest {
    @Test fun customModelAndAllPrivateOptionFieldsStayOutOfDiagnosticRecord() {
        val secret = "SYNTHETIC_PRIVATE_CANARY"
        val options = TranslationApiOptions(provider = TranslationApiProvider.COMPATIBLE, model = secret,
            baseUrl = "https://$secret.example/v1", domainPrompt = secret)
        val result = serviceFlowSnapshot(options, "00000000-0000-0000-0000-000000000001", 3,
            ServiceFlowAction.PAUSE_REQUEST, before = InputPhase.ACTIVE, after = InputPhase.PAUSED)
        assertFalse(result.contains(secret)); assertTrue(result.contains("model=CUSTOM"))
        assertTrue(result.contains("before=ACTIVE after=PAUSED"))
        assertTrue(result.contains("input_generation=3"))
    }
    @Test fun sessionCorrelationIsNewButControlDoesNotInventFrameGenerations() {
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        assertNotEquals(first, second)
        val start = serviceFlowSnapshot(TranslationApiOptions(), first, 4, ServiceFlowAction.INPUT_START)
        val pause = serviceFlowSnapshot(TranslationApiOptions(), first, 4, ServiceFlowAction.PAUSE_REQUEST)
        assertTrue(start.contains("input_generation=4")); assertTrue(pause.contains("input_generation=4"))
        assertTrue(start.contains("model=LOCAL_MODEL"))
    }
}
