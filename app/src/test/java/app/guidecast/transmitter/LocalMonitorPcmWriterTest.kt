package app.guidecast.transmitter

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalMonitorPcmWriterTest {
    @Test fun partialWritesAndBackpressureNeverDuplicateSamples() = runBlocking {
        val input = ByteArray(16) { it.toByte() }
        val output = mutableListOf<Byte>()
        var calls = 0
        assertTrue(writeLocalMonitorPcm(input, { true }, { bytes, offset, count ->
            if (++calls % 2 == 0) 0 else minOf(4, count).also {
                output.addAll(bytes.slice(offset until offset + it))
            }
        }))
        assertEquals(input.toList(), output)
    }

    @Test fun pauseAndImmediateResumeCannotWritePreviousFrameTail() = runBlocking {
        var epoch = 0
        var bytesWritten = 0
        val frameEpoch = epoch
        val complete = writeLocalMonitorPcm(ByteArray(16), { epoch == frameEpoch }, { _, _, _ ->
            bytesWritten += 4
            epoch++ // pause invalidates this frame even if already resumed on the next write
            4
        })
        assertFalse(complete)
        assertEquals(4, bytesWritten)
    }

    @Test fun stalledOutputFailsLocallyWithinTwoSeconds() = runBlocking {
        var clock = -500L
        try {
            writeLocalMonitorPcm(ByteArray(16), { true }, { _, _, _ -> 0 }, { clock += 500; clock })
            fail("Stalled monitor must not wait forever")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("로컬 모니터만"))
            assertEquals(2_000L, clock)
        }
    }

    @Test fun malformedPcmIsRejectedBeforeOutput() = runBlocking {
        try {
            writeLocalMonitorPcm(ByteArray(3), { true }, { _, _, _ -> error("must not write") })
            fail("Odd S16LE frame must fail")
        } catch (expected: IllegalArgumentException) { }
    }
}
