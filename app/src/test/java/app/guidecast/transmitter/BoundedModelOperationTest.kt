package app.guidecast.transmitter

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BoundedModelOperationTest {
    @Test fun unresponsiveInitialCheckUnlocksAndRetrySucceeds() = runBlocking {
        val states = mutableListOf<Boolean>()
        var error: String? = null
        runBoundedModelOperation(30, states::add, { error = it }) { awaitCancellation() }
        assertEquals(listOf(true, false), states)
        assertNotNull(error)
        var ran = false
        runBoundedModelOperation(500, states::add, { fail(it) }) { ran = true }
        assertTrue(ran)
        assertEquals(listOf(true, false, true, false), states)
    }

    @Test fun exceptionReleasesUiWithoutPretendingReady() = runBlocking {
        var busy = false
        var error = ""
        runBoundedModelOperation(500, { busy = it }, { error = it }) { error("catalog unavailable") }
        assertFalse(busy)
        assertEquals("catalog unavailable", error)
    }

    @Test fun callerCancellationIsNotReportedAsProviderFailure() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var busy = false
        val job = launch {
            runBoundedModelOperation(5_000, { busy = it }, { fail(it) }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(busy)
    }
}
