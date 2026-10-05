package app.guidecast.transmitter

import app.guidecast.core.stream.LocalAudioBufferSnapshot
import org.junit.Assert.*
import org.junit.Test

class NativeLocalPlaybackProgressTest {
    private val complete = LocalAudioBufferSnapshot(4800, 4800, 4800, 0, 0, 0, 0, 0)
    private fun progress(buffer: LocalAudioBufferSnapshot = complete, written: Long = 4800,
        head: Long? = 2400, unwritten: Long = 0, epochChanged: Boolean = false) =
        NativeLocalPlaybackSnapshot(1, buffer, written, head, unwritten, epochChanged)
    @Test fun acceptedPCMRequiresLastSamplePlayheadBeforeDrain() {
        assertFalse(progress(head = 2399).fullyDrained)
        assertTrue(progress().fullyDrained)
    }
    @Test fun unknownHeadPartialWriteAndChangedEpochCannotPass() {
        assertFalse(progress(head = null).fullyDrained)
        assertFalse(progress(written = 4000, unwritten = 800).fullyDrained)
        assertFalse(progress(epochChanged = true).fullyDrained)
        assertTrue(progress(head = null).countsOnly().isNull("head_samples"))
    }
    @Test fun rejectedOverwrittenCancelledAndQueuedAudioCannotPass() {
        for (buffer in listOf(complete.copy(offeredBytes = 5000, rejectedBytes = 200),
            complete.copy(overwrittenBytes = 200), complete.copy(canceledBytes = 200),
            complete.copy(pendingBytes = 200, pendingFrames = 1))) assertFalse(progress(buffer).fullyDrained)
    }
    @Test fun emptyPlaybackCannotPassAsFullyDrained() {
        assertFalse(progress(complete.copy(offeredBytes = 0, admittedBytes = 0, dequeuedBytes = 0),
            written = 0, head = 0).fullyDrained)
    }
    @Test fun emptyQueueBetweenServerChunksDoesNotMeanProviderCompleted() {
        assertTrue(progress().fullyDrained)
        assertFalse(progress().providerCompleteAndDrained)
        assertTrue(progress().copy(completedProviderBytes = 4800).providerCompleteAndDrained)
    }
    @Test fun lostPublicationCannotPassUsingOnlyLocallyAcceptedBytes() {
        assertFalse(progress().copy(completedProviderBytes = 9600).providerCompleteAndDrained)
        assertTrue(progress().copy(completedProviderBytes = 9600).countsOnly().getLong("completed_provider_bytes") == 9600L)
    }
    @Test fun newServiceOwnerAllowsLowerGenerationAndRejectsLateOldCallbacks() {
        val store = NativeLocalPlaybackProgressStore()
        val old = store.beginOwner()
        store.update(progress().copy(generation = 5, closed = true, playbackOwner = old))
        store.providerCompleted(5, 4800, old)
        assertTrue(store.state.value!!.providerCompleteAndDrained)
        val fresh = store.beginOwner()
        assertNull(store.state.value)
        store.update(progress().copy(generation = 1, playbackHeadSamples = 2399, playbackOwner = fresh))
        store.update(progress().copy(generation = 6, closed = true, playbackOwner = old))
        store.providerCompleted(6, 9600, old)
        assertEquals(1L, store.state.value!!.generation)
        assertEquals(fresh, store.state.value!!.playbackOwner)
        assertNull(store.state.value!!.completedProviderBytes)
        store.providerCompleted(1, 4800, fresh)
        assertFalse(store.state.value!!.providerCompleteAndDrained)
        store.update(progress().copy(generation = 1, playbackOwner = fresh))
        assertTrue(store.state.value!!.providerCompleteAndDrained)
    }
}
