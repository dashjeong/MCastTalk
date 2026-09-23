package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RecognitionInputCompletionTest {
    @Test fun liveInputKeepsExistingRestartAndFailureRecoveryAvailable() {
        val state = RecognitionInputCompletion()
        assertTrue(state.permitsAutomaticRestart())
        state.requireProviderCompletion(false, IllegalStateException("recoverable live failure"))
        assertFalse(state.hasReachedEof())
    }

    @Test fun queuedAutomaticRestartIsRejectedOnceFiniteInputEnds() {
        val state = RecognitionInputCompletion()
        assertTrue(state.permitsAutomaticRestart()) // Request was queued while input was live.
        state.onNormalInputEof()
        assertFalse(state.permitsAutomaticRestart()) // Admission is rechecked when selected.
        state.requireProviderCompletion(true) // A delayed native final acknowledgement is allowed.
    }

    @Test fun nativeEofFailureIsPropagatedRatherThanTurningAPartialIntoSuccessfulCompletion() {
        val state = RecognitionInputCompletion()
        val failure = IllegalStateException("synthetic native finalization failure")
        state.onNormalInputEof()
        assertSame(failure, runCatching { state.requireProviderCompletion(false, failure) }.exceptionOrNull())
    }

    @Test fun restartSelectedJustBeforeEofCannotMasqueradeAsSuccessfulFiniteCompletion() {
        val state = RecognitionInputCompletion()
        state.onNormalInputEof()
        assertThrows(IllegalStateException::class.java) { state.requireProviderCompletion(false) }
        state.onNormalInputEof()
        assertFalse(state.permitsAutomaticRestart())
    }

    @Test fun completedProviderPrefixDoesNotDiscardQueuedFiniteTail(): Unit = runBlocking {
        withTimeout(1_000L) {
            val state = RecognitionInputCompletion()
            val pcm = Channel<Int>(capacity = 3)
            listOf(10, 20, 30).forEach { pcm.send(it) }
            state.onNormalInputEof()
            pcm.close()

            val firstAttempt = state.newAttempt()
            assertEquals(listOf(10), firstAttempt.track(pcm.receiveAsFlow()).take(1).toList())
            state.requireProviderCompletion(true)
            state.requireProgressAfterFiniteEndpoint(firstAttempt)
            assertTrue(state.hasReachedEof())
            assertFalse("Producer EOF must not discard the two queued frames", state.hasDrainedInput())
            assertFalse(state.permitsAutomaticRestart())

            val secondAttempt = state.newAttempt()
            assertEquals(listOf(20, 30), secondAttempt.track(pcm.receiveAsFlow()).toList())
            assertTrue(state.hasDrainedInput())
            state.requireProviderCompletion(true)
        }
    }

    @Test fun eofArrivingAfterEarlyProviderEndpointStillRequiresTheRemainingQueueToDrain(): Unit = runBlocking {
        withTimeout(1_000L) {
            val state = RecognitionInputCompletion()
            val pcm = Channel<Int>(capacity = 1)
            val tailMayFinish = CompletableDeferred<Unit>()
            val producer = launch {
                pcm.send(1)
                tailMayFinish.await()
                pcm.send(2)
                state.onNormalInputEof()
                pcm.close()
            }
            assertEquals(listOf(1), state.newAttempt().track(pcm.receiveAsFlow()).take(1).toList())
            assertFalse(state.hasDrainedInput())
            tailMayFinish.complete(Unit)
            producer.join()
            assertTrue(state.hasReachedEof())
            assertFalse(state.hasDrainedInput())
            assertEquals(listOf(2), state.newAttempt().track(pcm.receiveAsFlow()).toList())
            assertTrue(state.hasDrainedInput())
        }
    }

    @Test fun providerReturningWithoutConsumingFiniteInputFailsInsteadOfRestartingForever() {
        val state = RecognitionInputCompletion()
        val attempt = state.newAttempt()
        state.onNormalInputEof()
        assertThrows(IllegalStateException::class.java) { state.requireProgressAfterFiniteEndpoint(attempt) }
        assertFalse(state.hasDrainedInput())
    }

    @Test fun exceptionalQueueClosureCannotClaimNormalDrain(): Unit = runBlocking {
        withTimeout(1_000L) {
            val state = RecognitionInputCompletion()
            val pcm = Channel<Int>(capacity = 1)
            pcm.send(1)
            state.onNormalInputEof()
            val failure = IllegalStateException("synthetic source failure")
            pcm.close(failure)
            val reported = runCatching { state.newAttempt().track(pcm.receiveAsFlow()).toList() }.exceptionOrNull()
            // Coroutine stack-trace recovery may copy the exception across a suspension boundary.
            // The contract is an explicit source failure and no successful drain, not object identity.
            assertTrue(reported is IllegalStateException)
            assertEquals(failure.message, reported?.message)
            assertFalse(state.hasDrainedInput())
        }
    }
}
