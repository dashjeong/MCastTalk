package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GemmaRuntimeDecouplingTest {

    @Test
    fun successfulGemmaInferenceAndFailedTtsYieldsReadyAndReportsTtsDegraded() = runBlocking {
        var engineTestingCalled = false
        var runtimeReadyCalled = false
        var runtimeFailureMessage: String? = null

        val outcome = executeGemmaVerification(
            markEngineTesting = { engineTestingCalled = true },
            selfTest = { "Hello, welcome to DMZ Peace Walk." },
            markRuntimeReady = { runtimeReadyCalled = true },
            markRuntimeFailure = { message -> runtimeFailureMessage = message },
            probeTts = {
                throw IOException("Moonshine voice download failed: UnknownHostException: models.guidecast.app")
            },
        )

        assertTrue(engineTestingCalled)
        assertTrue("Gemma translation inference succeeded so it must be marked READY", runtimeReadyCalled)
        assertNull("TTS failure must never call markRuntimeFailure on Gemma", runtimeFailureMessage)
        assertEquals(GemmaModelReadiness.READY, outcome.readiness)
        assertEquals("Hello, welcome to DMZ Peace Walk.", outcome.translation)
        assertNull(outcome.failureMessage)
        assertTrue(
            "TTS degradation reason must be separately captured",
            outcome.ttsDegradedReason.orEmpty().contains("Moonshine voice download failed"),
        )
    }

    @Test
    fun successfulGemmaInferenceWithoutTtsProbeYieldsReady() = runBlocking {
        var runtimeReadyCalled = false
        var runtimeFailureMessage: String? = null

        val outcome = executeGemmaVerification(
            markEngineTesting = {},
            selfTest = { "Hello" },
            markRuntimeReady = { runtimeReadyCalled = true },
            markRuntimeFailure = { message -> runtimeFailureMessage = message },
            probeTts = null,
        )

        assertTrue(runtimeReadyCalled)
        assertNull(runtimeFailureMessage)
        assertEquals(GemmaModelReadiness.READY, outcome.readiness)
        assertEquals("Hello", outcome.translation)
        assertNull(outcome.ttsDegradedReason)
    }

    @Test
    fun failedGemmaInferenceForbidsReadyAndRecordsFailure() = runBlocking {
        var engineTestingCalled = false
        var runtimeReadyCalled = false
        var runtimeFailureMessage: String? = null
        var ttsProbeInvoked = false

        val outcome = executeGemmaVerification(
            markEngineTesting = { engineTestingCalled = true },
            selfTest = { throw IllegalStateException("LiteRT-LM native worker out of memory") },
            markRuntimeReady = { runtimeReadyCalled = true },
            markRuntimeFailure = { message -> runtimeFailureMessage = message },
            probeTts = { ttsProbeInvoked = true },
        )

        assertTrue(engineTestingCalled)
        assertFalse("Failed Gemma inference must NEVER mark model READY", runtimeReadyCalled)
        assertFalse("TTS probe must not run if Gemma translation failed", ttsProbeInvoked)
        assertEquals(GemmaModelReadiness.FAILED, outcome.readiness)
        assertNull(outcome.translation)
        assertTrue(
            outcome.failureMessage.orEmpty().contains("LiteRT-LM native worker out of memory"),
        )
        assertEquals(outcome.failureMessage, runtimeFailureMessage)
    }

    @Test
    fun cancellationDuringGemmaSelfTestRepropagatesWithoutMarkingFailureOrReady() = runBlocking {
        var runtimeReadyCalled = false
        var runtimeFailureMessage: String? = null

        try {
            executeGemmaVerification(
                markEngineTesting = {},
                selfTest = { throw CancellationException("Gemma service timeout cancelled") },
                markRuntimeReady = { runtimeReadyCalled = true },
                markRuntimeFailure = { message -> runtimeFailureMessage = message },
            )
            fail("Expected CancellationException to repropagate")
        } catch (error: CancellationException) {
            assertEquals("Gemma service timeout cancelled", error.message)
        }

        assertFalse("Cancellation must not mark READY", runtimeReadyCalled)
        assertNull("Cancellation must not mark runtime failure", runtimeFailureMessage)
    }

    @Test
    fun cancellationDuringTtsProbeRepropagatesWithoutDowngradingReadyGemma() = runBlocking {
        var runtimeReadyCalled = false
        var runtimeFailureMessage: String? = null

        try {
            executeGemmaVerification(
                markEngineTesting = {},
                selfTest = { "Hello" },
                markRuntimeReady = { runtimeReadyCalled = true },
                markRuntimeFailure = { message -> runtimeFailureMessage = message },
                probeTts = { throw CancellationException("TTS probe cancelled") },
            )
            fail("Expected CancellationException to repropagate")
        } catch (error: CancellationException) {
            assertEquals("TTS probe cancelled", error.message)
        }

        assertTrue("Gemma was already marked READY upon translation success", runtimeReadyCalled)
        assertNull("TTS cancellation must not mark Gemma failure", runtimeFailureMessage)
    }
}
