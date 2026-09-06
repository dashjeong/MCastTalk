package app.guidecast.provider.gemma.translation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemmaNativeTerminalBridgeTest {
    @Test
    fun completeJsonIsKeptButDoesNotCompleteBeforeNativeDone() = runBlocking {
        lateinit var callback: GemmaNativeTerminalCallback
        val translation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitBridge { callback = it }
        }

        callback.onChunk("""{"translation":"first complete value"}""")
        assertFalse(translation.isCompleted)
        callback.onChunk(" ignored model tail")
        assertFalse(translation.isCompleted)

        callback.onDone()
        assertEquals("first complete value", withTimeout(1_000L) { translation.await() })
    }

    @Test
    fun cancellationWaitsForNativeTerminalBeforeUnwindingOwner() = runBlocking {
        lateinit var callback: GemmaNativeTerminalCallback
        val ownerReleased = AtomicBoolean(false)
        val translation = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitBridge { callback = it }
            } finally {
                ownerReleased.set(true)
            }
        }
        callback.onChunk("""{"translation":"already complete"}""")

        translation.cancel(CancellationException("client left"))
        assertFalse(ownerReleased.get())
        assertFalse(translation.isCompleted)

        callback.onError(IllegalStateException("late native failure"))
        withTimeout(1_000L) { translation.join() }
        assertTrue(translation.isCancelled)
        assertTrue(ownerReleased.get())
    }

    @Test
    fun terminalFailureWinsEvenAfterCompleteJson() = runBlocking {
        supervisorScope {
            lateinit var callback: GemmaNativeTerminalCallback
            val translation = async(start = CoroutineStart.UNDISPATCHED) {
                awaitBridge { callback = it }
            }
            callback.onChunk("""{"translation":"must not escape"}""")
            val nativeFailure = IllegalStateException("native decode failed")

            callback.onError(nativeFailure)

            try {
                withTimeout(1_000L) { translation.await() }
                fail("Expected native terminal failure")
            } catch (error: IllegalStateException) {
                assertFailureSemantics(nativeFailure, error)
            }
        }
    }

    @Test
    fun synchronousSubmissionThrowDoesNotPretendNativeIsTerminal() = runBlocking {
        supervisorScope {
            lateinit var callback: GemmaNativeTerminalCallback
            val submissionFailure = IllegalArgumentException("ambiguous JNI submission failure")
            val translation = async(start = CoroutineStart.UNDISPATCHED) {
                awaitBridge {
                    callback = it
                    throw submissionFailure
                }
            }
            assertFalse(translation.isCompleted)

            val terminalFailure = IllegalStateException("native terminal failure")
            callback.onError(terminalFailure)
            try {
                withTimeout(1_000L) { translation.await() }
                fail("Expected native terminal failure")
            } catch (error: IllegalStateException) {
                assertFailureSemantics(terminalFailure, error)
            }
        }
    }

    private suspend fun awaitBridge(
        start: (GemmaNativeTerminalCallback) -> Unit,
    ): String = awaitGemmaNativeTerminal(
        start = start,
        mergeChunk = { output, chunk -> output.append(chunk) },
        completeTranslation = { output -> completeGemmaJsonTranslation(output) },
        parseTerminal = { raw ->
            completeGemmaJsonTranslation(raw) ?: error("incomplete terminal output")
        },
    )

    private fun assertFailureSemantics(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stack-trace recovery may clone the exception and retain the original as cause.
        actual.cause?.let { recoveredCause ->
            assertEquals(expected.javaClass, recoveredCause.javaClass)
            assertEquals(expected.message, recoveredCause.message)
        }
    }
}
