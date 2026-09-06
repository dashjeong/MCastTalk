package app.guidecast.core.audio

object AudioInputSelectionPolicy {
    private val automaticPriority = mapOf(
        AudioInputKind.USB to 0,
        AudioInputKind.WIRED_HEADSET to 1,
        AudioInputKind.BLUETOOTH to 2,
        AudioInputKind.BUILT_IN to 3,
        AudioInputKind.OTHER to 4,
        AudioInputKind.WEB_SPEAKER to 5,
        // Playback capture always requires an explicit user choice and MediaProjection consent.
        AudioInputKind.DEVICE_PLAYBACK to 6,
    )

    fun resolve(
        preference: AudioInputPreference,
        available: List<AudioInputDevice>,
    ): AudioInputDevice? = when (preference.mode) {
        AudioInputPreference.Mode.AUTOMATIC -> available
            .filterNot { it.kind == AudioInputKind.DEVICE_PLAYBACK || it.kind == AudioInputKind.WEB_SPEAKER }
            .minWithOrNull(
            compareBy<AudioInputDevice> { automaticPriority.getValue(it.kind) }
                .thenBy { it.platformId },
        )

        AudioInputPreference.Mode.PINNED_DEVICE -> available.firstOrNull {
            it.platformId == preference.pinnedDeviceId
        }
    }
}
