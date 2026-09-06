package app.guidecast.provider.android.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidRecognitionPausePolicyTest {
    @Test
    fun `OEM endpoint hints match the conservative phrase and sentence pause ladder`() {
        assertEquals(900L, AndroidRecognitionPausePolicy.MINIMUM_SPEECH_MILLIS)
        assertEquals(900L, AndroidRecognitionPausePolicy.POSSIBLY_COMPLETE_SILENCE_MILLIS)
        assertEquals(1_200L, AndroidRecognitionPausePolicy.COMPLETE_SILENCE_MILLIS)
        assertTrue(
            AndroidRecognitionPausePolicy.COMPLETE_SILENCE_MILLIS >=
                AndroidRecognitionPausePolicy.POSSIBLY_COMPLETE_SILENCE_MILLIS,
        )
    }
}
