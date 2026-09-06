package app.guidecast.transmitter

import app.guidecast.core.audio.AudioInputKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputPermissionPolicyTest {
    @Test
    fun notificationDenialDoesNotBlockBuiltInMicrophone() {
        val permissions = InputPermissionState(
            recordAudioGranted = true,
            bluetoothConnectGranted = true,
            notificationsGranted = false,
        )

        assertTrue(permissions.canStartInput(AudioInputKind.BUILT_IN))
    }

    @Test
    fun bluetoothDenialDoesNotBlockBuiltInUsbOrPlaybackInput() {
        val permissions = InputPermissionState(
            recordAudioGranted = true,
            bluetoothConnectGranted = false,
            notificationsGranted = true,
        )

        assertTrue(permissions.canStartInput(AudioInputKind.BUILT_IN))
        assertTrue(permissions.canStartInput(AudioInputKind.USB))
        assertTrue(permissions.canStartInput(AudioInputKind.DEVICE_PLAYBACK))
    }

    @Test
    fun bluetoothMicrophoneRequiresBluetoothPermission() {
        val denied = InputPermissionState(
            recordAudioGranted = true,
            bluetoothConnectGranted = false,
            notificationsGranted = true,
        )
        val allowed = denied.copy(bluetoothConnectGranted = true)

        assertFalse(denied.canStartInput(AudioInputKind.BLUETOOTH))
        assertTrue(allowed.canStartInput(AudioInputKind.BLUETOOTH))
    }

    @Test
    fun everyInputRequiresRecordAudioAndAConcreteSelection() {
        val permissions = InputPermissionState(
            recordAudioGranted = false,
            bluetoothConnectGranted = true,
            notificationsGranted = true,
        )

        assertFalse(permissions.canStartInput(AudioInputKind.BUILT_IN))
        assertFalse(permissions.canStartInput(AudioInputKind.DEVICE_PLAYBACK))
        assertFalse(permissions.canStartInput(null))
    }
}
