package app.guidecast.transmitter

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Instrumentation bridge. Debug and alpha manifests register this non-exported activity so the
 * device-test APK can exercise MediaProjection; the release manifest does not register it.
 */
class MediaProjectionTestActivity : ComponentActivity() {
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val targetPackage = intent.getStringExtra(EXTRA_PLAYBACK_TARGET_PACKAGE)
            val targetUid = intent.getIntExtra(EXTRA_PLAYBACK_TARGET_UID, -1)
            val playbackTarget = if (!targetPackage.isNullOrBlank() && targetUid > 0) {
                PlaybackTargetApp(
                    packageName = targetPackage,
                    uid = targetUid,
                    label = targetPackage,
                )
            } else {
                null
            }
            BroadcastService.startInput(this, result.resultCode, data, playbackTarget)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        // The instrumentation harness must be deterministic on Android 14+: selecting the
        // default display avoids the additional app-picker page. The real operator UI continues
        // to use createGuideCastCaptureIntent() and preserves Android's app/full-screen choice.
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    companion object {
        internal const val EXTRA_PLAYBACK_TARGET_PACKAGE = "test_playback_target_package"
        internal const val EXTRA_PLAYBACK_TARGET_UID = "test_playback_target_uid"

        internal fun intent(context: android.content.Context, targetPackage: String, targetUid: Int) =
            Intent(context, MediaProjectionTestActivity::class.java)
                .putExtra(EXTRA_PLAYBACK_TARGET_PACKAGE, targetPackage)
                .putExtra(EXTRA_PLAYBACK_TARGET_UID, targetUid)
    }
}
