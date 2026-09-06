package app.guidecast.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioInputSelectionPolicyTest {
    private val builtIn = AudioInputDevice(1, AudioInputKind.BUILT_IN, "Phone")
    private val bluetooth = AudioInputDevice(2, AudioInputKind.BLUETOOTH, "Guide headset")
    private val wired = AudioInputDevice(3, AudioInputKind.WIRED_HEADSET, "Wired")
    private val usb = AudioInputDevice(4, AudioInputKind.USB, "USB directional")
    private val playback = AudioInputDevice(
        AudioInputKind.DEVICE_PLAYBACK_ID,
        AudioInputKind.DEVICE_PLAYBACK,
        "Device playback",
    )

    @Test
    fun `automatic selection prefers usb then wired then bluetooth then built in`() {
        val result = AudioInputSelectionPolicy.resolve(
            AudioInputPreference.Automatic,
            listOf(builtIn, bluetooth, wired, usb),
        )

        assertEquals(usb, result)
    }

    @Test
    fun `automatic selection uses bluetooth when no usb or wired input exists`() {
        val result = AudioInputSelectionPolicy.resolve(
            AudioInputPreference.Automatic,
            listOf(builtIn, bluetooth),
        )

        assertEquals(bluetooth, result)
    }

    @Test
    fun `manual selection resolves one exact device`() {
        val result = AudioInputSelectionPolicy.resolve(
            AudioInputPreference.pinned(bluetooth.platformId),
            listOf(builtIn, bluetooth),
        )

        assertEquals(bluetooth, result)
    }

    @Test
    fun `missing pinned device does not fall back to built in microphone`() {
        val result = AudioInputSelectionPolicy.resolve(
            AudioInputPreference.pinned(usb.platformId),
            listOf(builtIn),
        )

        assertNull(result)
    }

    @Test
    fun `automatic selection never chooses privacy gated playback capture`() {
        val result = AudioInputSelectionPolicy.resolve(
            AudioInputPreference.Automatic,
            listOf(playback, builtIn),
        )

        assertEquals(builtIn, result)
    }

    @Test
    fun `playback capture can be selected explicitly`() {
        val result = AudioInputSelectionPolicy.resolve(
            AudioInputPreference.pinned(playback.platformId),
            listOf(playback, builtIn),
        )

        assertEquals(playback, result)
    }
}
