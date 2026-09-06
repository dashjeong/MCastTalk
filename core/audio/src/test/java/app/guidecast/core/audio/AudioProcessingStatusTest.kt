package app.guidecast.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessingStatusTest {
    @Test
    fun `reports only effects actually enabled on the microphone session`() {
        val status = AudioProcessingStatus(
            mode = AudioProcessingMode.MICROPHONE_VOICE,
            noiseSuppressorActive = true,
            automaticGainActive = false,
        )

        assertEquals("잡음 억제 활성", status.operatorSummary)
    }

    @Test
    fun `playback capture is explicitly reported as unprocessed passthrough`() {
        val summary = AudioProcessingStatus(
            mode = AudioProcessingMode.PLAYBACK_PASSTHROUGH,
        ).operatorSummary.orEmpty()

        assertTrue("무가공" in summary)
        assertTrue("미적용" in summary)
    }

    @Test
    fun `inactive capture has no operator summary`() {
        assertNull(AudioProcessingStatus().operatorSummary)
    }

    @Test
    fun `disabled session effects do not claim that OEM capture is raw`() {
        val summary = AudioProcessingStatus(
            mode = AudioProcessingMode.MICROPHONE_VOICE,
        ).operatorSummary.orEmpty()

        assertTrue("단말에 따름" in summary)
        assertTrue("상태 확인 불가" in summary)
    }

    @Test
    fun `off request is not formatted as an active effect or OEM guarantee`() {
        val summary = AudioProcessingStatus(
            mode = AudioProcessingMode.MICROPHONE_VOICE,
            noiseReductionSummary = "앱 소음 감소 끄기 요청 · 단말 입력 처리는 별도",
        ).operatorSummary.orEmpty()

        assertTrue("끄기 요청" in summary)
        assertTrue("단말 입력 처리는 별도" in summary)
        assertFalse("활성" in summary)
    }
}
