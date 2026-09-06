package app.guidecast.transmitter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBackendUseStateTest {
    @Test
    fun staleCleanupCannotCloseSettingsOrBroadcastOwner() {
        val state = TranslationBackendUseState()
        state.claim()
        val stoppedBroadcastCleanup = requireNotNull(state.release())

        state.claim()

        assertFalse(state.mayCleanup(stoppedBroadcastCleanup))
        assertEquals(1, state.activeOwnerCount())
    }

    @Test
    fun settingsReleaseSchedulesCleanupOnlyAfterLastOverlappingOwner() {
        val state = TranslationBackendUseState()
        state.claim()
        state.claim()

        assertNull(state.release())
        val finalCleanup = state.release()

        assertNotNull(finalCleanup)
        assertTrue(state.mayCleanup(requireNotNull(finalCleanup)))
    }

    @Test
    fun idempotentLeaseCannotUnderflowSharedOwnerCount() {
        val state = TranslationBackendUseState()
        state.claim()
        var releases = 0
        val lease = TranslationBackendUseLease {
            releases += 1
            state.release()
        }

        lease.close()
        lease.close()

        assertEquals(1, releases)
        assertEquals(0, state.activeOwnerCount())
    }

    @Test
    fun cancellationDuringStandbyTransferCannotLeakOrLoseTheExactLease() = runBlocking {
        val state = TranslationBackendUseState()
        state.claim()
        val lease = TranslationBackendUseLease { state.release() }
        val lifecycleMutex = Mutex(locked = true)
        val transferEntered = CompletableDeferred<Unit>()
        var retained: TranslationBackendUseLease? = null
        val transfer = launch(start = CoroutineStart.UNDISPATCHED) {
            transferTranslationBackendLeaseToStandby(lease) { candidate ->
                transferEntered.complete(Unit)
                lifecycleMutex.withLock { retained = candidate }
            }
        }

        transferEntered.await()
        transfer.cancel()
        lifecycleMutex.unlock()
        withTimeout(1_000L) { transfer.join() }

        assertTrue(transfer.isCancelled)
        assertNotNull(retained)
        assertEquals(1, state.activeOwnerCount())
        requireNotNull(retained).close()
        assertEquals(0, state.activeOwnerCount())
    }

    @Test
    fun failedStandbyTransferClosesLeaseBeforeRethrowing() = runBlocking {
        val state = TranslationBackendUseState()
        state.claim()
        val lease = TranslationBackendUseLease { state.release() }

        val failure = runCatching {
            transferTranslationBackendLeaseToStandby(lease) { error("retain failed") }
        }.exceptionOrNull()

        assertEquals("retain failed", failure?.message)
        assertEquals(0, state.activeOwnerCount())
    }

    @Test
    fun diagnosticClaimPreservesStandbyWhileBroadcastTakeoverHasNoZeroOwnerWindow() {
        val state = TranslationBackendUseState()
        state.claim() // settings standby
        state.claim() // generic diagnostic; standby remains owned

        assertNull(state.release()) // diagnostic ends, standby still owns the backend
        assertEquals(1, state.activeOwnerCount())

        state.claim() // broadcast claims before superseded standby closes
        assertNull(state.release()) // superseded standby closes; broadcast remains
        assertEquals(1, state.activeOwnerCount())
        val stoppedBroadcast = requireNotNull(state.release())
        assertTrue(state.mayCleanup(stoppedBroadcast))
    }

    @Test
    fun providerCleanupFailuresAreIsolatedAndDoNotCancelLaterCleanup() = runBlocking {
        val calls = mutableListOf<String>()
        val reports = mutableListOf<String>()

        val failures = runIsolatedNativeBackendCleanups(
            listOf(
                NativeBackendCleanup("translation") {
                    calls += "translation"
                    error("close failed")
                },
                NativeBackendCleanup("gemma") { calls += "gemma" },
                NativeBackendCleanup("speech") { calls += "speech" },
            ),
        ) { label, _ -> reports += label }

        assertEquals(listOf("translation", "gemma", "speech"), calls)
        assertEquals(setOf("translation"), failures)
        assertEquals(listOf("translation"), reports)

        // A later lifecycle owner can still be acquired and released after the failed cleanup.
        val state = TranslationBackendUseState()
        state.claim()
        assertNotNull(state.release())
    }

    @Test
    fun cancellationBeforeServiceAttachmentEndsHiddenOwnerAndReleasesAnyLease() = runBlocking {
        val owners = TranslationPreparationOwnerState()
        val owner = owners.beginBroadcast("ko-KR", setOf("en"))
        val backend = TranslationBackendUseState()
        val lifecycleMutex = Mutex(locked = true)
        val acquireEntered = CompletableDeferred<Unit>()
        val sessionCurrent = AtomicBoolean(true)
        var attached = false
        val activation = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                acquireAndAttachTranslationBackendOwner(
                    owner = owner,
                    acquire = {
                        acquireEntered.complete(Unit)
                        lifecycleMutex.withLock {
                            backend.claim()
                            TranslationBackendUseLease { backend.release() }
                        }
                    },
                    attach = { _, lease ->
                        if (!sessionCurrent.get()) return@acquireAndAttachTranslationBackendOwner false
                        attached = true
                        // A real service field owns this lease after a successful attachment.
                        lease.close()
                        true
                    },
                    releaseLease = TranslationBackendUseLease::close,
                    endOwner = owners::end,
                )
            }
        }

        acquireEntered.await()
        sessionCurrent.set(false)
        activation.cancel()
        lifecycleMutex.unlock()
        withTimeout(1_000L) { activation.join() }

        assertFalse(attached)
        assertEquals(0, backend.activeOwnerCount())
        assertFalse(owners.isCurrent(owner))
        assertTrue(owners.isCurrent(owners.beginSettings("ko-KR", setOf("ja"))))
    }


    @Test
    fun broadcastTakeoverPermanentlySupersedesInFlightSettingsPreparation() {
        val state = TranslationPreparationOwnerState()
        val settings = state.beginSettings("ko-KR", setOf("en", "ja"))
        assertTrue(state.isCurrent(settings))

        val broadcast = state.beginBroadcast("ko-KR", setOf("zh", "nl"))

        assertFalse(state.isCurrent(settings))
        assertTrue(state.isCurrent(broadcast))
        assertEquals(setOf("zh", "nl"), state.retainedTargetLanguages())
        state.end(broadcast)
        assertFalse(state.isCurrent(settings))
    }

    @Test
    fun settingsStartedDuringBroadcastNeverBecomesNativeOwnerAfterStop() {
        val state = TranslationPreparationOwnerState()
        val broadcast = state.beginBroadcast("ko-KR", setOf("en"))
        val staleSettings = state.beginSettings("ja-JP", setOf("ko"))

        assertFalse(state.isCurrent(staleSettings))
        state.end(broadcast)
        assertFalse(state.isCurrent(staleSettings))

        val nextSettings = state.beginSettings("ja-JP", setOf("ko"))
        assertTrue(state.isCurrent(nextSettings))
    }

    @Test
    fun staleSettingsCannotCommitAfterBroadcastTakeover() {
        val state = TranslationPreparationOwnerState()
        val settings = state.beginSettings("ko-KR", setOf("en"))
        var settingsCommits = 0
        assertTrue(state.runIfCurrent(settings) { settingsCommits += 1 })

        val broadcast = state.beginBroadcast("ja-JP", setOf("en"))

        assertFalse(state.runIfCurrent(settings) { settingsCommits += 1 })
        assertTrue(state.runIfCurrent(broadcast) { })
        assertEquals(1, settingsCommits)
        assertTrue(state.currentSourceMatches("ja"))
        assertFalse(state.currentSourceMatches("ko-KR"))
    }
}
