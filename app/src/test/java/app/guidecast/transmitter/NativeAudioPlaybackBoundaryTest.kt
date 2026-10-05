package app.guidecast.transmitter

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NativeAudioPlaybackBoundaryTest {
    @Test fun staleCancellationCannotFlushAnIdenticallyNumberedNewSessionOutput() {
        val oldRetired = NativeAudioRetiredTurns()
        val newRetired = NativeAudioRetiredTurns()
        val newBoundary = NativeAudioPlaybackBoundary(0)
        assertEquals(960, newBoundary.write(100, 0, 0) { 960 })
        var current = true
        var flushed = false
        cancelNativeAudioOutputTurn(100, oldRetired,
            discard = { current = false },
            flush = { sequence -> newBoundary.flushIfMatches(sequence, 1, { current }) { flushed = true } },
            isCurrent = { current })
        assertFalse(flushed)
        assertFalse(oldRetired.allows(100)); assertTrue(newRetired.allows(100))
        assertEquals(100L, newBoundary.sequence)
    }

    @Test fun alreadyReplacedSessionCannotRetireOrDiscardAnotherOutput() {
        val retired = NativeAudioRetiredTurns()
        cancelNativeAudioOutputTurn(100, retired,
            discard = { fail("Stale session discarded audio") },
            flush = { fail("Stale session flushed audio") }, isCurrent = { false })
        assertTrue(retired.allows(100))
    }

    @Test fun waitingCurrentTurnCancellationDoesNotFlushUnplayedCompletedPriorTurn() {
        val boundary = NativeAudioPlaybackBoundary(0)
        val retired = NativeAudioRetiredTurns()
        var writes = 0
        fun write(sequence: Long, head: Int) = retired.writeIfAllowed(sequence) {
            boundary.write(sequence, 0, head) { writes++; 960 }
        }
        assertEquals(960, write(100, 0))
        assertEquals(0, write(101, 240))
        var flushes = 0
        cancelNativeAudioOutputTurn(101, retired, {}, { sequence ->
            boundary.flushIfMatches(sequence, 1) { flushes++ }
        })
        assertEquals(0, flushes)
        assertEquals(100L, boundary.sequence)
        assertEquals(0, write(101, 480))
        assertEquals(0, write(102, 479))
        assertEquals(960, write(102, 480))
        assertEquals(2, writes)
    }

    @Test fun currentTurnDeviceFlushCannotIncludePriorTurnSamples() {
        val boundary = NativeAudioPlaybackBoundary(0)
        val retired = NativeAudioRetiredTurns()
        assertEquals(960, boundary.write(100, 0, 0) { 960 })
        assertEquals(0, boundary.write(101, 0, 479) { fail("Previous turn not drained"); 960 })
        assertEquals(960, boundary.write(101, 0, 480) { 960 })
        var epoch = 0L
        var flushed = false
        cancelNativeAudioOutputTurn(101, retired, {}, { sequence ->
            boundary.flushIfMatches(sequence, 1) { epoch++; flushed = true }
        })
        assertTrue(flushed); assertEquals(1L, epoch)
        assertNull(boundary.sequence)
        assertEquals(0, retired.writeIfAllowed(101) { fail("Retired turn wrote again"); 960 })
        assertEquals(960, boundary.write(102, epoch, 0) { 960 })
    }

    @Test fun flushResetMustBeObservedBeforeAnotherTurnCanUseAnOldSampleHead() {
        val boundary = NativeAudioPlaybackBoundary(0)
        assertEquals(960, boundary.write(100, 0, 0) { 960 })
        boundary.flushIfMatches(100, 1) {}
        assertEquals(0, boundary.write(101, 1, 480) { fail("Old head accepted"); 960 })
        assertEquals(960, boundary.write(101, 1, 0) { 960 })
        assertEquals(0, boundary.write(102, 1, 479) { fail("Current turn not drained"); 960 })
        assertEquals(960, boundary.write(102, 1, 480) { 960 })
    }

    @Test fun primingSilenceCountsTowardTheSampleBoundary() {
        val boundary = NativeAudioPlaybackBoundary(0)
        boundary.recordPriming(120)
        assertEquals(960, boundary.write(100, 0, 0) { 960 })
        assertEquals(0, boundary.write(101, 0, 480) { fail("Priming samples ignored"); 960 })
        assertEquals(960, boundary.write(101, 0, 600) { 960 })
    }

    @Test fun backwardPlayheadObservationDoesNotReleaseTheNextOutput() {
        val boundary = NativeAudioPlaybackBoundary(0)
        assertEquals(960, boundary.write(100, 0, 0) { 960 })
        assertEquals(0, boundary.write(101, 0, 400) { fail("Premature next turn"); 960 })
        assertEquals(0, boundary.write(101, 0, 300) { fail("Backward head accepted"); 960 })
        assertEquals(960, boundary.write(101, 0, 480) { 960 })
    }

    @Test fun unsignedPlayheadWrapKeepsLongSessionBoundaries() {
        val boundary = NativeAudioPlaybackBoundary(0)
        boundary.recordPriming(Int.MAX_VALUE); boundary.recordPriming(Int.MAX_VALUE)
        assertEquals(4, boundary.write(100, 0, Int.MAX_VALUE) { 4 })
        assertEquals(0, boundary.write(100, 0, -2) { 0 })
        assertEquals(4, boundary.write(101, 0, 0) { 4 })
    }

    @Test fun retirementAndNonblockingWriteAdmissionHaveOneOrderedBoundary() {
        val retired = NativeAudioRetiredTurns()
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelRequested = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val written = java.util.concurrent.atomic.AtomicInteger(-1)
        val writer = thread {
            written.set(retired.writeIfAllowed(101) {
                admitted.countDown(); check(release.await(3, TimeUnit.SECONDS)); 960
            })
        }
        assertTrue(admitted.await(3, TimeUnit.SECONDS))
        val canceller = thread {
            cancelRequested.countDown(); retired.retire(101); cancelled.countDown()
        }
        try {
            assertTrue(cancelRequested.await(3, TimeUnit.SECONDS))
            assertFalse(cancelled.await(50, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); writer.join(3000); canceller.join(3000) }
        assertFalse(writer.isAlive); assertFalse(canceller.isAlive)
        assertEquals(960, written.get()); assertEquals(0L, cancelled.count)
        assertEquals(0, retired.writeIfAllowed(101) { fail("Post-cancel write admitted"); 960 })
        assertEquals(960, retired.writeIfAllowed(100) { 960 })
    }

    @Test fun explicitStopStillFlushesAndCurrentSessionCheckBlocksAllFollowingWrites() = runTest {
        val boundary = NativeAudioPlaybackBoundary(0)
        assertEquals(960, boundary.write(100, 0, 0) { 960 })
        var active = true
        var flushed = false
        boundary.flushIfMatches(null, 1) { active = false; flushed = true }
        var written = 0
        assertFalse(writeLocalMonitorPcm(ByteArray(960), { active }, { _, _, count -> written += count; count }))
        assertTrue(flushed); assertEquals(0, written)
    }
}
