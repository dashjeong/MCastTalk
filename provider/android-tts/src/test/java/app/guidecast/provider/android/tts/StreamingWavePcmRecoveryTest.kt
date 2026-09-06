package app.guidecast.provider.android.tts

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingWavePcmRecoveryTest {
    @Test
    fun `streaming recovery preserves exact suffix and pads only its final frame`() = runBlocking {
        val pcm = ByteArray(1_750) { index -> (index * 31).toByte() }
        val input = TrackingWaveInput(pcm16Wave(pcm, sampleRateHz = 16_000))

        val frames = streamRecoveredWavePcmFrames(
            file = File("ignored.wav"),
            outputSampleRateHz = 16_000,
            frameBytes = 640,
            startByteOffset = 640,
            openInput = { input },
        ).toList()

        val actual = frames.fold(ByteArray(0)) { joined, frame -> joined + frame }
        val expectedSuffix = pcm.copyOfRange(640, pcm.size)
        val expected = expectedSuffix.copyOf(((expectedSuffix.size + 639) / 640) * 640)
        assertArrayEquals(expected, actual)
        assertEquals(listOf(640, 640), frames.map(ByteArray::size))
        assertTrue(input.maxReadLength.get() <= STREAM_READ_BYTES)
        assertTrue(input.closed)
    }

    @Test
    fun `five simultaneous recoveries retain only bounded source reads`() = runBlocking {
        val pcmBytes = 512 * 1024
        val wave = pcm16Wave(ByteArray(pcmBytes) { index -> (index * 17).toByte() }, 16_000)
        val inputs = List(5) { TrackingWaveInput(wave) }

        val emittedBytes = inputs.map { input ->
            async(Dispatchers.Default) {
                var total = 0L
                streamRecoveredWavePcmFrames(
                    file = File("ignored.wav"),
                    outputSampleRateHz = 16_000,
                    frameBytes = 640,
                    startByteOffset = 0,
                    openInput = { input },
                ).collect { frame ->
                    assertEquals(640, frame.size)
                    total += frame.size
                }
                total
            }
        }.awaitAll()

        val expectedBytes = ((pcmBytes + 639L) / 640L) * 640L
        assertEquals(List(5) { expectedBytes }, emittedBytes)
        inputs.forEach { input ->
            assertTrue(input.maxReadLength.get() <= STREAM_READ_BYTES)
            assertTrue(input.readCalls.get() > 30)
            assertTrue(input.closed)
        }
    }

    @Test
    fun `chunked stereo resampling matches established whole wave decoder byte for byte`() =
        runBlocking {
            val stereoPcm = ByteArray(STREAM_READ_BYTES * 2 + 1_236)
            var offset = 0
            var sample = -15_000
            while (offset < stereoPcm.size) {
                stereoPcm[offset] = sample.toByte()
                stereoPcm[offset + 1] = (sample shr 8).toByte()
                stereoPcm[offset + 2] = (-sample).toByte()
                stereoPcm[offset + 3] = ((-sample) shr 8).toByte()
                sample = (sample + 137).coerceAtMost(15_000)
                offset += 4
            }
            val wave = pcm16Wave(stereoPcm, sampleRateHz = 8_000, channelCount = 2)
            val input = TrackingWaveInput(wave)
            val expectedPcm = decodeWaveTtsOutput(wave, outputSampleRateHz = 16_000)
            val expected = expectedPcm.copyOf(((expectedPcm.size + 639) / 640) * 640)

            val actual = streamRecoveredWavePcmFrames(
                file = File("ignored.wav"),
                outputSampleRateHz = 16_000,
                frameBytes = 640,
                startByteOffset = 0,
                openInput = { input },
            ).toList().fold(ByteArray(0)) { joined, frame -> joined + frame }

            assertArrayEquals(expected, actual)
            assertTrue(input.maxReadLength.get() <= STREAM_READ_BYTES)
        }

    @Test
    fun `downstream cancellation closes the recovery file immediately`() = runBlocking {
        val input = TrackingWaveInput(pcm16Wave(ByteArray(64 * 1024) { 7 }, 16_000))

        streamRecoveredWavePcmFrames(
            file = File("ignored.wav"),
            outputSampleRateHz = 16_000,
            frameBytes = 640,
            startByteOffset = 0,
            openInput = { input },
        ).take(1).collect()

        assertTrue(input.closed)
        assertTrue(input.readCalls.get() < 10)
    }

    private class TrackingWaveInput(private val bytes: ByteArray) : SeekableWaveInput {
        override val length: Long = bytes.size.toLong()
        val maxReadLength = AtomicInteger(0)
        val readCalls = AtomicInteger(0)
        @Volatile var closed = false
        private var position = 0

        override fun seek(position: Long) {
            require(position in 0..bytes.size.toLong())
            this.position = position.toInt()
        }

        override fun readFully(destination: ByteArray, offset: Int, length: Int) {
            check(!closed)
            require(length >= 0 && position + length <= bytes.size)
            bytes.copyInto(destination, offset, position, position + length)
            position += length
            readCalls.incrementAndGet()
            maxReadLength.updateAndGet { previous -> maxOf(previous, length) }
        }

        override fun close() {
            closed = true
        }
    }

    private fun pcm16Wave(
        pcm: ByteArray,
        sampleRateHz: Int,
        channelCount: Int = 1,
    ): ByteArray {
        val blockAlign = channelCount * 2
        require(channelCount in 1..2 && pcm.size % blockAlign == 0)
        val output = ByteArray(44 + pcm.size)
        output.writeAscii(0, "RIFF")
        output.writeLittleEndianInt(4, output.size - 8)
        output.writeAscii(8, "WAVE")
        output.writeAscii(12, "fmt ")
        output.writeLittleEndianInt(16, 16)
        output.writeLittleEndianShort(20, 1)
        output.writeLittleEndianShort(22, channelCount)
        output.writeLittleEndianInt(24, sampleRateHz)
        output.writeLittleEndianInt(28, sampleRateHz * blockAlign)
        output.writeLittleEndianShort(32, blockAlign)
        output.writeLittleEndianShort(34, 16)
        output.writeAscii(36, "data")
        output.writeLittleEndianInt(40, pcm.size)
        pcm.copyInto(output, 44)
        return output
    }

    private fun ByteArray.writeAscii(offset: Int, value: String) {
        value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() }
    }

    private fun ByteArray.writeLittleEndianShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}
