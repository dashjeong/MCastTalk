package app.guidecast.transmitter

import android.content.pm.ServiceInfo
import android.os.Build
import app.guidecast.core.audio.AudioInputKind

internal fun foregroundTypeForInput(kind: AudioInputKind, sdkInt: Int): Int = when {
    kind == AudioInputKind.DEVICE_PLAYBACK ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
    sdkInt >= Build.VERSION_CODES.R ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    else -> 0
}

internal fun foregroundTypeForBroadcast(inputKind: AudioInputKind?, sdkInt: Int): Int =
    inputKind?.let { foregroundTypeForInput(it, sdkInt) }
        ?: ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
