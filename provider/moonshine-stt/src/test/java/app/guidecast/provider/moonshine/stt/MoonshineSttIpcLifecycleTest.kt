package app.guidecast.provider.moonshine.stt

import app.guidecast.core.translation.NativeColdLoadTicket
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonshineSttIpcLifecycleTest {
    @Test
    fun `preparation ticket releases only on worker terminal and only once`() {
        val ticket = RecordingNativeColdLoadTicket()
        val request = MoonshineSttPreparationRequest(CompletableDeferred(), ticket)

        request.transferToNative()
        assertEquals(1, ticket.transfers)
        assertEquals(0, ticket.completions)

        request.finishNative()
        request.finishNative()
        assertEquals(1, ticket.completions)
    }

    @Test
    fun `cancellation before submission aborts while submitted request waits for terminal`() {
        val beforeTicket = RecordingNativeColdLoadTicket()
        val before = MoonshineSttPreparationRequest(CompletableDeferred(), beforeTicket)
        assertTrue(before.abortBeforeSubmission())
        assertEquals(1, beforeTicket.completions)

        val submittedTicket = RecordingNativeColdLoadTicket()
        val submitted = MoonshineSttPreparationRequest(CompletableDeferred(), submittedTicket)
        submitted.transferToNative()
        assertFalse(submitted.abortBeforeSubmission())
        assertEquals(0, submittedTicket.completions)
        submitted.finishNative()
        assertEquals(1, submittedTicket.completions)
    }

    @Test
    fun `native release requires both preparation and recognition to be idle`() {
        assertEquals(
            MoonshineSttNativeReleaseResult.RELEASED,
            moonshineSttNativeReleaseDecision(
                expectedUseEpoch = 7,
                currentUseEpoch = 7,
                pendingPreparationCount = 0,
                activeRecognitionCount = 0,
            ),
        )
        assertEquals(
            MoonshineSttNativeReleaseResult.BUSY,
            moonshineSttNativeReleaseDecision(
                expectedUseEpoch = 7,
                currentUseEpoch = 7,
                pendingPreparationCount = 1,
                activeRecognitionCount = 0,
            ),
        )
        assertEquals(
            MoonshineSttNativeReleaseResult.BUSY,
            moonshineSttNativeReleaseDecision(
                expectedUseEpoch = 7,
                currentUseEpoch = 7,
                pendingPreparationCount = 0,
                activeRecognitionCount = 1,
            ),
        )
        assertEquals(
            MoonshineSttNativeReleaseResult.BUSY,
            moonshineSttNativeReleaseDecision(
                expectedUseEpoch = 7,
                currentUseEpoch = 7,
                pendingPreparationCount = 1,
                activeRecognitionCount = 1,
            ),
        )
        assertEquals(
            MoonshineSttNativeReleaseResult.SUPERSEDED,
            moonshineSttNativeReleaseDecision(
                expectedUseEpoch = 7,
                currentUseEpoch = 8,
                pendingPreparationCount = 0,
                activeRecognitionCount = 0,
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `native release rejects an invalid negative usage count`() {
        moonshineSttNativeReleaseDecision(
            expectedUseEpoch = 0,
            currentUseEpoch = 0,
            pendingPreparationCount = -1,
            activeRecognitionCount = 0,
        )
    }

    @Test
    fun `hot recognition refuses an unloaded runtime instead of cold loading it`() {
        val missing = runCatching {
            requirePreparedMoonshineSttRuntime<FakeRuntime>(null, FakeRuntime::loaded)
        }.exceptionOrNull()
        val unloaded = runCatching {
            requirePreparedMoonshineSttRuntime(FakeRuntime(loaded = false), FakeRuntime::loaded)
        }.exceptionOrNull()
        val prepared = FakeRuntime(loaded = true)

        assertTrue(missing is MoonshineSttNativePreparationRequiredException)
        assertTrue(unloaded is MoonshineSttNativePreparationRequiredException)
        assertTrue(
            prepared === requirePreparedMoonshineSttRuntime(prepared, FakeRuntime::loaded),
        )
    }

    @Test
    fun `ready status is invalidated when its native worker is no longer live`() {
        val ready = MoonshineSpeechLanguageStatus(
            readiness = MoonshineSpeechLanguageReadiness.READY,
            message = "streaming ready",
            progress = 1f,
        )
        val failed = MoonshineSpeechLanguageStatus(
            readiness = MoonshineSpeechLanguageReadiness.FAILED,
            message = "worker died",
        )

        assertTrue(moonshineSttStatusForWorkerLiveness(ready, workerAlive = true) === ready)
        val reclaimed = moonshineSttStatusForWorkerLiveness(ready, workerAlive = false)
        assertEquals(MoonshineSpeechLanguageReadiness.NATIVE_RESTART_REQUIRED, reclaimed.readiness)
        assertFalse(reclaimed.isReady)
        assertTrue(moonshineSttStatusForWorkerLiveness(failed, workerAlive = false) === failed)
    }

    @Test
    fun `cancelled binding cannot start after its first cleanup ran`() {
        val attempt = MoonshineSttBindingAttemptState()

        assertTrue(attempt.mayStartBinding(continuationActive = true))
        attempt.cancel()

        assertFalse(attempt.mayStartBinding(continuationActive = true))
        assertFalse(attempt.mayStartBinding(continuationActive = false))
        assertTrue(attempt.wasCancelled)
    }

    @Test
    fun `stop tombstone survives until late start observes it and remains bounded`() {
        val cancelled = MoonshineSttCancelledSessions(maximumEntries = 2)
        cancelled.mark(10L)

        assertTrue(10L in cancelled)
        assertTrue(cancelled.remove(10L))
        assertFalse(10L in cancelled)

        cancelled.mark(20L)
        cancelled.mark(21L)
        cancelled.mark(22L)
        assertFalse(20L in cancelled)
        assertTrue(21L in cancelled)
        assertTrue(22L in cancelled)
        assertTrue(cancelled.size() == 2)
    }

    private class RecordingNativeColdLoadTicket : NativeColdLoadTicket {
        var transfers = 0
        var completions = 0

        override fun transferToNative() {
            transfers += 1
        }

        override fun completeNative() {
            completions += 1
        }

        override fun close() = Unit
    }

    private data class FakeRuntime(val loaded: Boolean)
}
