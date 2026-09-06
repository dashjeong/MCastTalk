package app.guidecast.provider.gemma.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemmaGenerationCoordinatorTest {
    @Test
    fun immediateFailureCleanupUsesExactFailedBoundaryAndRecoveryWaits() = runBlocking {
        val coordinator = GemmaGenerationCoordinator()
        val failure = IllegalStateException("native inference failed")
        try {
            coordinator.runGeneration<String> { requestId ->
                assertEquals(0L, requestId)
                throw failure
            }
            fail("Expected generation failure")
        } catch (error: IllegalStateException) {
            assertEquals(failure, error)
        }

        val cleanupEntered = CompletableDeferred<Long>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val cleanup = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.cleanupLatestFailure { failedRequestId ->
                cleanupEntered.complete(failedRequestId)
                releaseCleanup.await()
            }
        }
        assertEquals(0L, cleanupEntered.await())

        val recoveryEntered = CompletableDeferred<Long>()
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runGeneration { requestId ->
                recoveryEntered.complete(requestId)
                "recovered"
            }
        }
        assertFalse(recoveryEntered.isCompleted)

        releaseCleanup.complete(Unit)
        assertTrue(cleanup.await())
        assertEquals("recovered", recovery.await())
        assertEquals(1L, recoveryEntered.await())
    }

    @Test
    fun delayedOldCleanupNeverRunsAcrossAnActiveRecovery() = runBlocking {
        val coordinator = GemmaGenerationCoordinator()
        try {
            coordinator.runGeneration<Unit> { throw IllegalStateException("timeout") }
            fail("Expected generation failure")
        } catch (_: IllegalStateException) {
            // Expected: request 0 is now the captured failure boundary.
        }

        val recoveryEntered = CompletableDeferred<Long>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val recovery = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runGeneration { requestId ->
                recoveryEntered.complete(requestId)
                releaseRecovery.await()
                "recovered"
            }
        }
        assertEquals(1L, recoveryEntered.await())

        var destructiveCleanupCalls = 0
        val delayedCleanup = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.cleanupLatestFailure {
                destructiveCleanupCalls += 1
            }
        }
        assertFalse(delayedCleanup.isCompleted)

        releaseRecovery.complete(Unit)
        assertEquals("recovered", recovery.await())
        assertFalse(delayedCleanup.await())
        assertEquals(0, destructiveCleanupCalls)
    }

    @Test
    fun cancelledGenerationDoesNotCreateFailureCleanupWork() = runBlocking {
        val coordinator = GemmaGenerationCoordinator()
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.runGeneration<Unit> {
                throw kotlinx.coroutines.CancellationException("operator stopped")
            }
        }
        cancelled.cancel()

        var cleanupCalls = 0
        assertFalse(
            coordinator.cleanupLatestFailure {
                cleanupCalls += 1
            },
        )
        assertEquals(0, cleanupCalls)
    }
}
