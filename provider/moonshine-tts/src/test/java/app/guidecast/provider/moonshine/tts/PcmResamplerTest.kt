package app.guidecast.provider.moonshine.tts

import ai.moonshine.voice.TtsSynthesisResult
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {
    @Test
    fun `preserves duration and signal when converting 22050 to 24000`() {
        val inputRate = 22_050
        val outputRate = 24_000
        val source = FloatArray(inputRate) { index ->
            sin(2.0 * PI * 440.0 * index / inputRate).toFloat()
        }

        val output = resampleLinear(source, inputRate, outputRate)

        assertEquals(outputRate, output.size)
        assertTrue(output.maxOf { kotlin.math.abs(it) } > 0.95f)
        assertTrue(kotlin.math.abs(positiveZeroCrossings(output) - 440) <= 1)
    }

    @Test
    fun `copies matching rate without changing samples`() {
        val source = floatArrayOf(-1f, -0.25f, 0f, 0.5f, 1f)
        val output = resampleLinear(source, 24_000, 24_000)
        assertTrue(source.contentEquals(output))
    }

    @Test
    fun `normalizes native synthesis using its reported sample rate`() {
        val sourceRate = 22_050
        val source = FloatArray(sourceRate) { index ->
            sin(2.0 * PI * 440.0 * index / sourceRate).toFloat()
        }
        val nativeResult = TtsSynthesisResult(source, sourceRate)

        val output = normalizeSynthesisResult(nativeResult, 24_000)

        assertEquals(24_000, output.size)
        assertTrue(output.maxOf { kotlin.math.abs(it) } > 0.95f)
        assertTrue(kotlin.math.abs(positiveZeroCrossings(output) - 440) <= 1)
    }

    @Test
    fun `streaming samples are resampled encoded little endian and emitted as 20ms frames`() =
        runBlocking {
            val source = FloatArray(600) { index ->
                when (index % 4) {
                    0 -> -1f
                    1 -> -0.5f
                    2 -> 0.5f
                    else -> 1f
                }
            }
            val normalized = resampleLinear(source, 12_000, 24_000)
            val frames = mutableListOf<ByteArray>()
            val totalBytes = emitFloatSamplesAsPcm16LeFrames(
                samples = source,
                sourceRateHz = 12_000,
                targetRateHz = 24_000,
                emittedBytes = 0L,
                maxOutputBytes = Long.MAX_VALUE,
                onFrame = frames::add,
            )
            val expectedPcm = referenceFloatSamplesToPcm16Le(normalized)

            assertEquals(1_200, normalized.size)
            assertEquals(listOf(960, 960, 480), frames.map(ByteArray::size))
            assertArrayEquals(byteArrayOf(1, -128), frames.first().copyOfRange(0, 2))
            assertTrue(frames.any { frame -> frame.any { it.toInt() != 0 } })
            assertEquals(expectedPcm.size.toLong(), totalBytes)
            assertArrayEquals(expectedPcm, concatenate(frames))
        }

    @Test
    fun `direct streaming encoder is bit identical to prior phrase encoder`() = runBlocking {
        val source = FloatArray(1_001) { index ->
            when (index % 9) {
                0 -> -2f
                1 -> -1f
                2 -> -0.50001f
                3 -> -0.00001f
                4 -> 0f
                5 -> 0.00001f
                6 -> 0.50001f
                7 -> 1f
                else -> 2f
            }
        }
        val frames = mutableListOf<ByteArray>()

        val totalBytes = emitFloatSamplesAsPcm16LeFrames(
            samples = source,
            sourceRateHz = 24_000,
            targetRateHz = 24_000,
            emittedBytes = 4_096L,
            maxOutputBytes = 8_192L,
            onFrame = frames::add,
        )

        assertEquals(listOf(960, 960, 82), frames.map(ByteArray::size))
        assertEquals(4_096L + source.size * Short.SIZE_BYTES, totalBytes)
        assertArrayEquals(referenceFloatSamplesToPcm16Le(source), concatenate(frames))
    }

    @Test
    fun `streaming output limit is checked before publishing any frame`() = runBlocking {
        val source = FloatArray(481) { 0.25f }
        var publishedFrames = 0

        val error = runCatching {
            emitFloatSamplesAsPcm16LeFrames(
                samples = source,
                sourceRateHz = 12_000,
                targetRateHz = 24_000,
                emittedBytes = Long.MAX_VALUE - 1L,
                maxOutputBytes = Long.MAX_VALUE,
                onFrame = { publishedFrames++ },
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Moonshine TTS audio exceeds the safe IPC size", error?.message)
        assertEquals(0, publishedFrames)
    }

    private fun positiveZeroCrossings(samples: FloatArray): Int =
        samples.asSequence().zipWithNext().count { (left, right) -> left <= 0f && right > 0f }

    private fun referenceFloatSamplesToPcm16Le(samples: FloatArray): ByteArray {
        val output = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { floatSample ->
            val sample = (floatSample.coerceIn(-1f, 1f) * 32_767f)
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(sample.toShort())
        }
        return output.array()
    }

    private fun concatenate(frames: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            frames.forEach(output::write)
        }.toByteArray()
}
