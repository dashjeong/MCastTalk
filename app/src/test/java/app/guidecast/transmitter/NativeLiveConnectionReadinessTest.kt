package app.guidecast.transmitter

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeLiveConnectionReadinessTest {
    @Test fun microphoneActivationWaitsForActualSetupCompletion() = runTest {
        val gate = NativeLiveConnectionReadiness()
        var captureActivated = false
        val job = launch { gate.awaitReady(); captureActivated = true }
        runCurrent()
        assertFalse(captureActivated)
        gate.markReady(); runCurrent()
        assertTrue(captureActivated)
        job.join()
    }
    @Test fun closingBeforeAckUnblocksWaiterAndLateAckCannotActivateCapture() = runTest {
        val gate = NativeLiveConnectionReadiness()
        var captureActivated = false
        val outcome = async { runCatching { gate.awaitReady(); captureActivated = true } }
        runCurrent(); gate.close(); runCurrent()
        assertTrue(outcome.await().isFailure)
        assertFalse(gate.markReady())
        assertFalse(captureActivated)
    }
    @Test fun closedAckCannotStartANewCaptureAndNewGateIsIndependent() = runTest {
        val first = NativeLiveConnectionReadiness()
        first.markReady(); first.close()
        assertTrue(runCatching { first.awaitReady() }.isFailure)
        val second = NativeLiveConnectionReadiness()
        second.markReady(); second.awaitReady()
    }
    @Test fun missingAckTimesOutWithoutActivatingCapture() = runTest {
        val gate = NativeLiveConnectionReadiness()
        var activated = false
        val result = async { runCatching { gate.awaitReady(100); activated = true } }
        advanceTimeBy(101); runCurrent()
        assertTrue(result.await().exceptionOrNull() is TimeoutCancellationException)
        assertFalse(activated)
    }
}
