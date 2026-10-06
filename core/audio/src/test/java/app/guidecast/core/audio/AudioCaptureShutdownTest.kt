package app.guidecast.core.audio

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureShutdownTest {
    @Test fun aLateBlockingReadFinishesBeforeItsProcessorIsClosed() = runTest {
        val stopSignal = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var filterClosed = false
        val processor = RnNoiseMicrophoneProcessor(object : NoiseFrameFilter {
            override fun process(frame: FloatArray) { check(!filterClosed); events += "process" }
            override fun close() { filterClosed = true; events += "filter-close" }
        })
        val reader = launch {
            try {
                // Like a native read/processing operation, this work can finish after cancel.
                withContext(NonCancellable) { stopSignal.await(); processor.process(ByteArray(960)) }
            } finally { events += "reader-finished" }
        }
        runCurrent()
        shutDownCaptureReadWorker(reader,
            stopRead = { events += "stop-read"; stopSignal.complete(Unit) },
            releaseAfterWorker = { assertTrue(reader.isCompleted); processor.close(); events += "recorder-release" })
        assertTrue(filterClosed)
        assertEquals(listOf("stop-read", "process", "reader-finished", "filter-close", "recorder-release"), events)
        // The processor's closed contract is preserved, not weakened to mask the race.
        try { processor.process(ByteArray(960)); fail("Processing a released filter must stay forbidden") }
        catch (_: IllegalStateException) { }
    }

    @Test fun aCanceledOwnerStillJoinsItsInflightProcessorBeforeRelease() = runTest {
        val finishProcessing = CompletableDeferred<Unit>()
        val processingStarted = CompletableDeferred<Unit>()
        var released = false
        var readerFinished = false
        val reader = launch {
            try { withContext(NonCancellable) { processingStarted.complete(Unit); finishProcessing.await() } }
            finally { readerFinished = true }
        }
        processingStarted.await()
        val owner = launch {
            shutDownCaptureReadWorker(reader, stopRead = {}, releaseAfterWorker = {
                assertTrue(readerFinished); assertTrue(reader.isCompleted); released = true
            })
        }
        runCurrent(); owner.cancel(); runCurrent()
        assertFalse(released)
        finishProcessing.complete(Unit)
        owner.join()
        assertTrue(released)
    }

    @Test fun stopRejectionDoesNotSkipReaderCancellationOrResourceRelease() = runTest {
        val waiting = CompletableDeferred<Unit>()
        var finished = false
        var released = false
        val reader = launch { try { waiting.await() } finally { finished = true } }
        runCurrent()
        shutDownCaptureReadWorker(reader,
            stopRead = { throw IllegalStateException("already stopped") },
            releaseAfterWorker = { assertTrue(finished); assertTrue(reader.isCompleted); released = true })
        assertTrue(released)
    }

    @Test fun realThreadedInflightRnNoiseProcessingCannotRaceItsClose() = runTest {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val filterClosed = AtomicBoolean(false)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val processor = RnNoiseMicrophoneProcessor(object : NoiseFrameFilter {
            override fun process(frame: FloatArray) {
                events += "processing-started"; entered.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
                check(!filterClosed.get()); events += "processing-finished"
            }
            override fun close() { filterClosed.set(true); events += "filter-close" }
        })
        val reader = launch(Dispatchers.Default) {
            try { processor.process(ByteArray(960)) }
            finally { events += "reader-finished" }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            shutDownCaptureReadWorker(reader,
                stopRead = { events += "stop-read"; finish.countDown() },
                releaseAfterWorker = { assertTrue(reader.isCompleted); processor.close() })
            assertTrue(filterClosed.get())
            assertTrue(events.indexOf("reader-finished") < events.indexOf("filter-close"))
            assertTrue(events.indexOf("processing-finished") < events.indexOf("filter-close"))
        } finally {
            finish.countDown(); reader.cancelAndJoin(); processor.close()
        }
    }

    @Test fun aReplacementWaitsUntilCanceledCaptureHasReleasedItsRoute() = runTest {
        val lifetime = AudioCaptureLifetime()
        val releasePrevious = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val previous = launch {
            lifetime.withSession {
                events += "previous-acquired"
                try { CompletableDeferred<Unit>().await() }
                finally { withContext(NonCancellable) { releasePrevious.await(); events += "previous-released" } }
            }
        }
        runCurrent(); previous.cancel(); runCurrent()
        val replacement = launch { lifetime.withSession { events += "replacement-acquired" } }
        runCurrent()
        assertEquals(listOf("previous-acquired"), events)
        releasePrevious.complete(Unit)
        previous.join(); replacement.join()
        assertEquals(listOf("previous-acquired", "previous-released", "replacement-acquired"), events)
    }

    @Test fun cancellationWhileWaitingForCaptureDoesNotAcquireOrUnlockAnotherSession() = runTest {
        val lifetime = AudioCaptureLifetime()
        val releasePrevious = CompletableDeferred<Unit>()
        var active = false
        var canceledAcquired = false
        val previous = launch { lifetime.withSession { active = true; releasePrevious.await(); active = false } }
        runCurrent()
        val waiting = launch { lifetime.withSession { canceledAcquired = true } }
        runCurrent(); waiting.cancelAndJoin()
        assertTrue(active); assertFalse(canceledAcquired)
        var nextAcquired = false
        val next = launch { lifetime.withSession { nextAcquired = true } }
        runCurrent(); assertFalse(nextAcquired)
        releasePrevious.complete(Unit); previous.join(); next.join()
        assertTrue(nextAcquired)
    }

    @Test fun startupFailureReleasesCaptureForAnotherAttempt() = runTest {
        val lifetime = AudioCaptureLifetime()
        try { lifetime.withSession<Unit> { throw IllegalStateException("startup failed") }; fail("Expected startup failure") }
        catch (_: IllegalStateException) { }
        var nextStarted = false
        lifetime.withSession { nextStarted = true }
        assertTrue(nextStarted)
    }

    @Test fun repeatedEmptyReadsYieldAndStopWhenInputIsCanceled() = runTest {
        var reads = 0
        var frames = 0
        val reader = launch {
            while (true) {
                val count = readCapturePcm { reads++; 0 }
                if (count > 0) frames++
            }
        }
        runCurrent(); assertEquals(1, reads)
        advanceTimeBy(9); runCurrent(); assertEquals(1, reads)
        advanceTimeBy(1); runCurrent(); assertEquals(2, reads)
        reader.cancelAndJoin()
        advanceTimeBy(100); runCurrent()
        assertEquals(2, reads); assertEquals(0, frames)
    }

    @Test fun cancellationDuringNativeReadPreventsReturnedBytesFromBeingProcessed() = runTest {
        var processed = false
        lateinit var reader: kotlinx.coroutines.Job
        reader = launch {
            val count = readCapturePcm { reader.cancel(); 960 }
            if (count > 0) processed = true
        }
        reader.join()
        assertFalse(processed)
    }
}
