package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceNoteWorkOwnersTest {
    @Test fun staleScreenCannotReleaseTheReplacementScreen() {
        val owners = LocalWorkOwners()
        val old = Any(); val next = Any()
        assertTrue(owners.tryAcquire(old))
        assertFalse(owners.tryAcquire(next))
        owners.setActive(old, false)
        assertTrue(owners.tryAcquire(next))
        owners.setActive(old, false)
        assertTrue(owners.active.value)
        assertTrue(owners.hasOther(old))
        assertFalse(owners.hasOther(next))
        owners.setActive(next, false)
        assertFalse(owners.active.value)
    }

    @Test fun cancelledJobKeepsOwnershipUntilItsFinalSaveCompletes() = runTest {
        val owners = LocalWorkOwners()
        val old = Any(); val next = Any()
        val saving = CompletableDeferred<Unit>()
        assertTrue(owners.tryAcquire(old))
        val job = launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { saving.await() } }
        }
        runCurrent()
        job.invokeOnCompletion { owners.setActive(old, false) }
        job.cancel()
        runCurrent()
        assertTrue(owners.active.value)
        assertFalse(owners.tryAcquire(next))
        saving.complete(Unit)
        job.join()
        assertTrue(owners.tryAcquire(next))
        owners.setActive(old, false)
        assertTrue(owners.active.value)
    }
}
