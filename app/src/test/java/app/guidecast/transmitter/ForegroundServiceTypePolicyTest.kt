package app.guidecast.transmitter

import android.content.pm.ServiceInfo
import app.guidecast.core.audio.AudioInputKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceTypePolicyTest {
    @Test
    fun activePlaybackAndMicrophoneUseTheirOwnedCaptureType() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            foregroundTypeForBroadcast(AudioInputKind.DEVICE_PLAYBACK, 35),
        )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            foregroundTypeForBroadcast(AudioInputKind.BUILT_IN, 35),
        )
    }

    @Test
    fun serverWithoutInputUsesDataSyncAfterUnexpectedInputFailure() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            foregroundTypeForBroadcast(inputKind = null, sdkInt = 35),
        )
    }

    @Test
    fun api29MicrophoneUsesLegacyUntypedForegroundService() {
        assertEquals(0, foregroundTypeForInput(AudioInputKind.BUILT_IN, 29))
    }
}
