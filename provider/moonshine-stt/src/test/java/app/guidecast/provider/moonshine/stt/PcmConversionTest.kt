package app.guidecast.provider.moonshine.stt

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmConversionTest {
    @Test
    fun convertsSigned16BitLittleEndianToNormalizedFloat() {
        val pcm = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(Short.MIN_VALUE)
            .putShort(-16_384)
            .putShort(0)
            .putShort(Short.MAX_VALUE)
            .array()

        assertArrayEquals(
            floatArrayOf(-1f, -0.5f, 0f, 32_767f / 32_768f),
            pcm.toNormalizedFloatPcm(),
            0.00001f,
        )
    }

    @Test
    fun splitsPcmOnCompleteSamplesAndPreservesEveryByte() {
        val pcm = ByteArray(22) { index -> index.toByte() }

        val chunks = pcm.asBinderSafePcmChunks(maximumBytes = 7).toList()

        assertEquals(listOf(6, 6, 6, 4), chunks.map(ByteArray::size))
        assertArrayEquals(pcm, chunks.fold(byteArrayOf()) { result, chunk -> result + chunk })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialPcmSamplesBeforeBinderDelivery() {
        byteArrayOf(1, 2, 3).asBinderSafePcmChunks().toList()
    }

    @Test
    fun `matches nio reference across all 65536 signed 16-bit values`() {
        val allShorts = ShortArray(65_536) { index -> (index - 32_768).toShort() }
        val pcm = ByteBuffer.allocate(65_536 * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                for (s in allShorts) putShort(s)
            }
            .array()

        val actual = pcm.toNormalizedFloatPcm()

        val expectedBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val expected = FloatArray(expectedBuffer.remaining()) { expectedBuffer.get().toFloat() / 32_768f }

        assertEquals(65_536, actual.size)
        for (i in 0 until 65_536) {
            assertEquals(
                "Mismatch at short value ${allShorts[i]}",
                java.lang.Float.floatToRawIntBits(expected[i]),
                java.lang.Float.floatToRawIntBits(actual[i]),
            )
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects odd byte length pcm in toNormalizedFloatPcm`() {
        byteArrayOf(1, 2, 3).toNormalizedFloatPcm()
    }

    @Test
    fun `handles empty pcm array gracefully in toNormalizedFloatPcm`() {
        val empty = ByteArray(0).toNormalizedFloatPcm()
        assertEquals(0, empty.size)
    }
}
