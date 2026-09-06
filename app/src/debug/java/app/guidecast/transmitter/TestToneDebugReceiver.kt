package app.guidecast.transmitter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Emulator-only hook. This class and receiver do not exist in alpha/release APKs. */
class TestToneDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DEBUG_TEST_TONE) BroadcastService.playTestTone(context)
    }

    companion object {
        const val ACTION_DEBUG_TEST_TONE = "app.guidecast.debug.action.TEST_TONE"
    }
}
