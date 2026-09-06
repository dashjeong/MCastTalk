package app.guidecast.core.audio

/** A privacy-safe description of an Android audio input. */
data class AudioInputDevice(
    val platformId: Int,
    val kind: AudioInputKind,
    val label: String,
)

/** Privacy-safe description of a connected Android media output. */
data class AudioOutputDevice(
    val platformId: Int,
    val kind: AudioOutputKind,
    val label: String,
)

enum class AudioOutputKind {
    BUILT_IN_SPEAKER,
    WIRED,
    USB,
    BLUETOOTH,
    OTHER,
    UNKNOWN,
}

enum class AudioInputKind {
    DEVICE_PLAYBACK,
    BUILT_IN,
    WIRED_HEADSET,
    USB,
    BLUETOOTH,
    WEB_SPEAKER,
    OTHER,

    ;

    companion object {
        const val DEVICE_PLAYBACK_ID = -10_000
        const val WEB_SPEAKER_ID = -20_000
    }
}

/**
 * Automatic selection follows [AudioInputSelectionPolicy]. Manual selection pins one exact
 * platform device. A missing pinned device resolves to null instead of leaking audio by silently
 * falling back to the built-in microphone.
 */
data class AudioInputPreference(
    val mode: Mode,
    val pinnedDeviceId: Int? = null,
) {
    enum class Mode {
        AUTOMATIC,
        PINNED_DEVICE,
    }

    companion object {
        val Automatic = AudioInputPreference(Mode.AUTOMATIC)

        fun pinned(platformId: Int) = AudioInputPreference(
            mode = Mode.PINNED_DEVICE,
            pinnedDeviceId = platformId,
        )
    }
}
