package app.guidecast.transmitter

import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** Lets Android show its app-only/full-display consent choice; GuideCast uses only captured PCM. */
fun MediaProjectionManager.createGuideCastCaptureIntent(): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
    } else {
        createScreenCaptureIntent()
    }
