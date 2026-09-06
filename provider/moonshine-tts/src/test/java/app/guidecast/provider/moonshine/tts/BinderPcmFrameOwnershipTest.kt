package app.guidecast.provider.moonshine.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BinderPcmFrameOwnershipTest {
    @Test
    fun validBinderPcmKeepsTheClientOwnedArrayWithoutASecondCopy() {
        val binderOwned = ByteArray(640) { index -> (index % 127).toByte() }

        val frame = binderOwned.toBinderPcmAudioFrame(
            capturedAtElapsedRealtimeNanos = 42L,
            maximumFrameBytes = 4_096,
        )

        assertSame(binderOwned, frame?.bytes)
        assertEquals(42L, frame?.capturedAtElapsedRealtimeNanos)
    }

    @Test
    fun invalidBinderPcmNeverEntersTheStreamingQueue() {
        assertNull(
            ByteArray(0).toBinderPcmAudioFrame(
                capturedAtElapsedRealtimeNanos = 1L,
                maximumFrameBytes = 4_096,
            ),
        )
        assertNull(
            ByteArray(3).toBinderPcmAudioFrame(
                capturedAtElapsedRealtimeNanos = 1L,
                maximumFrameBytes = 4_096,
            ),
        )
        assertNull(
            ByteArray(4_098).toBinderPcmAudioFrame(
                capturedAtElapsedRealtimeNanos = 1L,
                maximumFrameBytes = 4_096,
            ),
        )
    }
}
