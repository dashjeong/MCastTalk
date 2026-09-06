package app.guidecast.provider.gemma.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaServiceGenerationGateTest {
    @Test
    fun normalStopRestartWaitsForPreviousNativeClose() = runBlocking {
        val gate = GemmaServiceGenerationGate()
        val stoppedService = gate.beginGeneration()
        val replacementService = gate.beginGeneration()

        stoppedService.awaitPredecessorClosed()
        val replacementReady = async(start = CoroutineStart.UNDISPATCHED) {
            replacementService.awaitPredecessorClosed()
        }
        assertFalse(replacementReady.isCompleted)

        stoppedService.confirmClosed()
        withTimeout(1_000L) { replacementReady.await() }
        assertTrue(replacementReady.isCompleted)
        replacementService.confirmClosed()
    }

    @Test
    fun cancelledEmptyReplacementCannotBypassHungPredecessor() = runBlocking {
        val gate = GemmaServiceGenerationGate()
        val failedNativeService = gate.beginGeneration()
        val cancelledReplacement = gate.beginGeneration()
        val laterRecovery = gate.beginGeneration()

        cancelledReplacement.confirmClosed()
        val recoveryReady = async(start = CoroutineStart.UNDISPATCHED) {
            laterRecovery.awaitPredecessorClosed()
        }
        assertFalse(recoveryReady.isCompleted)

        failedNativeService.confirmClosed()
        withTimeout(1_000L) { recoveryReady.await() }
        assertTrue(recoveryReady.isCompleted)
        laterRecovery.confirmClosed()
    }

    @Test
    fun failedNativeCloseKeepsReplacementBlockedInSameProcess() = runBlocking {
        val gate = GemmaServiceGenerationGate()
        val failedService = gate.beginGeneration()
        val replacement = gate.beginGeneration()
        val closeMayReturn = CompletableDeferred<Unit>()

        val closeAttempt = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                closeMayReturn.await()
                error("native close failed")
            } catch (_: IllegalStateException) {
                // Service logs cleanup failure and deliberately does not confirm native close.
            }
        }
        val replacementReady = async(start = CoroutineStart.UNDISPATCHED) {
            replacement.awaitPredecessorClosed()
        }
        assertFalse(replacementReady.isCompleted)

        closeMayReturn.complete(Unit)
        closeAttempt.await()
        assertFalse(replacementReady.isCompleted)

        // Test cleanup: a genuinely confirmed native close (or a new Linux process with a fresh
        // gate) is the only event that may release the replacement.
        failedService.confirmClosed()
        withTimeout(1_000L) { replacementReady.await() }
        replacement.confirmClosed()
    }
}
