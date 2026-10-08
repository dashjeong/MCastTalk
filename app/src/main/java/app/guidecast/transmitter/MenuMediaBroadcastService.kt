package app.guidecast.transmitter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Keeps local file/recording broadcasts alive independently of an Activity rotation. */
class MenuMediaBroadcastService : Service() {
    private val app get() = application as GuideCastApplication
    private var generation: Long = -1
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "웹 음성 방송", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 71,
            Intent(this, MenuMediaBroadcastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 72, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("웹 음성 방송").setContentText("선택한 음성과 자막을 청취자에게 전달합니다.")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "방송 종료", stop).build()).build()
        // Foreground admission precedes decoder, database, certificates and network startup.
        ServiceCompat.startForeground(this, 71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requested = intent.getLongExtra(EXTRA_GENERATION, -1)
                if (app.menuBroadcast.serviceStarted(requested)) generation = requested
                else if (!app.menuBroadcast.state.value.isActive) stopSelf(startId)
            }
            ACTION_STOP -> app.menuBroadcast.stop()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        app.menuBroadcast.serviceDestroyed(generation)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        internal const val ACTION_START = "app.guidecast.menu.START"
        internal const val ACTION_STOP = "app.guidecast.menu.STOP"
        internal const val EXTRA_GENERATION = "generation"
        private const val CHANNEL = "menu_web_broadcast"
    }
}
