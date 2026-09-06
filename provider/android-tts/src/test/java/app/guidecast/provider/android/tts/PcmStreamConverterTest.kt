package app.guidecast.provider.android.tts

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmStreamConverterTest {
    @Test
    fun `matching format reduces host allocation without changing PCM`() {
        // Android compile stubs omit java.management; reflection keeps this strictly host-only.
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val meter = factory.getMethod("getThreadMXBean").invoke(null)
        val type = Class.forName("com.sun.management.ThreadMXBean")
        org.junit.Assume.assumeTrue(type.getMethod("isThreadAllocatedMemorySupported").invoke(meter) as Boolean)
        type.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(meter, true)
        val readAllocated = type.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val converter = PcmStreamConverter(24_000, AudioFormat.ENCODING_PCM_16BIT, 1, 24_000)
        val input = ByteArray(960) { (it % 127).toByte() }
        // Exact old matching-format allocation path, kept only as a benchmark reference.
        fun previous(): ByteArray {
            val source = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN)
            val output = ByteBuffer.allocate(input.size + 8).order(ByteOrder.LITTLE_ENDIAN)
            output.put(source)
            return output.array().copyOf(output.position())
        }
        repeat(2000) { allocationSink = previous(); allocationSink = converter.convert(input) }
        fun allocated(operation: () -> ByteArray): Long {
            val id = Thread.currentThread().id
            val before = readAllocated.invoke(meter, id) as Long
            repeat(10_000) { allocationSink = operation() }
            return (readAllocated.invoke(meter, id) as Long) - before
        }
        val previousBytes = allocated(::previous)
        val currentBytes = allocated { converter.convert(input) }
        assertArrayEquals(previous(), converter.convert(input))
        println("HOST allocation per 10000 matching 20ms frames: old=$previousBytes new=$currentBytes bytes; not Android PSS")
        org.junit.Assert.assertTrue("The direct copy should remove at least one PCM allocation", currentBytes < previousBytes * 0.7)
    }

    @Test
    fun `matching format preserves every signed sample under odd chunks and buffer reuse`() {
        val input = ByteBuffer.allocate(65_536 * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { (Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()).forEach { putShort(it.toShort()) } }.array()
        val converter = PcmStreamConverter(24_000, AudioFormat.ENCODING_PCM_16BIT, 1, 24_000)
        val output = java.io.ByteArrayOutputStream()
        var offset = 0
        val lengths = intArrayOf(1, 3, 959, 960, 7, 1921)
        var i = 0
        while (offset < input.size) {
            val end = (offset + lengths[i++ % lengths.size]).coerceAtMost(input.size)
            val callbackBuffer = input.copyOfRange(offset, end)
            val converted = converter.convert(callbackBuffer)
            callbackBuffer.fill(0) // A producer is allowed to reuse its callback buffer.
            assertEquals(0, converted.size % 2)
            output.write(converted)
            assertEquals(0, converter.convert(byteArrayOf()).size)
            offset = end
        }
        assertArrayEquals(input, output.toByteArray())
    }

    @Test
    fun `five independent converters do not alias or mix channel PCM`() {
        val converters = List(5) { PcmStreamConverter(16_000, AudioFormat.ENCODING_PCM_16BIT, 1, 16_000) }
        repeat(100) { frame ->
            val results = converters.mapIndexed { channel, converter ->
                converter.convert(shorts((frame + channel * 1000).toShort(), Short.MIN_VALUE, Short.MAX_VALUE))
            }
            results.forEachIndexed { channel, pcm ->
                assertArrayEquals(shorts((frame + channel * 1000).toShort(), Short.MIN_VALUE, Short.MAX_VALUE), pcm)
            }
            results.first().fill(0)
            assertEquals((frame + 1000).toShort(), ByteBuffer.wrap(results[1]).order(ByteOrder.LITTLE_ENDIAN).short)
        }
    }

    @Test
    fun `keeps mono pcm16 samples at matching rate`() {
        val input = shorts(Short.MIN_VALUE, 0, Short.MAX_VALUE)
        val converter = PcmStreamConverter(
            16_000,
            AudioFormat.ENCODING_PCM_16BIT,
            1,
            16_000,
        )

        assertArrayEquals(input, converter.convert(input))
    }

    @Test
    fun `downmixes stereo and downsamples across chunk boundaries`() {
        val converter = PcmStreamConverter(
            32_000,
            AudioFormat.ENCODING_PCM_16BIT,
            2,
            16_000,
        )
        val stereo = shorts(10_000, 10_000, 20_000, 20_000, 30_000, 30_000, 32_000, 32_000)

        val first = converter.convert(stereo.copyOfRange(0, 5))
        val second = converter.convert(stereo.copyOfRange(5, stereo.size))
        val output = ByteBuffer.wrap(first + second).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(4, output.remaining())
        assertEquals(20_000, output.short.toInt())
        assertEquals(32_000, output.short.toInt())
    }

    @Test
    fun `recovers pcm from a wav file when engine omits audio callbacks`() {
        val pcm = shorts(1_000, -2_000, 3_000)
        val wave = ByteBuffer.allocate(44 + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".encodeToByteArray())
                putInt(36 + pcm.size)
                put("WAVE".encodeToByteArray())
                put("fmt ".encodeToByteArray())
                putInt(16)
                putShort(1.toShort())
                putShort(1.toShort())
                putInt(24_000)
                putInt(24_000 * 2)
                putShort(2.toShort())
                putShort(16.toShort())
                put("data".encodeToByteArray())
                putInt(pcm.size)
                put(pcm)
            }
            .array()

        assertArrayEquals(pcm, decodeWaveTtsOutput(wave, 24_000))
    }

    private fun shorts(vararg samples: Short): ByteArray =
        ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach(::putShort) }
            .array()

    private companion object {
        @Volatile var allocationSink: ByteArray? = null
    }
}
