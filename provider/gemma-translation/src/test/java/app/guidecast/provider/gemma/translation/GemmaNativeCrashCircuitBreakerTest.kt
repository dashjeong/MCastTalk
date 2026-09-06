package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemmaNativeCrashCircuitBreakerTest {
    @Test
    fun definiteNativeDeathBlocksRespawnUntilExplicitVerificationSucceeds() {
        val breaker = GemmaNativeCrashCircuitBreaker()
        var workerSpawns = 0
        fun automaticAttempt() {
            breaker.requireAutomaticAttemptAllowed("standard")
            workerSpawns += 1
        }

        automaticAttempt()
        breaker.recordDefiniteWorkerDeath("standard")
        try {
            automaticAttempt()
            fail("A blocked model must not create another automatic worker")
        } catch (_: IllegalStateException) {
            // Expected: fallback handles the blocked automatic attempt without a worker spawn.
        }
        assertEquals(1, workerSpawns)

        breaker.rearmAfterSuccessfulExplicitVerification("standard")
        automaticAttempt()
        assertEquals(2, workerSpawns)
    }

    @Test
    fun latchIsModelScopedAndOrdinaryFailuresDoNotBlock() {
        val breaker = GemmaNativeCrashCircuitBreaker()
        breaker.recordDefiniteWorkerDeath("standard")

        assertTrue(breaker.isBlocked("standard"))
        assertFalse(breaker.isBlocked("gpu_optimized"))
        breaker.requireAutomaticAttemptAllowed("gpu_optimized")
    }

    @Test
    fun onlyUnfinishedSubmittedWorkerDeathTripsLatch() {
        assertTrue(shouldLatchGemmaNativeWorkerDeath(
            nativeSubmissionPlanned = true,
            nativeFinished = false,
        ))
        assertFalse(shouldLatchGemmaNativeWorkerDeath(
            nativeSubmissionPlanned = false,
            nativeFinished = false,
        ))
        assertFalse(shouldLatchGemmaNativeWorkerDeath(
            nativeSubmissionPlanned = true,
            nativeFinished = true,
        ))
        assertTrue("A client cancellation is not proof that native work stopped",
            shouldLatchGemmaNativeWorkerDeath(
            nativeSubmissionPlanned = true,
            nativeFinished = false,
        ))
    }
}
