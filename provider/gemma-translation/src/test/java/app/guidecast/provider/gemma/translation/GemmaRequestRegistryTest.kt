package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaRequestRegistryTest {
    @Test
    fun deathLinkStaysUntilBothClientOutcomeAndTransferredNativeWorkAreTerminal() {
        val resultFirst = GemmaRequestCompletionState()
        assertTrue(resultFirst.tryComplete())
        assertFalse(
            resultFirst.mayReleaseDeathRecipient(
                nativeSubmissionPlanned = true,
                nativeFinished = false,
            ),
        )
        assertTrue(
            resultFirst.mayReleaseDeathRecipient(
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )

        val nativeFirst = GemmaRequestCompletionState()
        assertFalse(
            nativeFirst.mayReleaseDeathRecipient(
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )
        assertTrue(nativeFirst.tryComplete())
        assertTrue(
            nativeFirst.mayReleaseDeathRecipient(
                nativeSubmissionPlanned = true,
                nativeFinished = true,
            ),
        )
    }

    @Test
    fun nativeTerminalSignalReportsExactlyOnceAcrossCancellationAndCompletionRaces() {
        var reports = 0
        val signal = GemmaNativeTerminalSignal { reports += 1 }

        signal.reportOnce()
        signal.reportOnce()
        signal.reportOnce()

        assertEquals(1, reports)
    }

    @Test
    fun cancellationBeforeDeathLinkStillRequiresTheLaterLinkToBeRemoved() {
        val state = GemmaRequestCompletionState()

        assertTrue(state.tryComplete())
        assertFalse(state.takeDeathRecipientLink())
        assertTrue(state.markDeathRecipientLinked())
        assertTrue(state.takeDeathRecipientLink())
        assertFalse(state.takeDeathRecipientLink())
    }

    @Test
    fun deathLinkBeforeCallbackIsRemovedByOnlyTheFirstCompletion() {
        val state = GemmaRequestCompletionState()

        assertFalse(state.markDeathRecipientLinked())
        assertTrue(state.tryComplete())
        assertFalse(state.tryComplete())
        assertTrue(state.takeDeathRecipientLink())
        assertTrue(state.isCompleted)
    }

    @Test
    fun serverTeardownWakesClientOnceWhileClientCancellationStaysSilent() {
        var cancellations = 0
        val messages = mutableListOf<String>()
        val request = GemmaCancellableRequest(
            work = Any(),
            cancelWork = { cancellations += 1 },
            reportServerCancellation = messages::add,
        )

        request.cancelFromClient()
        request.cancelFromServer("reset")
        request.cancelFromServer("destroy")

        assertEquals(3, cancellations)
        assertEquals(listOf("reset"), messages)
    }

    @Test
    fun cancelArrivingBeforeOneWayRegistrationRejectsTheLateRequest() {
        val registry = GemmaRequestRegistry<Any>(maxPendingRequests = 4)

        assertNull(registry.cancel(7))
        assertEquals(GemmaRequestAdmission.OBSOLETE, registry.register(7, Any()))
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun delayedResetCancelsOnlyIdsAllocatedBeforeItsCutoff() {
        val registry = GemmaRequestRegistry<Any>(maxPendingRequests = 4)
        val old = Any()
        val newer = Any()
        assertEquals(GemmaRequestAdmission.ACCEPTED, registry.register(10, old))
        assertEquals(GemmaRequestAdmission.ACCEPTED, registry.register(11, newer))

        val cancelled = requireNotNull(registry.resetThrough(10))

        assertEquals(1, cancelled.size)
        assertSame(old, cancelled.single())
        assertEquals(GemmaRequestAdmission.OBSOLETE, registry.register(9, Any()))
        assertTrue(registry.complete(11, newer))
        assertEquals(1, registry.pendingCount())
    }

    @Test
    fun completionRemovesACancelledLazyJobAndCannotRemoveItsReplacement() {
        val registry = GemmaRequestRegistry<Any>(maxPendingRequests = 1)
        val cancelled = Any()
        assertEquals(GemmaRequestAdmission.ACCEPTED, registry.register(1, cancelled))
        assertSame(cancelled, registry.cancel(1))
        assertTrue(registry.complete(1, cancelled))
        assertEquals(0, registry.pendingCount())

        val replacement = Any()
        assertEquals(GemmaRequestAdmission.ACCEPTED, registry.register(2, replacement))
        assertFalse(registry.complete(2, cancelled))
        assertEquals(1, registry.pendingCount())
    }

    @Test
    fun destroyRejectsLateBinderTransactionsAndReturnsOwnedJobs() {
        val registry = GemmaRequestRegistry<Any>(maxPendingRequests = 4)
        val active = Any()
        assertEquals(GemmaRequestAdmission.ACCEPTED, registry.register(1, active))

        assertEquals(listOf(active), registry.close())
        assertEquals(GemmaRequestAdmission.CLOSED, registry.register(2, Any()))
        assertNull(registry.resetThrough(2))
    }

    @Test
    fun earlyCancellationTombstonesAreBounded() {
        val registry = GemmaRequestRegistry<Any>(
            maxPendingRequests = 4,
            maxEarlyCancellations = 2,
        )
        registry.cancel(1)
        registry.cancel(2)
        registry.cancel(3)

        assertEquals(GemmaRequestAdmission.ACCEPTED, registry.register(1, Any()))
        assertEquals(GemmaRequestAdmission.OBSOLETE, registry.register(2, Any()))
        assertEquals(GemmaRequestAdmission.OBSOLETE, registry.register(3, Any()))
    }
}
