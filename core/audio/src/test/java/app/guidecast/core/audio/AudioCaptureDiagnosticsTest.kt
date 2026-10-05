package app.guidecast.core.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioCaptureDiagnosticsTest {
    @Test fun partialReadsCarryOddSampleAndIgnoreUnusedBufferBytes() {
        val assembler = Pcm16ReadAssembler(); val output = ByteArrayOutputStream()
        val source = ByteArray(1001) { (it % 251).toByte() }
        var offset = 0
        for (count in listOf(1, 7, 2, 13, 9, 969)) {
            val buffer = ByteArray(count + 12) { 99 }
            source.copyInto(buffer, 0, offset, offset + count)
            output.write(assembler.accept(buffer, count)); offset += count
        }
        assertArrayEquals(source.copyOf(1000), output.toByteArray())
        assertEquals(1, assembler.discardTail()); assertEquals(0, assembler.pendingBytes)
        assertEquals(0, assembler.accept(byteArrayOf(8), 0).size)
    }
    @Test fun arbitraryShortReadsResample48kTo16kWithoutOrderChange() {
        val input = PcmSineWaveGenerator(48_000, 900.0).nextFrame(100)
        fun filter() = object : NoiseFrameFilter {
            override fun process(frame: FloatArray) = Unit
            override fun close() = Unit
        }
        val expected = RnNoiseMicrophoneProcessor(filter()).use { it.process(input) }
        val assembler = Pcm16ReadAssembler(); val actual = ByteArrayOutputStream()
        RnNoiseMicrophoneProcessor(filter()).use { processor ->
            var offset = 0
            while (offset < input.size) {
                val count = minOf(37, input.size - offset)
                actual.write(processor.process(assembler.accept(input.copyOfRange(offset, offset + count), count)))
                offset += count
            }
            assertEquals(0, processor.pendingInputBytes()); assertEquals(0, assembler.pendingBytes)
        }
        assertEquals(3200, actual.size()); assertArrayEquals(expected, actual.toByteArray())
    }
    @Test fun observationsDistinguishUnknownSilentReadsAndDiscardedInputTail() {
        val tracker = AudioCaptureDiagnosticTracker(AudioCaptureDiagnosticSnapshot(session=1, state="RECORDING"))
        assertNull(tracker.snapshot().raw.rms); assertNull(tracker.snapshot().routedDeviceType)
        tracker.beginRead(90); tracker.read(ByteArray(3), 20, 100); tracker.emitted(ByteArray(2))
        tracker.beginRead(105); tracker.emptyRead(false, 110)
        tracker.policy(true, true); tracker.route(7); tracker.close(1, 120)
        val value = tracker.snapshot()
        assertEquals(2L, value.readCalls); assertEquals(1L, value.shortReads); assertEquals(1L, value.oddReads)
        assertEquals(0.0, value.raw.rms!!, 0.0); assertEquals(0L, value.raw.energyFrames)
        assertEquals(true, value.systemMicrophoneMuted); assertEquals(true, value.clientSilenced)
        assertEquals(7, value.routedDeviceType); assertEquals(1, value.discardedPartialSampleBytes)
        assertEquals(120, value.denoiserPendingInputBytes)
        tracker.read(byteArrayOf(0, 100), 2, 200); tracker.route(2)
        assertEquals(value, tracker.snapshot())
    }
    @Test fun captureAndProcessedEnergyRemainSeparateAndWeighted() {
        val tracker = AudioCaptureDiagnosticTracker(AudioCaptureDiagnosticSnapshot(state="RECORDING"))
        tracker.beginRead(5); tracker.read(PcmSineWaveGenerator(48_000, 400.0).nextFrame(20), 1920, 10)
        tracker.emitted(ByteArray(640))
        assertEquals(1L, tracker.snapshot().raw.energyFrames)
        assertEquals(0L, tracker.snapshot().output.energyFrames)
        assertEquals(960L, tracker.snapshot().raw.samples); assertEquals(320L, tracker.snapshot().output.samples)
        tracker.beginRead(15); tracker.emptyRead(true, 20); tracker.close(0, 0)
        assertEquals("FAILED", tracker.snapshot().state); assertEquals(1L, tracker.snapshot().readErrors)
    }
    @Test fun rawSignalPreservesNonzeroSamplesAcrossOddReadBoundaries() {
        fun tracker() = AudioCaptureDiagnosticTracker(AudioCaptureDiagnosticSnapshot(state="RECORDING"))
        val whole = tracker(); whole.beginRead(1); whole.read(byteArrayOf(0, 64, 0, 64), 4, 2)
        val split = tracker(); split.beginRead(1); split.read(byteArrayOf(0), 4, 2)
        split.beginRead(3); split.read(byteArrayOf(64, 0, 64), 4, 4)
        assertEquals(whole.snapshot().raw.samples, split.snapshot().raw.samples)
        assertEquals(whole.snapshot().raw.bytes, split.snapshot().raw.bytes)
        assertEquals(0.5, split.snapshot().raw.rms!!, 0.0)
        assertEquals(whole.snapshot().raw.peak, split.snapshot().raw.peak)
        assertEquals(2L, split.snapshot().oddReads)
    }
    @Test fun zeroOnlyResultsAdvanceHeartbeatWithoutInventingSuccessfulPcm() {
        val tracker = AudioCaptureDiagnosticTracker(AudioCaptureDiagnosticSnapshot(state="RECORDING"))
        tracker.beginRead(10); tracker.emptyRead(false, 20)
        tracker.beginRead(30); tracker.emptyRead(false, 40)
        val empty = tracker.snapshot()
        assertEquals(2L, empty.readCalls); assertEquals(2L, empty.zeroReads)
        assertEquals(30L, empty.lastReadAttemptElapsedNanos); assertEquals(40L, empty.lastReadResultElapsedNanos)
        assertNull(empty.lastReadElapsedNanos); assertEquals(10L, empty.noProgressSinceElapsedNanos)
        assertEquals("ZERO_READ", empty.lastReadResult); assertNull(empty.raw.rms)
        tracker.beginRead(50); tracker.read(byteArrayOf(0,64), 2, 60)
        val resumed = tracker.snapshot()
        assertEquals(60L, resumed.lastReadElapsedNanos); assertNull(resumed.noProgressSinceElapsedNanos)
        assertEquals(0L, resumed.consecutiveEmptyReads); assertEquals("PCM_READ", resumed.lastReadResult)
    }
}
