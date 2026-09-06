package app.guidecast.provider.moonshine.stt

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineSttServiceGenerationGateTest {
    @Test
    fun `replacement generation waits for confirmed native close`() = runBlocking {
        val gate = MoonshineSttServiceGenerationGate()
        val first = gate.beginGeneration()
        val replacement = gate.beginGeneration()

        first.awaitPredecessorClosed()
        val replacementReady = async { replacement.awaitPredecessorClosed() }
        assertFalse(replacementReady.isCompleted)

        first.closeRuntimeThenMarkClosed { Unit }
        withTimeout(1_000L) { replacementReady.await() }
        assertTrue(replacementReady.isCompleted)
        replacement.markClosed()
    }

    @Test
    fun `transient close failure is retried before replacement is released`() = runBlocking {
        val generationGate = MoonshineSttServiceGenerationGate()
        val stoppedService = generationGate.beginGeneration()
        val replacementService = generationGate.beginGeneration()
        val runtimeCloseGate = MoonshineSttRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)
        val replacementReady = async { replacementService.awaitPredecessorClosed() }

        stoppedService.awaitPredecessorClosed()
        val closeError = closeMoonshineSttRuntimeWithRetry(maxAttempts = 2) {
            runtimeCloseGate.close {
                stoppedService.closeRuntimeThenMarkClosed {
                    if (closeCalls.incrementAndGet() == 1) error("transient close failure")
                }
            }
        }

        assertNull(closeError)
        withTimeout(1_000L) { replacementReady.await() }
        assertEquals(2, closeCalls.get())

        // A later lifecycle callback cannot close an already confirmed runtime again.
        runtimeCloseGate.close { closeCalls.incrementAndGet() }
        assertEquals(2, closeCalls.get())
        replacementService.markClosed()
    }

    @Test
    fun `exhausted close retries keep replacement fail closed`() = runBlocking {
        val generationGate = MoonshineSttServiceGenerationGate()
        val stoppedService = generationGate.beginGeneration()
        val replacementService = generationGate.beginGeneration()
        val runtimeCloseGate = MoonshineSttRuntimeCloseGate()
        val closeCalls = AtomicInteger(0)

        stoppedService.awaitPredecessorClosed()
        val closeError = closeMoonshineSttRuntimeWithRetry(maxAttempts = 2) {
            runtimeCloseGate.close {
                stoppedService.closeRuntimeThenMarkClosed {
                    closeCalls.incrementAndGet()
                    error("persistent close failure")
                }
            }
        }

        assertTrue(closeError?.message.orEmpty().contains("persistent close failure"))
        assertEquals(2, closeCalls.get())
        val replacementReady = async { replacementService.awaitPredecessorClosed() }
        delay(100L)
        assertFalse("replacement bypassed an unconfirmed STT close", replacementReady.isCompleted)
        replacementReady.cancelAndJoin()
    }

    @Test
    fun `partial initialization close failure retains exact owner and blocks another load`() {
        val owners = MoonshineSttNativeOwnerRegistry<FakeTranscriber>()
        val candidate = FakeTranscriber(failFirstClose = true)
        val initializationFailure = IllegalStateException("load failed")
        owners.trackInitialization(candidate)

        val failure = runCatching {
            owners.closeFailedInitialization(
                candidate = candidate,
                initializationFailure = initializationFailure,
                closeCandidate = FakeTranscriber::close,
            )
        }.exceptionOrNull()

        assertTrue(failure is MoonshineSttNativeCloseNotConfirmedException)
        assertSame(initializationFailure, failure?.cause)
        assertTrue(owners.tracks(candidate))
        assertEquals(1, owners.trackedOwnerCount())
        assertTrue(runCatching { owners.requireCanInitialize() }.isFailure)

        owners.closeAll(FakeTranscriber::close)
        assertFalse(owners.tracks(candidate))
        assertEquals(0, owners.trackedOwnerCount())
        owners.requireCanInitialize()
        assertEquals(2, candidate.closeCalls)
    }

    @Test
    fun `confirmed partial initialization cleanup preserves original load failure`() {
        val owners = MoonshineSttNativeOwnerRegistry<FakeTranscriber>()
        val candidate = FakeTranscriber(failFirstClose = false)
        val initializationFailure = IllegalStateException("load failed")
        owners.trackInitialization(candidate)

        val failure = runCatching {
            owners.closeFailedInitialization(
                candidate = candidate,
                initializationFailure = initializationFailure,
                closeCandidate = FakeTranscriber::close,
            )
        }.exceptionOrNull()

        assertSame(initializationFailure, failure)
        assertEquals(0, owners.trackedOwnerCount())
        owners.requireCanInitialize()
        assertEquals(1, candidate.closeCalls)
    }

    @Test
    fun `active owner remains reachable until shutdown close succeeds`() {
        val owners = MoonshineSttNativeOwnerRegistry<FakeTranscriber>()
        val active = FakeTranscriber(failFirstClose = true)
        owners.trackInitialization(active)
        owners.activate(active)

        val firstFailure = runCatching { owners.closeAll(FakeTranscriber::close) }
            .exceptionOrNull()

        assertTrue(firstFailure?.message.orEmpty().contains("close failed"))
        assertSame(active, owners.activeOrNull())
        assertTrue(owners.tracks(active))

        owners.closeAll(FakeTranscriber::close)
        assertNull(owners.activeOrNull())
        assertEquals(0, owners.trackedOwnerCount())
        assertEquals(2, active.closeCalls)
    }

    private class FakeTranscriber(
        private val failFirstClose: Boolean,
    ) {
        var closeCalls = 0
            private set

        fun close() {
            closeCalls += 1
            if (failFirstClose && closeCalls == 1) error("close failed")
        }
    }
}
