package app.guidecast.transmitter

import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelServiceDecisionTest {
    @Test
    fun nativeDeathLatchSkipsAutomaticWarmupUntilExplicitVerification() {
        assertFalse(isGemmaBroadcastEligible(
            requested = true, languagePairSupported = true, hardwareSupported = true,
            modelReady = true, automaticRetryBlocked = true,
        ))
        assertTrue(isGemmaBroadcastEligible(
            requested = true, languagePairSupported = true, hardwareSupported = true,
            modelReady = true, automaticRetryBlocked = false,
        ))
    }

    @Test
    fun verifiedOrReadyModelIsNeverDownloadedAgain() {
        assertFalse(GemmaModelReadiness.VERIFIED.requiresModelDownload())
        assertFalse(GemmaModelReadiness.READY.requiresModelDownload())
    }

    @Test
    fun missingIncompleteOrFailedModelStillUsesInstaller() {
        assertTrue(GemmaModelReadiness.NOT_INSTALLED.requiresModelDownload())
        assertTrue(GemmaModelReadiness.DOWNLOADING.requiresModelDownload())
        assertTrue(GemmaModelReadiness.VERIFYING.requiresModelDownload())
        assertTrue(GemmaModelReadiness.ENGINE_TESTING.requiresModelDownload())
        assertTrue(GemmaModelReadiness.FAILED.requiresModelDownload())
    }
}
