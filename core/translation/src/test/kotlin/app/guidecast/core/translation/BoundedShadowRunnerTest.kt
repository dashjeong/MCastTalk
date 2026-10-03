package app.guidecast.core.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundedShadowRunnerTest {
    @Test fun queueIsBoundedAndRevokedQueuedWorkNeverRuns() = runTest {
        val gate = CompletableDeferred<Unit>()
        var permitted = true
        var calls = 0
        val runner = BoundedShadowRunner(this, 500)
        assertTrue(runner.offer({ true }) { gate.await() })
        runCurrent()
        assertTrue(runner.offer({ permitted }) { calls++ })
        assertFalse(runner.offer({ true }) { calls++ })
        permitted = false
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, calls)
        runner.close()
    }
    @Test fun hungOrFailedComparisonDoesNotBlockLaterWork() = runTest {
        val runner = BoundedShadowRunner(this, 100)
        runner.offer({ true }) { CompletableDeferred<Unit>().await() }
        runCurrent()
        var completed = false
        runner.offer({ true }) { completed = true }
        advanceUntilIdle()
        assertTrue(completed)
        runner.close()
    }
}
