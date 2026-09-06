package app.guidecast.provider.moonshine.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmEmissionPacingTest {
    @Test
    fun `emits 20 millisecond frames in order with real-time pacing`() = runBlocking {
        val fullFrameBytes = 24_000 * TTS_FRAME_MILLIS / 1_000 * Short.SIZE_BYTES
        val source = ByteArray(fullFrameBytes * 2 + fullFrameBytes / 2) { index ->
            (index % 127).toByte()
        }
        val emitted = mutableListOf<ByteArray>()
        val delays = mutableListOf<Long>()
        val eventOrder = mutableListOf<String>()

        emitPacedPcmFrames(
            input = ByteArrayInputStream(source),
            sampleRateHz = 24_000,
            onFrame = { frame ->
                emitted += frame
                eventOrder += "frame:${frame.size}"
            },
            pace = { millis ->
                delays += millis
                eventOrder += "pace:$millis"
            },
        )

        assertEquals(listOf(fullFrameBytes, fullFrameBytes, fullFrameBytes / 2), emitted.map(ByteArray::size))
        assertEquals(listOf(20L, 20L, 20L), delays)
        assertEquals(
            listOf(
                "frame:$fullFrameBytes",
                "pace:20",
                "frame:$fullFrameBytes",
                "pace:20",
                "frame:${fullFrameBytes / 2}",
                "pace:20",
            ),
            eventOrder,
        )
        val reconstructed = ByteArrayOutputStream().also { output ->
            emitted.forEach(output::write)
        }.toByteArray()
        assertArrayEquals(source, reconstructed)
    }
}
