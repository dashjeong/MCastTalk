package app.guidecast.provider.gemma.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GemmaOfflineEvaluationDeadlineTest {
    @Test fun admissionWaitExpiresAndRunsCleanup() = runTest {
        var cleaned = false
        val admission = CompletableDeferred<Unit>()
        try {
            withGemmaOfflineEvaluationDeadline {
                try { admission.await() } finally { cleaned = true }
            }
            fail("Admission must share the fixed evaluation budget")
        } catch (_: TimeoutCancellationException) {
            assertEquals(60_000L, testScheduler.currentTime)
            assertTrue(cleaned)
        }
    }

    @Test fun admissionAndInferenceShareOneBudget() = runTest {
        try {
            withGemmaOfflineEvaluationDeadline {
                delay(40_000)
                delay(30_000)
            }
            fail("Inference must not receive a fresh budget after admission")
        } catch (_: TimeoutCancellationException) {
            assertEquals(60_000L, testScheduler.currentTime)
        }
    }

    @Test fun queuedTimeoutDoesNotCancelTheGenerationThatOwnsTheLane() = runTest {
        val coordinator = GemmaGenerationCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            coordinator.runGeneration { entered.complete(Unit); release.await(); "owner" }
        }
        entered.await()
        try {
            withGemmaOfflineEvaluationDeadline { coordinator.runGeneration { "queued" } }
            fail("Queued evaluation must expire")
        } catch (_: TimeoutCancellationException) {
            assertFalse(owner.isCancelled)
            release.complete(Unit)
            assertEquals("owner", owner.await())
            assertEquals("next", coordinator.runGeneration { "next" })
        }
    }

    @Test fun completedResultIsPreserved() = runTest {
        assertEquals("completed", withGemmaOfflineEvaluationDeadline {
            delay(59_999)
            "completed"
        })
    }

    @Test fun callerCancellationIsPreservedAndCleansUp() = runTest {
        val entered = CompletableDeferred<Unit>()
        var cleaned = false
        val task = async {
            withGemmaOfflineEvaluationDeadline {
                try { entered.complete(Unit); delay(60_000) } finally { cleaned = true }
            }
        }
        entered.await()
        task.cancel(CancellationException("test cancellation"))
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(cleaned)
        assertEquals(0L, testScheduler.currentTime)
    }
}
