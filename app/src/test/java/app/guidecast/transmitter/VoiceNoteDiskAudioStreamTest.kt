package app.guidecast.transmitter

import app.guidecast.core.stream.PcmAudioFrame
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceNoteDiskAudioStreamTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun channel32TrySendFailsUnderSlowConsumerCausingPermanentStop() = runTest {
        // Reproduces the exact bug in VoiceNoteViewModel:
        // Channel(32) + trySend() fails when consumer is slower than producer
        val frames = Channel<PcmAudioFrame>(32)
        val recognitionStopped = AtomicBoolean(false)
        val processed = mutableListOf<PcmAudioFrame>()

        // Slow consumer coroutine
        val consumerJob = launch {
            try {
                for (frame in frames) {
                    delay(50) // simulate slow recognizer inference
                    processed.add(frame)
                }
            } catch (_: CancellationException) {
                // Cancelled when queue is exhausted
            }
        }

        // Producer: tries to send 40 frames (e.g. fast audio arrival)
        var failedAtFrame = -1
        val dummyBytes = ByteArray(3_200)
        for (i in 0 until 40) {
            val frame = PcmAudioFrame(dummyBytes.copyOf(), System.nanoTime())
            if (frames.trySend(frame).isFailure) {
                failedAtFrame = i
                recognitionStopped.set(true)
                consumerJob.cancel()
                break
            }
        }

        // Channel(32) must fail on the 33rd frame (index 32)
        assertEquals("Channel(32) must fail when full", 32, failedAtFrame)
        assertTrue("recognitionStopped must be triggered on failure", recognitionStopped.get())
        assertTrue("Subsequent frames were permanently lost", processed.size < 40)
    }

    @Test
    fun diskAudioStreamProcessesAllFramesWithoutExhaustionUnderSlowConsumer() = runTest {
        val testFile = File(tempFolder.root, "test-note.wav")
        val originNanos = 1_000_000_000L
        val chunkSize = 3_200 // 100ms of 16kHz S16LE mono
        val totalFrames = 50

        val stream = VoiceNoteDiskAudioStream(testFile, originNanos, chunkSize)
        val receivedFrames = mutableListOf<PcmAudioFrame>()

        VoiceNoteWav(testFile).use { wav ->
            // Consumer collects in background at slow pace
            val consumerJob = launch {
                stream.flow().collect { frame ->
                    delay(10) // slow processing
                    receivedFrames.add(frame)
                }
            }

            // Producer writes 50 frames to disk without blocking
            val samplePayload = ByteArray(chunkSize) { (it % 120).toByte() }
            for (i in 0 until totalFrames) {
                wav.append(samplePayload, samplePayload.size)
                stream.onBytesCommitted(wav.byteCount)
            }
            stream.finishWriting()

            consumerJob.join()
        }

        assertEquals("All 50 frames must be delivered without loss", totalFrames, receivedFrames.size)
        // Verify timestamps strictly reflect physical audio position
        for (i in 0 until totalFrames) {
            val expectedOffset = i.toLong() * chunkSize
            val expectedTimestamp = originNanos + expectedOffset * 31_250L
            assertEquals("Timestamp must match physical audio position", expectedTimestamp, receivedFrames[i].capturedAtElapsedRealtimeNanos)
            assertEquals("Frame size must match chunk size", chunkSize, receivedFrames[i].bytes.size)
        }
        stream.close()
    }

    @Test
    fun diskAudioStreamEnforcesSingleCollector() = runTest {
        val testFile = File(tempFolder.root, "single-collector.wav")
        VoiceNoteWav(testFile).use { wav ->
            wav.append(ByteArray(640), 640)
        }
        val stream = VoiceNoteDiskAudioStream(testFile, 0L)
        stream.onBytesCommitted(640L)
        stream.finishWriting()

        // First collector succeeds
        val frames = stream.flow().toList()
        assertEquals(1, frames.size)

        // Second collector must fail
        try {
            stream.flow().toList()
            fail("Second collector must be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Only one recognition collector permitted") == true)
        }
        stream.close()
    }

    @Test
    fun diskAudioStreamCancellationClosesCleanly() = runTest {
        val testFile = File(tempFolder.root, "cancel-test.wav")
        VoiceNoteWav(testFile).use { wav ->
            wav.append(ByteArray(3_200), 3_200)
        }
        val stream = VoiceNoteDiskAudioStream(testFile, 0L)
        stream.onBytesCommitted(3_200L)

        val job = launch {
            stream.flow().collect {
                // Keep collecting
            }
        }
        delay(50)
        job.cancel()
        job.join()
        assertTrue("Job must complete cancelled", job.isCancelled)
        stream.close()
    }

    @Test
    fun diskAudioStreamRejectsTruncatedOrCorruptFile() = runTest {
        val testFile = File(tempFolder.root, "truncated.wav")
        VoiceNoteWav(testFile).use { wav ->
            wav.append(ByteArray(1_600), 1_600)
        }
        val stream = VoiceNoteDiskAudioStream(testFile, 0L)
        // Commit 6400 bytes even though file physically only has 1600 bytes
        stream.onBytesCommitted(6_400L)
        stream.finishWriting()

        try {
            stream.flow().toList()
            fail("Truncated file must throw IOException")
        } catch (e: java.io.IOException) {
            assertTrue(
                "Exception message must mention truncation or corruption: ${e.message}",
                e.message?.contains("truncated or corrupt") == true
            )
        } finally {
            stream.close()
        }
    }

    @Test
    fun diskAudioStreamValidatesChunkSize() {
        val testFile = File(tempFolder.root, "chunk-validation.wav")
        assertThrows(IllegalArgumentException::class.java) {
            VoiceNoteDiskAudioStream(testFile, 0L, chunkSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceNoteDiskAudioStream(testFile, 0L, chunkSize = -100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VoiceNoteDiskAudioStream(testFile, 0L, chunkSize = 3_199) // odd number of bytes
        }
        val valid = VoiceNoteDiskAudioStream(testFile, 0L, chunkSize = 3_200)
        valid.close()
    }

    @Test
    fun diskAudioStreamPropagatesDownstreamExceptionsWithoutSwallowing() = runTest {
        val testFile = File(tempFolder.root, "downstream-exception.wav")
        VoiceNoteWav(testFile).use { wav ->
            wav.append(ByteArray(3_200), 3_200)
        }
        val stream = VoiceNoteDiskAudioStream(testFile, 0L)
        stream.onBytesCommitted(3_200L)
        stream.finishWriting()

        class CustomDownstreamException : RuntimeException("Consumer crashed")

        try {
            stream.flow().collect {
                throw CustomDownstreamException()
            }
            fail("Downstream exception must propagate out of flow")
        } catch (_: CustomDownstreamException) {
            // Expected: downstream exception must be transparently propagated
        } finally {
            stream.close()
        }
    }

    @Test
    fun diskAudioStreamWakesIdleCollectorOnClose() = runTest {
        val testFile = File(tempFolder.root, "idle-wake.wav")
        VoiceNoteWav(testFile).use { wav ->
            wav.append(ByteArray(1_600), 1_600)
        }
        val stream = VoiceNoteDiskAudioStream(testFile, 0L)
        stream.onBytesCommitted(1_600L)
        // Note: finishWriting() is NOT called; writer remains idle

        val collected = mutableListOf<PcmAudioFrame>()
        val collectorJob = launch {
            stream.flow().collect { frame ->
                collected.add(frame)
            }
        }

        delay(50) // Allow collector to read 1600 bytes and enter idle suspension waiting for more
        assertEquals(1, collected.size)
        assertTrue("Collector job should still be active waiting for more audio", collectorJob.isActive)

        // Close the stream while collector is idle waiting
        stream.close()

        // Collector must wake up and terminate cleanly without hanging
        collectorJob.join()
        assertFalse("Collector job must finish cleanly after close", collectorJob.isActive)
    }

    @Test
    fun diskAudioStreamPreservesUniquePcmPayloadSequenceAndTimestamps() = runTest {
        val testFile = File(tempFolder.root, "payload-seq.wav")
        val originNanos = 5_000_000_000L
        val chunkSize = 1_600
        val totalFrames = 25

        val stream = VoiceNoteDiskAudioStream(testFile, originNanos, chunkSize)
        val expectedPayloads = List(totalFrames) { frameIndex ->
            ByteArray(chunkSize) { byteOffset ->
                ((frameIndex * 13 + byteOffset) % 256).toByte()
            }
        }

        VoiceNoteWav(testFile).use { wav ->
            for (payload in expectedPayloads) {
                wav.append(payload, payload.size)
                stream.onBytesCommitted(wav.byteCount)
            }
            stream.finishWriting()
        }

        val collectedFrames = stream.flow().toList()
        assertEquals(totalFrames, collectedFrames.size)

        for (i in 0 until totalFrames) {
            val frame = collectedFrames[i]
            val expectedOffset = i.toLong() * chunkSize
            val expectedNanos = originNanos + expectedOffset * 31_250L

            assertEquals("Timestamp for frame $i must match", expectedNanos, frame.capturedAtElapsedRealtimeNanos)
            assertArrayEquals("Payload for frame $i must be identical byte-for-byte", expectedPayloads[i], frame.bytes)
        }
        stream.close()
    }

    @Test
    fun diskAudioStreamRejectsInvalidBytesCommitted() {
        val testFile = File(tempFolder.root, "invalid-committed.wav")
        val stream = VoiceNoteDiskAudioStream(testFile, 0L)
        assertThrows(IllegalArgumentException::class.java) {
            stream.onBytesCommitted(-2L) // negative
        }
        assertThrows(IllegalArgumentException::class.java) {
            stream.onBytesCommitted(3L) // odd
        }
        stream.onBytesCommitted(100L)
        assertThrows(IllegalArgumentException::class.java) {
            stream.onBytesCommitted(50L) // backwards
        }
        stream.close()
    }

    @Test
    fun diskAudioStreamWriterDoneRaceDeliversAllFinalBytes() = runTest {
        val testFile = File(tempFolder.root, "race-test.wav")
        val chunkSize = 1_600
        val stream = VoiceNoteDiskAudioStream(testFile, 0L, chunkSize)
        val collected = mutableListOf<PcmAudioFrame>()

        VoiceNoteWav(testFile).use { wav ->
            // Initial batch of 1 frame
            wav.append(ByteArray(chunkSize) { 1 }, chunkSize)
            stream.onBytesCommitted(wav.byteCount)

            val collectorJob = launch {
                stream.flow().collect { frame ->
                    collected.add(frame)
                    delay(20) // delay consumer
                }
            }

            delay(10) // wait for consumer to read frame 1
            // While consumer is delayed, append final batch and finish writing in rapid succession
            wav.append(ByteArray(chunkSize) { 2 }, chunkSize)
            stream.onBytesCommitted(wav.byteCount)
            stream.finishWriting()

            collectorJob.join()
        }

        assertEquals("Must read both initial and final race-committed frames", 2, collected.size)
        assertEquals(1.toByte(), collected[0].bytes[0])
        assertEquals(2.toByte(), collected[1].bytes[0])
        stream.close()
    }

    @Test
    fun diskAudioStreamDeliveredDurationTracksConsumedOffsetNotForwardClock() = runTest {
        val testFile = File(tempFolder.root, "duration-track.wav")
        val chunkSize = 3_200 // 100ms
        val totalFrames = 10 // 1000ms total
        val stream = VoiceNoteDiskAudioStream(testFile, 0L, chunkSize)

        assertEquals("Initially 0ms delivered", 0L, stream.deliveredDurationMs())

        VoiceNoteWav(testFile).use { wav ->
            for (i in 0 until totalFrames) {
                wav.append(ByteArray(chunkSize), chunkSize)
                stream.onBytesCommitted(wav.byteCount)
            }
            stream.finishWriting()

            // Even though 1,000ms has been written to disk, delivered duration remains 0 before reading
            assertEquals("0ms delivered before flow collection", 0L, stream.deliveredDurationMs())

            var frameCount = 0
            stream.flow().collect {
                frameCount++
                val expectedDeliveredMs = frameCount * 100L
                assertEquals(
                    "deliveredDurationMs must track audio delivered to engine, not producer's clock",
                    expectedDeliveredMs,
                    stream.deliveredDurationMs()
                )
            }
            assertEquals(totalFrames, frameCount)
            assertEquals("1000ms delivered after full collection", 1_000L, stream.deliveredDurationMs())
        }
        stream.close()
    }
}
