package app.guidecast.provider.android.stt

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPipeWriterTest {
    @Test
    fun partialWritesAndTemporaryBackpressurePreservePcmBytesInOrder() = runBlocking {
        val bytes = ByteArray(640) { (it * 31).toByte() }
        val written = ByteArrayOutputStream()
        var calls = 0
        var time = 0L
        writeRecognitionPcm(bytes, tryWrite = { offset, count ->
            if (calls++ % 3 == 0) 0 else minOf(count, 17).also {
                written.write(bytes, offset, it)
            }
        }, nowMillis = { time }, waitForCapacity = { time += it })
        assertArrayEquals(bytes, written.toByteArray())
        assertTrue(time > 0)
    }

    @Test
    fun silentReaderStallFailsWithinThreeSeconds() = runBlocking {
        var time = 0L
        var failure: Throwable? = null
        try {
            writeRecognitionPcm(ByteArray(640), tryWrite = { _, _ -> 0 },
                nowMillis = { time }, waitForCapacity = { time += it })
        } catch (error: RecognitionAudioInputStalledException) { failure = error }
        assertTrue(failure is RecognitionAudioInputStalledException)
        assertEquals(3_000L, time)
    }

    @Test
    fun operatorCancellationInterruptsAFullPipeWithoutWaitingForDeadline() = runBlocking {
        val full = CompletableDeferred<Unit>()
        var cancelled = false
        val writer = launch {
            try {
                writeRecognitionPcm(ByteArray(640), tryWrite = { _, _ -> 0 },
                    waitForCapacity = { full.complete(Unit); awaitCancellation() })
            } catch (error: CancellationException) { cancelled = true; throw error }
        }
        full.await()
        writer.cancel()
        writer.join()
        assertTrue(cancelled)
    }

    @Test
    fun eightHoursOfVirtualPcmWithRecurringReaderStallsNeverAccumulatesPendingWrites() = runBlocking {
        val frame = ByteArray(640) { (it % 127).toByte() } // 20 ms, 16 kHz mono PCM16
        var now = 0L
        var frames = 0L
        var recoveries = 0
        var bytesWritten = 0L
        val duration = 8L * 60 * 60 * 1_000
        while (now < duration) {
            val stalled = frames > 0 && frames % 15_000 == 0L // inject the reported five-minute class
            try {
                writeRecognitionPcm(frame, tryWrite = { _, count ->
                    if (stalled) 0 else count.also { bytesWritten += it }
                }, nowMillis = { now }, waitForCapacity = { now += it })
            } catch (_: RecognitionAudioInputStalledException) { recoveries++ }
            now += 20
            frames++
        }
        assertTrue(now >= duration)
        assertTrue(recoveries >= 90)
        assertEquals((frames - recoveries) * frame.size, bytesWritten)
    }
}
