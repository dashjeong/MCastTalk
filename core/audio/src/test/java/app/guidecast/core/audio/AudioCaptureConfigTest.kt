package app.guidecast.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureConfigTest {
    @Test
    fun `automatic gain is opt-in so room sound is not raised by default`() {
        assertFalse(AudioCaptureConfig().enableAutomaticGain)
    }

    @Test
    fun `off mode requests every controllable platform effect disabled`() {
        val selected = AudioCaptureConfig(
            enableNoiseSuppressor = true,
            enableAutomaticGain = true,
            enableEchoCanceler = true,
            noiseMode = MicrophoneNoiseMode.OFF,
        ).platformMicrophoneEffects()

        assertFalse(selected.enableNoiseSuppressor)
        assertFalse(selected.enableAutomaticGain)
        assertFalse(selected.enableEchoCanceler)
    }

    @Test
    fun `device mode preserves individually requested platform effects`() {
        val selected = AudioCaptureConfig(
            enableNoiseSuppressor = true,
            enableAutomaticGain = true,
            enableEchoCanceler = true,
            noiseMode = MicrophoneNoiseMode.DEVICE,
        ).platformMicrophoneEffects()

        assertTrue(selected.enableNoiseSuppressor)
        assertTrue(selected.enableAutomaticGain)
        assertTrue(selected.enableEchoCanceler)
    }

    @Test
    fun `rnnoise does not stack platform suppression or automatic gain`() {
        val selected = AudioCaptureConfig(
            enableNoiseSuppressor = true,
            enableAutomaticGain = true,
            enableEchoCanceler = true,
            noiseMode = MicrophoneNoiseMode.AI,
        ).platformMicrophoneEffects()

        assertFalse(selected.enableNoiseSuppressor)
        assertFalse(selected.enableAutomaticGain)
        assertTrue(selected.enableEchoCanceler)
    }

    @Test
    fun `disabled session effect is explicitly disabled and retained for capture lifetime`() {
        val effect = FakeEffect(enabled = true)

        val configured = configureAudioEffect(
            requestedEnabled = false,
            create = { effect },
            setEnabled = { target, enabled -> target.enabled = enabled },
            isEnabled = FakeEffect::enabled,
            release = { it.released = true },
        )

        assertSame(effect, configured)
        assertFalse(effect.enabled)
        assertFalse(effect.released)
    }

    @Test
    fun `effect that rejects requested state is released and omitted`() {
        val effect = FakeEffect(enabled = true)

        val configured = configureAudioEffect(
            requestedEnabled = false,
            create = { effect },
            setEnabled = { _, _ -> Unit },
            isEnabled = FakeEffect::enabled,
            release = { it.released = true },
        )

        assertNull(configured)
        assertTrue(effect.released)
    }

    private data class FakeEffect(
        var enabled: Boolean,
        var released: Boolean = false,
    )
}
