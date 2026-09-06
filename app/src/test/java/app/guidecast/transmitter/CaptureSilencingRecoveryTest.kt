package app.guidecast.transmitter

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSilencingRecoveryTest {
    @Test fun onlySustainedAndroidSilencingTriggersOneRecovery() = runTest {
        val states = MutableStateFlow(false)
        var calls = 0
        val job = launch { observeSustainedCaptureSilencing(states) { calls++ } }
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(0, calls)
        states.value = true
        runCurrent()
        advanceTimeBy(500)
        states.value = false
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(0, calls)
        states.value = true
        runCurrent()
        advanceTimeBy(751)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(10_000)
        assertEquals(1, calls)
        job.cancel()
    }

    @Test fun sessionCancellationDisarmsPendingRecovery() = runTest {
        var calls = 0
        val job = launch {
            observeSustainedCaptureSilencing(MutableStateFlow(true)) { calls++ }
        }
        runCurrent()
        advanceTimeBy(500)
        job.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, calls)
    }
}
