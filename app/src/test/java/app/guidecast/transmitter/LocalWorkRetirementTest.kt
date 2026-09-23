package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalWorkRetirementTest {
    @Test fun libraryAndOwnerRemainOpenUntilEveryCancelledScopeChildHasFinished() = runTest {
        val owners = LocalWorkOwners()
        val old = Any(); val next = Any()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + scopeJob)
        val firstSave = CompletableDeferred<Unit>()
        val secondSave = CompletableDeferred<Unit>()
        var closes = 0
        listOf(firstSave, secondSave).forEach { save -> scope.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) {
                save.await()
                assertEquals("Database must remain open during final persistence", 0, closes)
            } }
        } }
        runCurrent()
        assertTrue(owners.tryAcquire(old))
        retireLocalWork(scopeJob) { closes++; owners.setActive(old, false) }
        runCurrent()
        assertFalse(owners.tryAcquire(next))
        firstSave.complete(Unit); runCurrent()
        assertEquals(0, closes)
        assertTrue(owners.active.value)
        secondSave.complete(Unit); scopeJob.join()
        assertEquals(1, closes)
        assertTrue(owners.tryAcquire(next))
        owners.setActive(old, false)
        assertTrue(owners.active.value)
    }

    @Test fun idleAndAlreadyFinishedScopesCloseImmediatelyExactlyOnce() {
        var closes = 0
        retireLocalWork(null) { closes++ }
        val finished = SupervisorJob().apply { complete() }
        retireLocalWork(finished) { closes++ }
        assertEquals(2, closes)
    }
}
