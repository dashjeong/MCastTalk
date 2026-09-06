package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputKind

/**
 * Keeps optional Android permissions from disabling unrelated audio inputs.
 *
 * Notification permission only controls whether Android shows the foreground notification.
 * Bluetooth permission is required only for a Bluetooth microphone. Every input still requires
 * RECORD_AUDIO, including MediaProjection playback capture on the Android versions we support.
 */
internal data class InputPermissionState(
    val recordAudioGranted: Boolean,
    val bluetoothConnectGranted: Boolean,
    val notificationsGranted: Boolean,
) {
    fun canStartInput(kind: AudioInputKind?): Boolean =
        kind != null &&
            recordAudioGranted &&
            (kind != AudioInputKind.BLUETOOTH || bluetoothConnectGranted)
}
