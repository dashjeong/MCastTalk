package app.guidecast.provider.moonshine.stt

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MoonshineSttCallbackDeadlineTest {
    @Test
    fun silentBinderTimeoutBecomesRecoverableWorkerFailureAndCleansAttempt() = runBlocking {
        var cleanupCount = 0

        val failure = try {
            awaitMoonshineSttCallbackWithin(
                timeoutMillis = 25L,
                timeoutFailure = { MoonshineSttWorkerException("callback timed out") },
                onTimeout = { cleanupCount += 1 },
                awaitCallback = { awaitCancellation() },
            )
            fail("Expected a bounded worker failure")
            null
        } catch (error: Throwable) {
            error
        }

        assertTrue(failure is MoonshineSttWorkerException)
        assertEquals("callback timed out", failure?.message)
        assertEquals(1, cleanupCount)
    }

    @Test
    fun owningSessionCancellationKeepsCancellationIdentityAndDoesNotRunTimeoutCleanup() =
        runBlocking {
            var cleanupCount = 0

            try {
                withTimeout(25L) {
                    awaitMoonshineSttCallbackWithin(
                        timeoutMillis = 10_000L,
                        timeoutFailure = { MoonshineSttWorkerException("wrong deadline") },
                        onTimeout = { cleanupCount += 1 },
                        awaitCallback = { awaitCancellation() },
                    )
                }
                fail("Expected owning-session cancellation")
            } catch (_: TimeoutCancellationException) {
                // Expected: the outer owner, not the Binder callback deadline, cancelled the call.
            }

            assertEquals(0, cleanupCount)
        }

    @Test
    fun completedCallbackDoesNotRunTimeoutCleanup() = runBlocking {
        var cleanupCount = 0

        awaitMoonshineSttCallbackWithin(
            timeoutMillis = 1_000L,
            timeoutFailure = { MoonshineSttWorkerException("unexpected timeout") },
            onTimeout = { cleanupCount += 1 },
            awaitCallback = {},
        )

        assertEquals(0, cleanupCount)
    }
}
