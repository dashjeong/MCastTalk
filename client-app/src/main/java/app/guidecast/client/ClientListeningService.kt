package app.guidecast.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ClientListeningService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller: ClientSessionController
        get() = (application as GuideCastClientApplication).sessionController
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:client-listening")
            .apply { acquire(MAX_LISTENING_MILLIS) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "$packageName:client-stream")
            .apply { acquire() }
        createNotificationChannel()
        startInForeground(buildNotification(controller.state.value))
        notificationJob = serviceScope.launch {
            controller.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(state))
                if (state.phase == ClientSessionPhase.ERROR) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_STOP -> {
                controller.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
                val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
                val target = ClientTargetLanguage.fromId(intent.getStringExtra(EXTRA_TARGET))
                controller.start(address, pin, target)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        if (controller.state.value.sessionActive) controller.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: ClientUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ClientMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle("GuideCast Client · ${state.target.displayName}")
            .setContentText(state.errorMessage ?: state.statusMessage)
            .setContentIntent(openIntent)
            .setOngoing(state.sessionActive)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.phase == ClientSessionPhase.LISTENING) {
            builder.addAction(0, "일시정지", serviceAction(ACTION_PAUSE, 1))
        } else if (state.phase == ClientSessionPhase.PAUSED) {
            builder.addAction(0, "계속", serviceAction(ACTION_RESUME, 2))
        }
        if (state.sessionActive) builder.addAction(0, "중지", serviceAction(ACTION_STOP, 3))
        return builder.build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ClientListeningService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun startInForeground(notification: Notification) {
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    companion object {
        private const val CHANNEL_ID = "guidecast_client_listening"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_LISTENING_MILLIS = 6L * 60 * 60 * 1_000
        private const val ACTION_START = "app.guidecast.client.START"
        private const val ACTION_PAUSE = "app.guidecast.client.PAUSE"
        private const val ACTION_RESUME = "app.guidecast.client.RESUME"
        private const val ACTION_STOP = "app.guidecast.client.STOP"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_TARGET = "target"

        fun start(context: Context, state: ClientUiState) {
            val intent = Intent(context, ClientListeningService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ADDRESS, state.address)
                .putExtra(EXTRA_PIN, state.pin)
                .putExtra(EXTRA_TARGET, state.target.name)
            context.startForegroundService(intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, ClientListeningService::class.java).setAction(action))
        }
    }
}
