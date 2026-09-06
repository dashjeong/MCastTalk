package app.guidecast.transmitter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelStatus
import app.guidecast.provider.gemma.translation.GemmaModelManager
import app.guidecast.provider.gemma.translation.GemmaCatalogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** Downloads, verifies and executes the public gemma4-e2b model as one foreground operation. */
class GemmaModelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var app: GuideCastApplication
    private var work: Job? = null
    private var notificationUpdates: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stagingManager: GemmaModelManager? = null
    private var ownsOperation = false

    override fun onCreate() {
        super.onCreate()
        app = application as GuideCastApplication
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_DOWNLOAD || work?.isActive == true) return START_NOT_STICKY
        if (!operationOwner.compareAndSet(false, true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ownsOperation = true
        val variant = runCatching {
            GemmaCatalogStore(this).resolve(intent.getStringExtra("modelId")
                ?: app.gemmaTranslationProvider.modelManager.selectedVariant.id)
        }.getOrElse {
            preparationMessage.value = "검증된 모델을 찾지 못했습니다: ${it.message}"
            releaseOperation()
            stopSelf()
            return START_NOT_STICKY
        }
        val candidate = GemmaModelManager(this, variant)
        stagingManager = candidate
        preparationStatus.value = candidate.status.value
        preparationMessage.value = "${variant.label} 준비 중 · 기존 적용 모델은 유지됩니다."
        preparing.value = true
        startAsForeground(app.gemmaTranslationProvider.modelManager.status.value)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:gemma-model")
            .apply { acquire(MAX_WAKE_LOCK_MILLIS) }
        notificationUpdates = scope.launch {
            var lastUpdateAt = 0L
            candidate.status.collect { status ->
                preparationStatus.value = status
                val now = SystemClock.elapsedRealtime()
                if (status.readiness != GemmaModelReadiness.DOWNLOADING ||
                    now - lastUpdateAt >= NOTIFICATION_INTERVAL_MILLIS
                ) {
                    updateNotification(status)
                    lastUpdateAt = now
                }
            }
        }
        work = scope.launch {
            val manager = candidate
            try {
            runCatching {
                manager.withSelectedModel {
                // An APK update or process restart can leave a fully verified model whose
                // runtime self-test has not run yet. Re-downloading 2.59 GB in that state both
                // wastes data and requires a second model-sized block of free storage.
                manager.refresh()
                if (manager.status.value.readiness.requiresModelDownload()) {
                    manager.download()
                }
                val broadcast = app.broadcastRuntime.state.value
                check(broadcast.phase == BroadcastPhase.IDLE && !broadcast.translationTestActive) {
                    "파일 준비 완료. 방송·통역 시험을 중지한 뒤 다시 적용하세요. 기존 모델은 유지됩니다."
                }
                manager.markEngineTesting()
                val result = app.withTranslationBackendUse {
                    app.withProcessNativeColdLoadLease(
                        key = ProcessNativeColdLoadKeys.GEMMA_MODEL,
                        serializeWithAllColdLoads = true,
                    ) { app.gemmaTranslationProvider.applyVerifiedModel(variant) }
                }
                preparationMessage.value = "${variant.label} 적용 완료 · 실제 번역 점검: $result · 음성 품질은 시험 탭에서 확인하세요."
                }
            }.onFailure { error ->
                preparationMessage.value = "모델 적용 미완료 · 기존 모델 유지: ${error.message ?: error.javaClass.simpleName}"
                if (error is CancellationException) throw error
                if (manager.status.value.readiness == GemmaModelReadiness.ENGINE_TESTING) {
                    manager.markRuntimeFailure(error.message ?: "실행 점검 실패")
                }
                Log.e(LOG_TAG, "Gemma Translator runtime verification failed", error)
            }
            // This service owns one deduplicated operation. A second tap still creates a newer
            // startId even though it does not create another job, so stopSelf(startId) could leave
            // the dataSync foreground service alive until Android's multi-hour timeout.
            } finally {
            notificationUpdates?.cancel()
            releaseOperation()
            stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "Foreground-service time limit reached: type=$fgsType")
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            val message = "Android 장시간 실행 제한으로 모델 준비를 중지했습니다. " +
                "받은 파일은 보존되며 모델 내려받기를 다시 누르면 이어받습니다."
            stagingManager?.markOperationInterrupted(message)
            preparationMessage.value = message
            work?.cancel(CancellationException(message))
            notificationUpdates?.cancel()
            notificationUpdates = null
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        notificationUpdates?.cancel()
        work?.cancel()
        releaseWakeLock()
        scope.cancel()
        if (work == null) releaseOperation()
        super.onDestroy()
    }

    private fun releaseOperation() {
        if (ownsOperation) {
            ownsOperation = false
            preparationStatus.value = null
            preparing.value = false
            operationOwner.set(false)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
        wakeLock = null
    }

    private fun startAsForeground(status: GemmaModelStatus) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(status),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateNotification(status: GemmaModelStatus) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
    }

    private fun notification(status: GemmaModelStatus) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("Gemma Translator 준비")
        .setContentText(status.notificationText())
        .setOnlyAlertOnce(true)
        .setOngoing(status.readiness in ACTIVE_STATES)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .apply {
            if (status.readiness == GemmaModelReadiness.DOWNLOADING) {
                setProgress(
                    1_000,
                    (status.downloadedBytes * 1_000L / status.totalBytes.coerceAtLeast(1L)).toInt(),
                    false,
                )
            } else if (status.readiness in ACTIVE_STATES) {
                setProgress(0, 0, true)
            }
        }
        .build()

    private fun GemmaModelStatus.notificationText(): String = when (readiness) {
        GemmaModelReadiness.NOT_INSTALLED -> "다운로드를 시작합니다."
        GemmaModelReadiness.DOWNLOADING ->
            "${downloadedBytes / 1_048_576} / ${totalBytes / 1_048_576} MiB · 이어받기 지원"
        GemmaModelReadiness.VERIFYING -> "파일 크기와 SHA-256을 검증합니다."
        GemmaModelReadiness.VERIFIED -> errorMessage ?: "모델 파일 검증 완료"
        GemmaModelReadiness.ENGINE_TESTING -> "실제 번역 추론을 점검합니다."
        GemmaModelReadiness.READY -> "번역 추론 점검 통과 · 오프라인 준비 완료"
        GemmaModelReadiness.FAILED -> errorMessage ?: "모델 준비 실패"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Gemma 모델 준비",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private val operationOwner = AtomicBoolean(false)
        val preparing = MutableStateFlow(false)
        val preparationStatus = MutableStateFlow<GemmaModelStatus?>(null)
        val preparationMessage = MutableStateFlow<String?>(null)
        private const val ACTION_DOWNLOAD = "app.guidecast.action.DOWNLOAD_GEMMA_TRANSLATOR"
        private const val LOG_TAG = "GemmaModelService"
        private const val CHANNEL_ID = "guidecast_gemma_model"
        private const val NOTIFICATION_ID = 3102
        private const val MAX_WAKE_LOCK_MILLIS = 6L * 60 * 60 * 1_000
        private const val NOTIFICATION_INTERVAL_MILLIS = 1_000L
        private val ACTIVE_STATES = setOf(
            GemmaModelReadiness.DOWNLOADING,
            GemmaModelReadiness.VERIFYING,
            GemmaModelReadiness.ENGINE_TESTING,
        )

        fun start(context: Context, modelId: String? = null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GemmaModelService::class.java).setAction(ACTION_DOWNLOAD)
                    .putExtra("modelId", modelId),
            )
        }
    }
}

internal fun GemmaModelReadiness.requiresModelDownload(): Boolean =
    this != GemmaModelReadiness.VERIFIED && this != GemmaModelReadiness.READY
