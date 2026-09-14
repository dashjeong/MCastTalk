package app.guidecast.transmitter

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking

class FileAudioConversionTest {
    @Test fun elevenMinuteFileConsumesEveryPcmSampleAndRetainsEveryCompletedChunk(): Unit = runBlocking {
        var totalBytes = 0L
        var calls = 0
        var largest = 0
        var expectedFrame = 0L
        val duration = 11 * 60_000L + 340L
        val frames = flow {
            for (time in 0L until duration step 20) {
                val value = (time / 20 % 20_000 + 1_000).toShort()
                val pcm = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN)
                repeat(320) { pcm.putShort(value) }
                emit(FilePcmFrame(pcm.array(), time, time + 20))
            }
        }
        val result = transcribeFileFrames(frames, "en", { duration }) { bytes, start, end, language ->
            totalBytes += bytes.size; calls++; largest = maxOf(largest, bytes.size)
            assertEquals(expectedFrame * 20, start)
            val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            while (input.hasRemaining()) {
                val expected = (expectedFrame % 20_000 + 1_000).toShort()
                repeat(320) { assertEquals(expected, input.short) }
                expectedFrame++
            }
            assertEquals(expectedFrame * 20, end)
            // A segmented engine may return many finalized utterances within each PCM session.
            // More than 60 callback results survive across the full 11-minute file.
            val count = if (bytes.size == 1_920_000) 10 else 1
            (0 until count).map { part ->
                FileSpeechSegment(part.toLong(), start + (end - start) * part / count,
                    start + (end - start) * (part + 1) / count, "fixture $calls/$part", language)
            }
        }
        assertEquals(duration * 32, totalBytes)
        assertEquals(12, calls)
        assertTrue(largest <= 1_920_000)
        assertEquals(111, result.segments.size)
        assertEquals(duration, result.durationMs)
        assertEquals(duration, result.segments.last().endMs)
        assertEquals((0L..110L).toList(), result.segments.map { it.id })
    }

    @Test fun cancellationDuringFileRecognitionDoesNotStartAnotherChunk(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val operation = async {
            transcribeFileFrames(flow {
                for (time in 0L until 120_000L step 20) emit(FilePcmFrame(ByteArray(640) { 20 }, time, time + 20))
            }, "ko", { 120_000 }) { _, _, _, _ ->
                calls++; entered.complete(Unit); awaitCancellation()
            }
        }
        entered.await(); operation.cancel(); operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(1, calls)
    }

    @Test fun stereo48kConvertsToExact16kMonoWithSignalAndNoChannelReordering() {
        val input = ByteBuffer.allocate(48_000 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(48_000) { input.putShort(12_000); input.putShort(4_000) }
        input.flip()
        val output = FilePcmConverter(48_000, 2, AudioFormat.ENCODING_PCM_16BIT).convert(input)
        assertEquals(32_000, output.size)
        val samples = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        while (samples.hasRemaining()) assertEquals(8_000.toShort(), samples.short)
    }

    @Test fun arbitrary44100HzPacketBoundariesPreserveTheSameSamplesAsAWholeBuffer() {
        val input = ByteBuffer.allocate(44_100 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44_100) { input.putShort(((it % 1_000) * 20 - 10_000).toShort()) }
        val bytes = input.array()
        val whole = FilePcmConverter(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
            .convert(ByteBuffer.wrap(bytes))
        val split = FilePcmConverter(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        val parts = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 514, bytes.size)
            parts.write(split.convert(ByteBuffer.wrap(bytes, offset, end - offset)))
            offset = end
        }
        assertEquals(32_000, whole.size)
        assertArrayEquals(whole, parts.toByteArray())
    }

    @Test fun floatingPcmClipsFiniteValuesAndRejectsNanAudio() {
        val input = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(2f).putFloat(-2f).putFloat(Float.NaN).putFloat(0.5f)
        input.flip()
        val output = FilePcmConverter(16_000, 1, AudioFormat.ENCODING_PCM_FLOAT).convert(input)
        val samples = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(32767.toShort(), samples.short)
        assertEquals((-32768).toShort(), samples.short)
        assertEquals(0.toShort(), samples.short)
        assertEquals(16384.toShort(), samples.short)
    }

    @Test fun providerWordStartsUseFileOffsetAndNeverInventEndTimes() {
        val words = validatedFileWordTimes(listOf(FileSpeechWord("one", 40), FileSpeechWord("two", 330)), 60_000, 61_000)
        assertEquals(listOf(60_040L, 60_330L), words.map { it.startMs })
        assertTrue(words.all { it.endMs == null })
        assertTrue(validatedFileWordTimes(listOf(FileSpeechWord("absent", 0)), 100, 1_000).isEmpty())
        assertTrue(validatedFileWordTimes(listOf(FileSpeechWord("late", 2_000)), 100, 1_000).isEmpty())
        assertTrue(validatedFileWordTimes(listOf(FileSpeechWord("overflow", Long.MAX_VALUE)), 100, 1_000).isEmpty())
        assertTrue(validatedFileWordTimes(listOf(FileSpeechWord("back", 200), FileSpeechWord("wards", 100)), 0, 1_000).isEmpty())
    }
}
