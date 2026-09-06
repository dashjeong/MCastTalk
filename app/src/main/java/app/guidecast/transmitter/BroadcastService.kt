package app.guidecast.transmitter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.guidecast.core.audio.AudioCaptureConfig
import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.PcmFrame
import app.guidecast.core.audio.PcmSineWaveGenerator
import app.guidecast.core.audio.WebAudioInputBridge
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.server.BroadcastAccess
import app.guidecast.core.server.GuideCastLocalServer
import app.guidecast.core.server.GuideCastServerConfig
import app.guidecast.core.server.LocalNetworkAddressResolver
import app.guidecast.core.server.RunningGuideCastServer
import app.guidecast.core.server.SpeakerAccess
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.ChannelAudioPublicationCoordinator
import app.guidecast.core.stream.AudioStreamObservabilitySnapshot
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.FairQueuedTranslationEngineProvider
import app.guidecast.core.translation.FairTranslationQueueConfig
import app.guidecast.core.stream.LocalAudioMonitorSubscription
import app.guidecast.core.stream.StreamSession
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.FailoverTranslationEngineProvider
import app.guidecast.core.translation.OpenCcSimplifiedToTraditionalConverter
import app.guidecast.core.translation.RunningTranslationPipeline
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SynthesizedPcmStats
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationBroadcastPipeline
import app.guidecast.core.translation.TranslationEngineProvider
import app.guidecast.core.translation.SelectiveRefinementTranslationEngineProvider
import app.guidecast.core.translation.SelectiveRefinementOutcome
import app.guidecast.core.translation.TranslationPipelineObserver
import app.guidecast.core.translation.TranslationTarget
import app.guidecast.core.translation.TranslationWorkerState
import java.util.Locale
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.coroutineContext

private data class TranslationPreparationResult(
    val providerLabel: String,
    val warning: String?,
)

private const val MAX_STANDARD_NATIVE_SUPPORT_RELOADS = 2

private data class PendingBroadcastStart(
    val intent: Intent,
    /** Broadcast control generation at the instant this restart was queued. */
    val controlGeneration: Long,
)

private class LocalMonitorFeedbackBlockedException(message: String) :
    IllegalStateException(message)

internal data class GemmaBroadcastWarmupResult(
    val active: Boolean,
    val warning: String? = null,
)

internal data class RetryableCloseResult<T : Any>(
    val retained: T?,
    val failure: Throwable?,
)

/** Returns the exact resource on failure so its owner can retry instead of losing the handle. */
internal fun <T : Any> closeWithRetryAndRetain(
    resource: T?,
    attempts: Int = 2,
    close: (T) -> Unit,
): RetryableCloseResult<T> {
    require(attempts > 0)
    if (resource == null) return RetryableCloseResult(retained = null, failure = null)
    var failure: Throwable? = null
    repeat(attempts) {
        try {
            close(resource)
            return RetryableCloseResult(retained = null, failure = null)
        } catch (error: Throwable) {
            val previous = failure
            if (previous == null) {
                failure = error
            } else if (previous !== error) {
                previous.addSuppressed(error)
            }
        }
    }
    return RetryableCloseResult(retained = resource, failure = failure)
}

/** Six-GB-class devices serialize the large model load before accepting live recognition PCM. */
internal fun shouldWarmGemmaBeforeListening(
    awaitChannelPreparation: Boolean,
    gemmaEligible: Boolean,
    constrainedMemoryMode: Boolean,
): Boolean = awaitChannelPreparation || (gemmaEligible && constrainedMemoryMode)

/**
 * Only a completed warm-up may initially open the Gemma priority route. Standard-memory sessions
 * may retry after a bounded cooldown; constrained sessions stay on fallback after one failure.
 * The worker-process generation gate prevents either policy from overlapping native runtimes.
 */
internal fun shouldEnableGemmaPriorityRoute(
    gemmaEligible: Boolean,
    warmupActive: Boolean,
): Boolean = gemmaEligible && warmupActive

internal fun isGemmaBroadcastEligible(
    requested: Boolean,
    languagePairSupported: Boolean,
    hardwareSupported: Boolean,
    modelReady: Boolean,
    automaticRetryBlocked: Boolean = false,
): Boolean = requested && languagePairSupported && hardwareSupported &&
    modelReady && !automaticRetryBlocked

/**
 * Keeps the stable physical-RAM classification from session start, but refreshes Android's
 * pressure-sensitive admission after old backend/voice reconciliation has had a chance to release
 * memory. An initially unsupported device cannot be promoted by a later inconsistent snapshot.
 */
internal data class GemmaBroadcastAttemptAdmission(
    val hardwareSupported: Boolean,
    val constrainedMemoryMode: Boolean,
    val loadPermittedNow: Boolean,
    val operatorMessage: String,
)

internal fun gemmaBroadcastAttemptAdmission(
    initialCapability: GemmaBroadcastCapability,
    latestCapability: GemmaBroadcastCapability,
    workerAlreadyPrepared: Boolean = false,
): GemmaBroadcastAttemptAdmission = GemmaBroadcastAttemptAdmission(
    hardwareSupported = initialCapability.supported,
    constrainedMemoryMode = initialCapability.constrainedMemoryMode,
    loadPermittedNow = initialCapability.supported &&
        (workerAlreadyPrepared || latestCapability.loadPermittedNow),
    operatorMessage = if (initialCapability.supported) {
        if (workerAlreadyPrepared && !latestCapability.loadPermittedNow) {
            latestCapability.message.substringBefore(" · 현재 가용") +
                " · 이미 추론을 통과한 Gemma 작업 공간 재사용"
        } else {
            latestCapability.message
        }
    } else {
        initialCapability.message
    },
)

/** Pure routing helper: ensures operator selection is strictly preserved without background overrides. */
internal fun resolveInputFrames(
    input: AudioInputDevice,
    physicalMicFrames: () -> Flow<PcmFrame>,
    playbackFrames: () -> Flow<PcmFrame>,
    webSpeakerFrames: () -> Flow<PcmFrame>,
): Flow<PcmFrame> = when (input.kind) {
    AudioInputKind.DEVICE_PLAYBACK -> playbackFrames()
    AudioInputKind.WEB_SPEAKER -> webSpeakerFrames()
    else -> physicalMicFrames()
}

/** Per-backend admission; translation and speech receive separate gates to isolate stalls. */
internal fun translationSupportPreparationParallelism(
    constrainedMemoryMode: Boolean,
    languageCount: Int,
): Int {
    require(languageCount in 1..MAX_SIMULTANEOUS_TRANSLATED_CHANNELS)
    return if (constrainedMemoryMode) 1 else languageCount
}

/** Reloads are smaller than first preparation fan-out and remain single-file on constrained RAM. */
internal fun translationSupportReloadParallelism(
    constrainedMemoryMode: Boolean,
    languageCount: Int,
): Int {
    require(languageCount in 1..MAX_SIMULTANEOUS_TRANSLATED_CHANNELS)
    return if (constrainedMemoryMode) {
        1
    } else {
        minOf(MAX_STANDARD_NATIVE_SUPPORT_RELOADS, languageCount)
    }
}

/**
 * Translation and speech keep independent failure domains, but their cold native mappings share
 * this aggregate lane. One-language standard sessions still admit translation and voice together;
 * constrained devices serialize cold loads without removing a selected channel or voice.
 */
internal fun shouldSerializeNativeColdLoads(
    constrainedMemoryMode: Boolean,
    languageCount: Int,
    unconstrainedLoadPreferred: Boolean,
): Boolean {
    require(languageCount in 1..MAX_SIMULTANEOUS_TRANSLATED_CHANNELS)
    return constrainedMemoryMode || languageCount > 1 || !unconstrainedLoadPreferred
}

/** The shared memory budget now makes constrained fallback warm-up safe beside resident Gemma. */
internal fun shouldWarmFallbackTranslationInBackground(
    constrainedMemoryMode: Boolean,
    gemmaPriorityActive: Boolean,
    memoryAdmissionEnabled: Boolean = true,
): Boolean = !(constrainedMemoryMode && gemmaPriorityActive && !memoryAdmissionEnabled)

/** A constrained broadcast avoids repeated 10.5-second Gemma stalls after its first failure. */
internal fun shouldRetryGemmaWithinBroadcast(constrainedMemoryMode: Boolean): Boolean =
    !constrainedMemoryMode

/** Converts an uncaught child failure into telemetry instead of Android's process-fatal handler. */
internal fun failureIsolatingServiceExceptionHandler(
    report: (Throwable) -> Unit,
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, error -> report(error) }

/**
 * Makes begin-owner -> backend lease -> service-field attachment one cancellation-safe handoff.
 * Before [attach] returns true, no teardown path can see the owner, so this helper retains exact
 * cleanup ownership for every exception and cancellation window.
 */
internal suspend fun <O : Any, L : Any> acquireAndAttachTranslationBackendOwner(
    owner: O,
    acquire: suspend () -> L?,
    attach: (owner: O, lease: L) -> Boolean,
    releaseLease: (L) -> Unit,
    endOwner: (O) -> Unit,
) {
    var lease: L? = null
    var attached = false
    try {
        lease = acquire() ?: throw CancellationException(
            "A newer translation session replaced backend activation",
        )
        attached = attach(owner, requireNotNull(lease))
        if (!attached) {
            throw CancellationException("A newer translation session replaced backend attachment")
        }
    } finally {
        if (!attached) {
            lease?.let(releaseLease)
            endOwner(owner)
        }
    }
}

/** Resolves Gemma readiness before LISTENING without changing the live inference deadline. */
internal suspend fun prepareGemmaBroadcastWarmup(
    eligible: Boolean,
    fallbackReady: Boolean,
    priorityLanguageTag: String,
    warmup: suspend (String) -> Unit,
    cleanupAfterFailure: suspend () -> Unit,
): GemmaBroadcastWarmupResult {
    if (!eligible) return GemmaBroadcastWarmupResult(active = false)
    require(priorityLanguageTag.isNotBlank()) { "Gemma warmup priority language is required" }
    return try {
        warmup(priorityLanguageTag)
        GemmaBroadcastWarmupResult(active = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val detail = error.message?.take(300) ?: error.javaClass.simpleName
        var cleanupWarning: String? = null
        try {
            cleanupAfterFailure()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cleanupError: Throwable) {
            // A private Gemma worker whose close cannot yet be confirmed must not be rebound; the
            // process-generation gate enforces that. It also must not take down the microphone or
            // local server. Keep this session permanently off Gemma and expose the cleanup state
            // while the original/ML Kit paths continue under the operator's control.
            cleanupWarning = "Gemma 작업 공간 회수 확인 실패 · 이 방송에서는 재시도하지 않음: " +
                (cleanupError.message?.take(300) ?: cleanupError.javaClass.simpleName)
        }
        GemmaBroadcastWarmupResult(
            active = false,
            warning = listOfNotNull(if (fallbackReady) {
                "Gemma 사전 준비 오류로 경량 오프라인 번역을 사용합니다 · $detail"
            } else {
                "Gemma 우선 채널 준비 오류 · 해당 언어만 번역 모델 준비 후 재시도합니다 · $detail"
            }, cleanupWarning).joinToString(" · "),
        )
    }
}

/**
 * Owns two independent state machines:
 * 1. input capture: idle / active / paused
 * 2. local broadcast server: idle / live / paused
 *
 * Stopping one state machine never implicitly stops the other.
 */
class BroadcastService : Service() {
    private val serviceExceptionHandler = failureIsolatingServiceExceptionHandler { error ->
        // A forgotten background-child catch must never become an Android process crash during
        // an event. The owning task still terminates, while the server/input/sibling channels stay
        // alive and the operator gets a bounded diagnostic instead of a false global failure.
        Log.e(LOG_TAG, "Isolated service background task failed", error)
        if (this::app.isInitialized) {
            val warning = "독립 백그라운드 작업 오류 · " +
                (error.message?.take(MAX_OPERATOR_ERROR_DETAIL_CHARACTERS)
                    ?: error.javaClass.simpleName)
            app.broadcastRuntime.update { current ->
                if (current.phase == BroadcastPhase.LIVE ||
                    current.phase == BroadcastPhase.PAUSED ||
                    current.translationTestActive
                ) {
                    current.copy(
                        translationWarning = listOfNotNull(
                            current.translationWarning,
                            warning,
                        ).distinct().joinToString(" · "),
                    )
                } else {
                    current.copy(errorMessage = warning)
                }
            }
        }
    }
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + serviceExceptionHandler,
    )
    private lateinit var app: GuideCastApplication
    private lateinit var audioManager: AudioManager
    private var inputJob: Job? = null
    private var serverStartJob: Job? = null
    private var pendingBroadcastStart: PendingBroadcastStart? = null
    private var listenerJob: Job? = null
    private var translationPreparationJob: Job? = null
    private var translationSupportPreparationJob: Job? = null
    private var translationHealthJob: Job? = null
    private var translationPipeline: RunningTranslationPipeline? = null
    private var sharedTranslationQueue: FairQueuedTranslationEngineProvider? = null
    private var translationBackendUseLease: TranslationBackendUseLease? = null
    private var translationPreparationOwner: TranslationPreparationOwnerToken? = null
    private var translationTestJob: Job? = null
    private var previewPlaybackJob: Job? = null
    private var previewSubscription: LocalAudioMonitorSubscription? = null
    private var previewAudioTrack: AudioTrack? = null
    private var localMonitorJob: Job? = null
    private var localMonitorSubscription: LocalAudioMonitorSubscription? = null
    private var localMonitorAudioTrack: AudioTrack? = null
    private val localMonitorPaused = AtomicBoolean(false)
    private val localMonitorPlaybackEpoch = AtomicLong(0)
    private val localMonitorGeneration = AtomicLong(0)
    @Volatile private var recognitionFrames: Channel<PcmAudioFrame>? = null
    private var runningServer: RunningGuideCastServer? = null
    /** Exact audio generation shared by the server and every producer of this broadcast. */
    @Volatile private var broadcastStreamSession: StreamSession? = null
    /** Per-session ordering: language channels stay parallel; an operator tone owns them all. */
    @Volatile private var broadcastAudioPublicationCoordinator:
        ChannelAudioPublicationCoordinator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var selectedInput: AudioInputDevice? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null
    private var playbackTargetUid: Int? = null
    private val projectionGeneration = AtomicLong(0)
    private val testToneActive = AtomicBoolean(false)
    private val testToneRequestPending = AtomicBoolean(false)
    private val testToneRequestGeneration = AtomicLong(0)
    private val inputPaused = AtomicBoolean(false)
    private val inputGeneration = AtomicLong(0)
    private val broadcastGeneration = AtomicLong(0)
    @Volatile private var latestDeliveredStartId = 0
    private val translationResourceLock = Any()
    private val translationSessionCoordinator = TranslationSessionCoordinator(translationResourceLock)
    private val localMonitorLock = Any()
    private val broadcastResourceLock = Any()
    /** Ktor stop may block; serialize retries without holding the broader broadcast state lock. */
    private val serverCloseLock = Any()
    @Volatile private var broadcastUsesTranslation = false
    @Volatile private var translationProviderWarning: String? = null
    @Volatile private var translationTestAudioEvidence: TranslationTestAudioEvidence? = null
    override fun onCreate() {
        super.onCreate()
        app = application as GuideCastApplication
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestDeliveredStartId = startId
        val action = intent?.action
        try {
            when (action) {
                ACTION_START_INPUT -> startInput(intent)
                ACTION_PAUSE_INPUT -> pauseInput()
                ACTION_RESUME_INPUT -> resumeInput()
                ACTION_STOP_INPUT -> stopInput()
                ACTION_START_BROADCAST -> startBroadcast(intent)
                ACTION_PAUSE_BROADCAST -> pauseBroadcast()
                ACTION_RESUME_BROADCAST -> resumeBroadcast()
                ACTION_STOP_BROADCAST -> stopBroadcast()
                ACTION_START_TRANSLATION_TEST -> startTranslationTest(intent)
                ACTION_STOP_TRANSLATION_TEST -> stopTranslationTest()
                ACTION_START_LOCAL_MONITOR -> startLocalMonitor(intent)
                ACTION_PAUSE_LOCAL_MONITOR -> pauseLocalMonitor()
                ACTION_RESUME_LOCAL_MONITOR -> resumeLocalMonitor()
                ACTION_STOP_LOCAL_MONITOR -> stopLocalMonitor()
                ACTION_SET_LOCAL_MONITOR_VOLUME -> setLocalMonitorVolume(intent)
                ACTION_STOP_ALL -> stopAll()
                ACTION_TEST_TONE -> playTestTone()
            }
        } catch (error: Throwable) {
            handleActionFailure(action, error)
        }
        return START_NOT_STICKY
    }

    private fun handleActionFailure(action: String?, error: Throwable) {
        Log.e(LOG_TAG, "Service action failed: $action", error)
        val message = error.message ?: error.javaClass.simpleName
        when (action) {
            ACTION_START_INPUT -> failInput("입력을 시작하지 못했습니다: $message")
            ACTION_START_BROADCAST -> {
                releaseBroadcastResources()
                app.broadcastRuntime.update { current ->
                    current.copy(
                        phase = BroadcastPhase.FAILED,
                        accessMode = null,
                        listenerUrl = null,
                        speakerUrl = null,
                        caSha256Fingerprint = null,
                        caFingerprintWarning = null,
                        listenerCount = 0,
                        listenerDroppedFrames = 0,
                        webSocketDeliveredFrameCount = 0,
                        translationChannels = emptyList(),
                        errorMessage = "방송 서버를 시작하지 못했습니다: $message",
                    )
                }
                stopServiceIfUnused()
            }
            ACTION_START_TRANSLATION_TEST -> {
                releaseTranslationTestResources()
                app.broadcastRuntime.update { current ->
                    current.copy(
                        translationTestActive = false,
                        translationTestPassed = false,
                        translationTestMessage = "통번역 시험을 시작하지 못했습니다: $message",
                    )
                }
            }
            ACTION_START_LOCAL_MONITOR -> failLocalMonitor(
                "로컬 모니터를 시작하지 못했습니다: $message",
            )
            else -> app.broadcastRuntime.update { current ->
                current.copy(errorMessage = "요청을 처리하지 못했습니다: $message")
            }
        }
        updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseBroadcastResources()
        invalidateInputCapture()
        stopProjection()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "Foreground-service time limit reached: type=$fgsType")
        if (fgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            releaseBroadcastResources()
            app.broadcastRuntime.update { current ->
                current.copy(
                    phase = BroadcastPhase.FAILED,
                    accessMode = null,
                    listenerUrl = null,
                    speakerUrl = null,
                    caSha256Fingerprint = null,
                    caFingerprintWarning = null,
                    listenerCount = 0,
                    listenerDroppedFrames = 0,
                    webSocketDeliveredFrameCount = 0,
                    translationChannels = emptyList(),
                    errorMessage = "Android 장시간 실행 제한으로 방송 서버를 안전하게 종료했습니다. " +
                        "입력을 켠 뒤 방송을 다시 시작하세요.",
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return
        }
        super.onTimeout(startId, fgsType)
    }

    private fun startInput(intent: Intent) {
        if (inputJob != null) return
        val input = app.audioInputRepository.selectedDevice.value
        if (input == null) {
            failInput("사용할 입력을 먼저 선택하세요.")
            return
        }
        selectedInput = input
        val isPlayback = input.kind == AudioInputKind.DEVICE_PLAYBACK
        startForegroundForInput(isPlayback)

        if (isPlayback && mediaProjection == null) {
            val requestedPackage = intent.getStringExtra(EXTRA_PLAYBACK_TARGET_PACKAGE)
            val requestedUid = intent.getIntExtra(EXTRA_PLAYBACK_TARGET_UID, -1)
            val actualUid = requestedPackage?.let(::installedApplicationUid)
            if (requestedPackage.isNullOrBlank() || requestedUid <= 0 || actualUid != requestedUid ||
                requestedUid == applicationInfo.uid
            ) {
                failInput("출력 대상 앱을 다시 선택하세요.")
                return
            }
            val resultCode = intent.getIntExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, Int.MIN_VALUE)
            val resultData = intent.mediaProjectionData()
            if (resultCode == Int.MIN_VALUE || resultData == null) {
                failInput("기기 내부 소리 캡처 권한을 허용하세요.")
                return
            }
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                failInput("Android가 내부 소리 캡처 권한을 제공하지 않았습니다.")
                return
            }
            val projectionSession = projectionGeneration.incrementAndGet()
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projectionGeneration.get() != projectionSession ||
                        mediaProjection !== projection
                    ) {
                        return
                    }
                    mediaProjection = null
                    mediaProjectionCallback = null
                    projectionGeneration.incrementAndGet()
                    failInput(
                        "기기 내부 소리 캡처 권한이 종료됐습니다. 입력 시작을 다시 누르세요.",
                    )
                }
            }
            mediaProjection = projection
            mediaProjectionCallback = callback
            playbackTargetUid = requestedUid
            projection.registerCallback(callback, Handler(Looper.getMainLooper()))
        }
        launchInputCapture(input)
    }

    private fun launchInputCapture(input: AudioInputDevice) {
        if (inputJob != null) return
        val generation = inputGeneration.incrementAndGet()
        val signalTracker = InputSignalTracker()
        app.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.STARTING,
                inputLabel = input.label,
                inputRms = 0f,
                inputPeak = 0f,
                inputFrameCount = 0,
                inputAudibleFrameCount = 0,
                inputSignalActive = false,
                inputProcessingSummary = null,
                inputErrorMessage = null,
            )
        }
        inputPaused.set(false)
        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                inputFrames(input).collect { frame ->
                    processInputFrame(input, frame, generation, signalTracker)
                }
                error("오디오 입력이 예기치 않게 종료됐습니다.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (isInputGenerationCurrent(generation) && inputJob === job) {
                    failInput(error.message ?: "오디오 입력을 시작하지 못했습니다.")
                }
            }
        }
        inputJob = job
        job.start()
        updateNotification()
    }

    private fun inputFrames(input: AudioInputDevice): Flow<PcmFrame> =
        resolveInputFrames(
            input = input,
            physicalMicFrames = {
                app.audioCaptureEngine.frames(
                    preferredDeviceId = input.platformId,
                    config = AudioCaptureConfig(sampleRateHz = SAMPLE_RATE_HZ,
                        noiseMode = app.microphoneNoiseSettings.mode.value),
                )
            },
            playbackFrames = {
                app.audioCaptureEngine.playbackFrames(
                    mediaProjection = requireNotNull(mediaProjection) {
                        "기기 내부 소리 캡처 권한이 없습니다."
                    },
                    targetUid = requireNotNull(playbackTargetUid) {
                        "출력 대상 앱이 선택되지 않았습니다."
                    },
                    config = AudioCaptureConfig(
                        sampleRateHz = SAMPLE_RATE_HZ,
                        enableNoiseSuppressor = false,
                        enableAutomaticGain = false,
                    ),
                )
            },
            webSpeakerFrames = {
                WebAudioInputBridge.frames()
            },
        )

    private suspend fun processInputFrame(
        input: AudioInputDevice,
        frame: PcmFrame,
        generation: Long,
        signalTracker: InputSignalTracker,
    ) {
        if (!isInputGenerationCurrent(generation) || inputPaused.get()) return
        if (input.kind != AudioInputKind.DEVICE_PLAYBACK &&
            input.kind != AudioInputKind.WEB_SPEAKER &&
            app.audioInputRepository.selectedDevice.value?.platformId != input.platformId
        ) {
            error("선택한 마이크 연결이 끊겼습니다. 입력을 안전하게 중지합니다.")
        }
        val stats = frame.bytes.pcmS16LeSignalStats()
        val signal = signalTracker.observe(
            rms = stats.rms,
            peak = stats.peak,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
        if (!isInputGenerationCurrent(generation) || inputPaused.get()) return
        if (signal.frameCount == 1L || signal.frameCount % 5L == 0L) {
            app.broadcastRuntime.update { current ->
                if (!isInputGenerationCurrent(generation) || inputPaused.get()) {
                    return@update current
                }
                val activeLabel = if (WebAudioInputBridge.isStreamingActive()) {
                    "${input.label} (강사 웹 음성 수신 중)"
                } else {
                    input.label
                }
                current.copy(
                    inputPhase = InputPhase.ACTIVE,
                    inputLabel = activeLabel,
                    inputRms = stats.rms,
                    inputPeak = stats.peak,
                    inputFrameCount = signal.frameCount,
                    inputAudibleFrameCount = signal.audibleFrameCount,
                    inputSignalActive = signal.signalActive,
                    inputProcessingSummary = app.audioCaptureEngine.processingStatus.value
                        .operatorSummary,
                    inputErrorMessage = null,
                )
            }
        }

        if (!isInputGenerationCurrent(generation) || inputPaused.get()) return
        val current = app.broadcastRuntime.state.value
        if (testToneActive.get()) return
        val pcm = PcmAudioFrame(frame.bytes, frame.capturedAtElapsedRealtimeNanos)
        if (current.phase == BroadcastPhase.LIVE) {
            // Input and broadcast have independent stop controls. If the server generation is
            // being replaced, its channel registry can disappear between two captured frames.
            // Drop only that obsolete output frame; never fail the microphone/input session.
            val publicationLease = broadcastAudioPublicationCoordinator?.acquireChannel("source")
            try {
                val latest = app.broadcastRuntime.state.value
                // Recheck after acquiring the lease: a tone request can win the race while this
                // capture coroutine was waiting, and no source frame may be woven into its PCM.
                if (!testToneActive.get() && latest.phase == BroadcastPhase.LIVE) {
                    broadcastStreamSession?.tryPublish("source", pcm)
                }
            } finally {
                publicationLease?.close()
            }
        }
        // Source audio is independent of STT/TTS readiness and is published before STT backpressure.
        if (
            recognitionFrames != null &&
            (current.translationTestActive || current.phase == BroadcastPhase.LIVE)
        ) {
            val recognitionInput = recognitionFrames
            try {
                recognitionInput?.send(pcm)
            } catch (_: ClosedSendChannelException) {
                // A replaced translation session must not stop the source broadcast.
            }
        }
    }

    private fun pauseInput() {
        if (app.broadcastRuntime.state.value.inputPhase != InputPhase.ACTIVE) return
        inputPaused.set(true)
        app.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.PAUSED,
                inputRms = 0f,
                inputPeak = 0f,
                inputSignalActive = false,
                inputProcessingSummary = null,
            )
        }
        updateNotification()
    }

    private fun resumeInput() {
        if (app.broadcastRuntime.state.value.inputPhase != InputPhase.PAUSED) return
        if (inputJob == null || selectedInput == null) {
            failInput("입력 스트림이 종료됐습니다. 입력 시작을 다시 누르세요.")
            return
        }
        inputPaused.set(false)
        app.broadcastRuntime.update { current ->
            current.copy(inputPhase = InputPhase.ACTIVE, inputErrorMessage = null)
        }
        updateNotification()
    }

    private fun stopInput() {
        if (app.broadcastRuntime.state.value.translationTestActive) {
            stopTranslationTest(preservePass = true)
        }
        invalidateInputCapture()
        inputPaused.set(false)
        stopProjection()
        selectedInput = null
        app.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.IDLE,
                inputLabel = null,
                inputRms = 0f,
                inputPeak = 0f,
                inputFrameCount = 0,
                inputAudibleFrameCount = 0,
                inputSignalActive = false,
                inputProcessingSummary = null,
                inputErrorMessage = null,
            )
        }
        updateNotification()
        if (app.broadcastRuntime.state.value.phase == BroadcastPhase.LIVE ||
            app.broadcastRuntime.state.value.phase == BroadcastPhase.PAUSED
        ) {
            startForegroundForBroadcast()
        }
        stopServiceIfUnused()
    }

    private fun failInput(message: String) {
        invalidateInputCapture()
        inputPaused.set(false)
        stopProjection()
        selectedInput = null
        app.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.FAILED,
                inputRms = 0f,
                inputPeak = 0f,
                inputFrameCount = 0,
                inputAudibleFrameCount = 0,
                inputSignalActive = false,
                inputProcessingSummary = null,
                inputErrorMessage = message,
            )
        }
        updateNotification()
        val phase = app.broadcastRuntime.state.value.phase
        if (phase == BroadcastPhase.STARTING ||
            phase == BroadcastPhase.LIVE ||
            phase == BroadcastPhase.PAUSED
        ) {
            startForegroundForBroadcast()
        } else {
            stopServiceIfUnused()
        }
    }

    private fun invalidateInputCapture() {
        inputGeneration.incrementAndGet()
        inputJob?.cancel()
        inputJob = null
    }

    private fun isInputGenerationCurrent(generation: Long): Boolean =
        inputGeneration.get() == generation

    private fun clearBroadcastSecrets(intent: Intent?) {
        intent?.getCharArrayExtra(EXTRA_PIN)?.fill('\u0000')
        intent?.getCharArrayExtra(EXTRA_SPEAKER_PIN)?.fill('\u0000')
    }

    private fun startBroadcast(intent: Intent) {
        val staleServer = synchronized(broadcastResourceLock) {
            val phase = app.broadcastRuntime.state.value.phase
            runningServer?.takeIf {
                phase == BroadcastPhase.IDLE || phase == BroadcastPhase.FAILED
            }
        }
        if (staleServer != null) {
            val closeFailure = closeServerHandle(staleServer)
            if (closeFailure != null) {
                clearBroadcastSecrets(intent)
                app.broadcastRuntime.update { current ->
                    current.copy(
                        phase = BroadcastPhase.FAILED,
                        errorMessage = "이전 방송 소켓을 아직 종료하지 못했습니다. " +
                            "다시 시작하면 안전하게 재시도합니다: " +
                            (closeFailure.message ?: closeFailure.javaClass.simpleName),
                    )
                }
                updateNotification()
                return
            }
        }
        synchronized(broadcastResourceLock) {
            if (runningServer != null) {
                clearBroadcastSecrets(intent)
                return
            }
            if (serverStartJob != null) {
                // Stop can cancel a start after its socket has bound but before runningServer is
                // attached. Keep the latest user request and replay it only after that job's
                // completion handler has closed the local server, so :8787 is never rebound early.
                clearBroadcastSecrets(pendingBroadcastStart?.intent)
                val incomingPin = intent.getCharArrayExtra(EXTRA_PIN)
                val incomingSpeakerPin = intent.getCharArrayExtra(EXTRA_SPEAKER_PIN)
                pendingBroadcastStart = PendingBroadcastStart(
                    intent = Intent(intent).also { deferred ->
                        incomingPin?.let { deferred.putExtra(EXTRA_PIN, it.copyOf()) }
                        incomingSpeakerPin?.let {
                            deferred.putExtra(EXTRA_SPEAKER_PIN, it.copyOf())
                        }
                    },
                    controlGeneration = broadcastGeneration.get(),
                )
                incomingPin?.fill('\u0000')
                incomingSpeakerPin?.fill('\u0000')
                return
            }
        }
        val generation = broadcastGeneration.incrementAndGet()
        val mode = intent.getStringExtra(EXTRA_ACCESS_MODE)
            ?.let { runCatching { OperatorAccessMode.valueOf(it) }.getOrNull() }
            ?: OperatorAccessMode.QR_TOKEN
        startForegroundForBroadcast()
        app.broadcastRuntime.update { current ->
            current.copy(
                phase = BroadcastPhase.STARTING,
                accessMode = mode,
                listenerUrl = null,
                speakerUrl = null,
                caSha256Fingerprint = null,
                caFingerprintWarning = null,
                listenerCount = 0,
                listenerDroppedFrames = 0,
                webSocketDeliveredFrameCount = 0,
                translationChannels = emptyList(),
                // Moonshine sequence ids are scoped to one recognition stream. A new broadcast
                // therefore starts a new transcript session instead of merging sequence 0 into
                // the previous event's script and completion markers.
                transcripts = emptyList(),
                errorMessage = null,
            )
        }

        val pin = intent.getCharArrayExtra(EXTRA_PIN)
        val speakerPin = intent.getCharArrayExtra(EXTRA_SPEAKER_PIN)
        val translationLanguages = intent.getStringArrayExtra(EXTRA_TRANSLATION_LANGUAGES)
            .orEmpty()
            .toList()
        val sourceLanguageTag = intent.getStringExtra(EXTRA_SOURCE_LANGUAGE)
            ?: DEFAULT_SOURCE_LANGUAGE_TAG
        val useGemma = intent.getBooleanExtra(EXTRA_USE_GEMMA, false)
        val selectiveTranslationRefinement = useGemma &&
            intent.getBooleanExtra(EXTRA_SELECTIVE_REFINEMENT, false)

        if (app.broadcastRuntime.state.value.translationTestActive) {
            stopTranslationTest(preservePass = true)
        }

        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                require(
                    translationLanguages.size <= MAX_TRANSLATION_LANGUAGES &&
                        translationLanguages.distinct().size == translationLanguages.size &&
                        translationLanguages.all(TRANSLATION_LANGUAGES::containsKey) &&
                        translationLanguages.none {
                            normalizeSourceLanguage(it) == normalizeSourceLanguage(sourceLanguageTag)
                        },
                ) { "지원하지 않는 번역 언어가 포함되어 있습니다." }
                requireSupportedSourceLanguage(sourceLanguageTag)
                runBroadcastServer(
                    mode = mode,
                    pin = pin,
                    speakerPin = speakerPin,
                    translationLanguages = translationLanguages,
                    sourceLanguageTag = sourceLanguageTag,
                    useGemma = useGemma,
                    selectiveTranslationRefinement = selectiveTranslationRefinement,
                    generation = generation,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (broadcastGeneration.get() == generation) {
                    releaseBroadcastResources()
                    app.broadcastRuntime.update { current ->
                        current.copy(
                            phase = BroadcastPhase.FAILED,
                            accessMode = null,
                            listenerUrl = null,
                            speakerUrl = null,
                            caSha256Fingerprint = null,
                            caFingerprintWarning = null,
                            listenerCount = 0,
                            listenerDroppedFrames = 0,
                            webSocketDeliveredFrameCount = 0,
                            translationChannels = emptyList(),
                            errorMessage = error.message ?: "방송을 시작하지 못했습니다.",
                        )
                    }
                    updateNotification()
                }
            }
        }
        synchronized(broadcastResourceLock) {
            serverStartJob = job
        }
        // A LAZY coroutine can be cancelled after start() schedules it but before its body enters.
        // A `finally` inside that body is then never executed, leaving serverStartJob as a permanent
        // teardown barrier and stranding the user's queued restart. Job completion handlers run for
        // that pre-entry cancellation as well as ordinary completion, so ownership is released in
        // exactly one place only after the previous start has fully stopped using :8787.
        job.invokeOnCompletion {
            pin?.fill('\u0000')
            speakerPin?.fill('\u0000')
            var deferredStart: PendingBroadcastStart? = null
            synchronized(broadcastResourceLock) {
                if (serverStartJob === job) {
                    serverStartJob = null
                    deferredStart = pendingBroadcastStart
                    pendingBroadcastStart = null
                }
            }
            deferredStart?.let { deferred ->
                Handler(Looper.getMainLooper()).post {
                    if (broadcastGeneration.get() == deferred.controlGeneration) {
                        startBroadcast(deferred.intent)
                    } else {
                        clearBroadcastSecrets(deferred.intent)
                        stopServiceIfUnused()
                    }
                }
            } ?: Handler(Looper.getMainLooper()).post {
                stopServiceIfUnused()
            }
        }
        job.start()
    }

    private fun startTranslationTest(intent: Intent) {
        if (translationTestJob != null ||
            app.broadcastRuntime.state.value.translationTestActive
        ) return
        val broadcastBusy = synchronized(broadcastResourceLock) {
            runningServer != null || serverStartJob != null || pendingBroadcastStart != null
        } || app.broadcastRuntime.state.value.phase.let { phase ->
            phase == BroadcastPhase.STARTING ||
                phase == BroadcastPhase.LIVE ||
                phase == BroadcastPhase.PAUSED
        }
        if (broadcastBusy) return
        if (app.broadcastRuntime.state.value.inputPhase != InputPhase.ACTIVE) {
            app.broadcastRuntime.update { current ->
                current.copy(translationTestMessage = "음성 입력을 먼저 시작하세요.")
            }
            return
        }
        val languageTag = intent.getStringExtra(EXTRA_TEST_LANGUAGE)
            ?.takeIf(TRANSLATION_LANGUAGES::containsKey)
            ?: run {
                app.broadcastRuntime.update { current ->
                    current.copy(translationTestMessage = "시험할 통역 언어를 선택하세요.")
                }
                return
            }
        val sourceLanguageTag = intent.getStringExtra(EXTRA_SOURCE_LANGUAGE)
            ?: DEFAULT_SOURCE_LANGUAGE_TAG
        if (runCatching { requireSupportedSourceLanguage(sourceLanguageTag) }.isFailure ||
            normalizeSourceLanguage(sourceLanguageTag) == normalizeSourceLanguage(languageTag)
        ) {
            app.broadcastRuntime.update { current ->
                current.copy(translationTestMessage = "원문과 다른 지원 출력 언어를 선택하세요.")
            }
            return
        }
        val useGemma = intent.getBooleanExtra(EXTRA_USE_GEMMA, true)
        val selectiveTranslationRefinement = useGemma &&
            intent.getBooleanExtra(EXTRA_SELECTIVE_REFINEMENT, false)
        val sessionId = beginTranslationSession()
        synchronized(translationResourceLock) {
            translationTestAudioEvidence = TranslationTestAudioEvidence(
                sessionId = sessionId,
                languageTag = languageTag,
            )
        }
        app.translationDiagnostics.begin()
        app.broadcastRuntime.update { current ->
            current.copy(
                transcripts = emptyList(),
                translationTestActive = true,
                translationTestLanguageTag = languageTag,
                translationTestPassed = false,
                translationTestMessage = "통역 엔진을 준비 중입니다. '말씀하세요'가 표시된 뒤 발화하세요.",
            )
        }
        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val preparation = prepareTranslationPipeline(
                    listOf(languageTag),
                    sourceLanguageTag,
                    useGemma,
                    sessionId,
                    awaitChannelPreparation = true,
                    selectiveTranslationRefinement = selectiveTranslationRefinement,
                )
                ensureTranslationSessionCurrent(sessionId)
                startPreviewPlayback(languageTag, sessionId)
                app.broadcastRuntime.update { current ->
                    if (!isTranslationSessionCurrent(sessionId)) return@update current
                    current.copy(
                        translationTestMessage = listOfNotNull(
                            "준비됐습니다. ${sourceLanguageTag.sourceLanguageDisplayName()}로 " +
                                "말씀하세요. 인식 중 문장이 바로 표시됩니다.",
                            preparation.warning,
                        ).joinToString(" · "),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                translationSessionCoordinator.handleSessionFailure(
                    sessionId = sessionId,
                    onStateUpdate = {
                        app.broadcastRuntime.update { current ->
                            current.copy(
                                translationTestActive = false,
                                translationTestPassed = false,
                                translationTestMessage = error.message ?: "통번역 시험을 시작하지 못했습니다.",
                            )
                        }
                    },
                    onReleaseResources = {
                        doReleaseTranslationTestResources()
                    },
                )
            } finally {
                if (translationTestJob === job) translationTestJob = null
            }
        }
        translationTestJob = job
        job.start()
    }

    private fun startPreviewPlayback(languageTag: String, sessionId: Long) {
        val track = createMonitorAudioTrack(
            MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
        )
        val channelId = languageTag.lowercase(Locale.ROOT)
        val subscription = translationPipeline?.streamSession?.subscribeLocalMonitor(channelId)
            ?: error("통역 음성 시험 세션이 종료되었습니다.")
        track.play()
        lateinit var playbackJob: Job
        playbackJob = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                for (frame in subscription.frames) {
                    val frameStats = frame.bytes.pcmS16LeSignalStats()
                    var offset = 0
                    while (offset < frame.bytes.size) {
                        val written = track.write(
                            frame.bytes,
                            offset,
                            frame.bytes.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        check(written > 0) { "통역 음성을 스피커로 재생하지 못했습니다." }
                        offset += written
                    }
                    if (frameStats.sampleCount > 0 &&
                        frameStats.nonZeroSamples > 0 &&
                        frameStats.rms > 0f &&
                        frameStats.peak > MIN_TRANSLATION_TEST_PEAK
                    ) {
                        recordTranslationTestPlaybackWrite(
                            sessionId = sessionId,
                            languageTag = languageTag,
                            writtenBytes = frame.bytes.size,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                translationSessionCoordinator.handleSessionFailure(
                    sessionId = sessionId,
                    onStateUpdate = {
                        recordTranslationTestPlaybackFailure(sessionId, languageTag)
                        app.broadcastRuntime.update { current ->
                            current.copy(
                                translationTestActive = false,
                                translationTestPassed = false,
                                translationTestMessage = error.message ?: "통역 음성 미리듣기가 중단됐습니다.",
                            )
                        }
                    },
                    onReleaseResources = {
                        doReleaseTranslationTestResources()
                    },
                )
            } finally {
                subscription.close()
            }
        }
        try {
            synchronized(translationResourceLock) {
                ensureTranslationSessionCurrent(sessionId)
                previewAudioTrack = track
                previewSubscription = subscription
                previewPlaybackJob = playbackJob
                playbackJob.start()
            }
        } catch (error: Throwable) {
            playbackJob.cancel()
            subscription.close()
            runCatching { track.stop() }
            track.release()
            throw error
        }
    }

    private fun createMonitorAudioTrack(sampleRateHz: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "통역 음성 스피커를 열지 못했습니다." }
        val targetBufferBytes = (sampleRateHz * 2 * LOCAL_MONITOR_BUFFER_MILLIS / 1_000)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, targetBufferBytes))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track ->
                check(track.state == AudioTrack.STATE_INITIALIZED) {
                    "통역 음성 스피커 초기화에 실패했습니다."
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val thresholdFrames = (sampleRateHz * LOCAL_MONITOR_START_THRESHOLD_MILLIS / 1_000)
                        .coerceIn(1, track.bufferCapacityInFrames)
                    runCatching { track.setStartThresholdInFrames(thresholdFrames) }
                        .getOrElse { error ->
                            track.release()
                            throw IllegalStateException("로컬 출력 시작 버퍼를 설정하지 못했습니다.", error)
                        }
                }
            }
    }

    private fun startLocalMonitor(intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_MONITOR_CHANNEL)
            ?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9_-]{0,23}")) }
            ?: error("모니터할 방송 채널을 선택하세요.")
        val state = app.broadcastRuntime.state.value
        check(state.phase == BroadcastPhase.LIVE || state.phase == BroadcastPhase.PAUSED) {
            "방송 서버가 열린 상태에서 사용할 수 있습니다."
        }
        val session = synchronized(broadcastResourceLock) { broadcastStreamSession }
            ?.takeIf(StreamSession::isActive)
            ?: error("현재 방송 오디오 세션이 없습니다.")
        val descriptor = session.descriptor(channelId) ?: error("현재 방송에 없는 채널입니다.")
        val requestedOutputDeviceId = intent.getIntExtra(EXTRA_MONITOR_OUTPUT_DEVICE_ID, Int.MIN_VALUE)
            .takeUnless { it == Int.MIN_VALUE }
            ?: error("모니터 출력 장치를 선택하세요.")
        val outputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == requestedOutputDeviceId }
            ?: error("선택한 출력 장치가 연결되어 있지 않습니다.")
        val requestedRoute = outputDevice.toLocalMonitorRoute()
        val monitorInput = selectedInput ?: app.audioInputRepository.selectedDevice.value
        if (monitorInput != null) {
            check(
                LocalMonitorOutputRoutePlanner.eligible(monitorInput, listOf(requestedRoute)).isNotEmpty(),
            ) { "현재 입력과 함께 사용할 수 없는 출력 장치입니다." }
        }

        stopLocalMonitor(updateRuntime = false)
        val generation = localMonitorGeneration.incrementAndGet()
        localMonitorPaused.set(false)
        app.broadcastRuntime.update { current ->
            current.copy(
                localMonitor = LocalMonitorSnapshot(
                    phase = LocalMonitorPhase.STARTING,
                    channelId = channelId,
                    channelLabel = descriptor.displayName,
                    outputRouteLabel = "시스템 미디어 출력 확인 중",
                    requestedOutputDeviceId = requestedOutputDeviceId,
                    volume = state.localMonitor.volume,
                ),
            )
        }

        val track = createMonitorAudioTrack(descriptor.sampleRateHz)
        track.setVolume(state.localMonitor.volume)
        if (!track.setPreferredDevice(outputDevice)) {
            track.release()
            error("Android가 선택한 출력 장치를 로컬 모니터에 지정하지 못했습니다.")
        }
        val subscription = try {
            session.subscribeLocalMonitor(channelId)
        } catch (error: Throwable) {
            track.setPreferredDevice(null)
            track.release()
            throw error
        }
        lateinit var job: Job
        job = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var renderedFrames = 0L
            var renderedNonSilentFrames = 0L
            var lastRoute: LocalMonitorRoute? = null
            try {
                track.play()
                primeMonitorRoute(track, descriptor.sampleRateHz)
                val initialRoute = awaitMonitorRoute(track)
                    ?: throw LocalMonitorFeedbackBlockedException(
                        "Android 실제 출력 경로를 2초 안에 확인하지 못해 로컬 모니터만 중지했습니다.",
                    )
                if (initialRoute.platformId != requestedOutputDeviceId) {
                    throw LocalMonitorFeedbackBlockedException(
                        "선택한 ${requestedRoute.label} 대신 ${initialRoute.label}로 연결되어 로컬 출력만 중지했습니다.",
                    )
                }
                val initialDecision = evaluateLocalMonitorRoute(initialRoute)
                if (!initialDecision.mayRender) {
                    throw LocalMonitorFeedbackBlockedException(
                        initialDecision.warning ?: "로컬 출력 경로를 확인하지 못했습니다.",
                    )
                }
                lastRoute = initialRoute
                updateLocalMonitorPlayback(
                    generation = generation,
                    phase = LocalMonitorPhase.PLAYING,
                    route = initialRoute,
                    renderedFrames = 0L,
                    renderedNonSilentFrames = 0L,
                    warning = initialDecision.warning,
                )

                for (frame in subscription.frames) {
                    val frameEpoch = localMonitorPlaybackEpoch.get()
                    if (localMonitorGeneration.get() != generation) return@launch
                    if (localMonitorPaused.get()) continue
                    val route = track.routedDevice?.toLocalMonitorRoute()
                        ?: awaitMonitorRoute(track)
                        ?: throw LocalMonitorFeedbackBlockedException(
                            "출력 장치 연결을 확인하지 못해 로컬 모니터만 중지했습니다. 방송과 입력은 계속됩니다.",
                        )
                    if (route.platformId != requestedOutputDeviceId) {
                        throw LocalMonitorFeedbackBlockedException(
                            "출력이 ${route.label}로 바뀌어 로컬 모니터만 중지했습니다. 방송과 입력은 계속됩니다.",
                        )
                    }
                    val decision = evaluateLocalMonitorRoute(route)
                    if (!decision.mayRender) {
                        throw LocalMonitorFeedbackBlockedException(
                            decision.warning ?: "하울링 위험으로 로컬 출력만 중지했습니다.",
                        )
                    }
                    fun isCurrentFrame() = localMonitorGeneration.get() == generation &&
                        localMonitorPlaybackEpoch.get() == frameEpoch && !localMonitorPaused.get()
                    val complete = writeLocalMonitorPcm(
                        frame.bytes,
                        isCurrent = ::isCurrentFrame,
                        writeNonBlocking = { bytes, offset, count ->
                            synchronized(localMonitorLock) {
                                if (!isCurrentFrame()) 0 else
                                    track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                            }
                        },
                    )
                    if (!complete) continue
                    renderedFrames += 1L
                    val stats = frame.bytes.pcmS16LeSignalStats()
                    if (stats.nonZeroSamples > 0 && stats.peak > MIN_TRANSLATION_TEST_PEAK) {
                        renderedNonSilentFrames += 1L
                    }
                    if (route != lastRoute || renderedFrames % LOCAL_MONITOR_UI_FRAME_CADENCE == 0L) {
                        lastRoute = route
                        updateLocalMonitorPlayback(
                            generation = generation,
                            phase = LocalMonitorPhase.PLAYING,
                            route = route,
                            renderedFrames = renderedFrames,
                            renderedNonSilentFrames = renderedNonSilentFrames,
                            warning = decision.warning,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (localMonitorGeneration.get() == generation) {
                    app.broadcastRuntime.update { current ->
                        current.copy(
                            localMonitor = current.localMonitor.copy(
                                phase = LocalMonitorPhase.FAILED,
                                errorMessage = error.message
                                    ?: "로컬 방송 모니터가 중단됐습니다.",
                            ),
                        )
                    }
                }
            } finally {
                subscription.close()
                synchronized(localMonitorLock) {
                    if (localMonitorJob === job) {
                        localMonitorJob = null
                        localMonitorSubscription = null
                        localMonitorAudioTrack = null
                    }
                }
                // The playback coroutine is the sole release owner. A control action may stop
                // the track to unblock WRITE_BLOCKING, but never releases it concurrently.
                releaseMonitorAudioTrack(track)
            }
        }
        try {
            synchronized(localMonitorLock) {
                check(localMonitorGeneration.get() == generation)
                localMonitorJob = job
                localMonitorSubscription = subscription
                localMonitorAudioTrack = track
                job.start()
            }
        } catch (error: Throwable) {
            job.cancel()
            subscription.close()
            releaseMonitorAudioTrack(track)
            throw error
        }
    }

    private fun primeMonitorRoute(track: AudioTrack, sampleRateHz: Int) {
        val primingFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            track.startThresholdInFrames
        } else {
            track.bufferCapacityInFrames
        }.coerceAtLeast(sampleRateHz / 50)
        val silence = ByteArray(primingFrames * 2)
        val written = track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
        check(written == silence.size) { "시스템 미디어 출력 경로를 열지 못했습니다." }
    }

    private suspend fun awaitMonitorRoute(track: AudioTrack): LocalMonitorRoute? {
        repeat(LOCAL_MONITOR_ROUTE_WAIT_ATTEMPTS) {
            track.routedDevice?.let { return it.toLocalMonitorRoute() }
            delay(LOCAL_MONITOR_ROUTE_WAIT_STEP_MILLIS)
        }
        return null
    }

    private fun evaluateLocalMonitorRoute(route: LocalMonitorRoute): LocalMonitorFeedbackDecision {
        val inputActive = app.broadcastRuntime.state.value.inputPhase.let { phase ->
            phase == InputPhase.ACTIVE || phase == InputPhase.PAUSED
        }
        return LocalMonitorFeedbackPolicy.evaluate(
            input = selectedInput,
            inputActive = inputActive,
            output = route,
        )
    }

    private fun updateLocalMonitorPlayback(
        generation: Long,
        phase: LocalMonitorPhase,
        route: LocalMonitorRoute,
        renderedFrames: Long,
        renderedNonSilentFrames: Long,
        warning: String?,
    ) {
        if (localMonitorGeneration.get() != generation) return
        app.broadcastRuntime.update { current ->
            if (localMonitorGeneration.get() != generation) return@update current
            current.copy(
                localMonitor = current.localMonitor.copy(
                    phase = if (localMonitorPaused.get()) LocalMonitorPhase.PAUSED else phase,
                    outputRouteLabel = route.label,
                    renderedFrames = renderedFrames,
                    renderedNonSilentFrames = renderedNonSilentFrames,
                    warning = warning,
                    errorMessage = null,
                ),
            )
        }
    }

    private fun pauseLocalMonitor() {
        synchronized(localMonitorLock) {
            val track = localMonitorAudioTrack ?: return
            localMonitorPaused.set(true)
            localMonitorPlaybackEpoch.incrementAndGet()
            runCatching { track.pause() }
            runCatching { track.flush() }
        }
        app.broadcastRuntime.update { current ->
            current.copy(
                localMonitor = current.localMonitor.copy(phase = LocalMonitorPhase.PAUSED),
            )
        }
    }

    private fun resumeLocalMonitor() {
        val track = synchronized(localMonitorLock) { localMonitorAudioTrack } ?: return
        localMonitorPaused.set(false)
        runCatching { track.play() }.getOrElse { error ->
            failLocalMonitor(error.message ?: "로컬 모니터를 다시 재생하지 못했습니다.")
            return
        }
        app.broadcastRuntime.update { current ->
            current.copy(
                localMonitor = current.localMonitor.copy(phase = LocalMonitorPhase.PLAYING),
            )
        }
    }

    private fun stopLocalMonitor(updateRuntime: Boolean = true) {
        localMonitorGeneration.incrementAndGet()
        localMonitorPaused.set(false)
        val resources = synchronized(localMonitorLock) {
            Triple(localMonitorJob, localMonitorSubscription, localMonitorAudioTrack).also {
                localMonitorJob = null
                localMonitorSubscription = null
                localMonitorAudioTrack = null
            }
        }
        resources.first?.cancel()
        resources.second?.close()
        resources.third?.let(::stopMonitorAudioTrack)
        if (updateRuntime) {
            app.broadcastRuntime.update { current ->
                current.copy(localMonitor = LocalMonitorSnapshot(volume = current.localMonitor.volume))
            }
        }
    }

    private fun setLocalMonitorVolume(intent: Intent) {
        val gain = intent.getFloatExtra(EXTRA_MONITOR_VOLUME, 0.25f)
        require(gain.isFinite() && gain in 0f..1f) { "모니터 음량 범위가 올바르지 않습니다." }
        synchronized(localMonitorLock) { localMonitorAudioTrack?.setVolume(gain) }
        app.broadcastRuntime.update { it.copy(localMonitor = it.localMonitor.copy(volume = gain)) }
    }

    private fun releaseMonitorAudioTrack(track: AudioTrack) {
        stopMonitorAudioTrack(track)
        runCatching { track.setPreferredDevice(null) }
        runCatching { track.release() }
    }

    private fun stopMonitorAudioTrack(track: AudioTrack) {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
    }

    private fun failLocalMonitor(message: String) {
        stopLocalMonitor(updateRuntime = false)
        app.broadcastRuntime.update { current ->
            current.copy(
                localMonitor = current.localMonitor.copy(
                    phase = LocalMonitorPhase.FAILED,
                    errorMessage = message,
                ),
            )
        }
    }

    private fun stopTranslationTest(preservePass: Boolean = true) {
        translationTestJob?.cancel()
        translationTestJob = null
        releaseTranslationTestResources()
        app.broadcastRuntime.update { current ->
            current.copy(
                translationTestActive = false,
                translationTestPassed = current.translationTestPassed && preservePass,
                translationTestMessage = if (current.translationTestPassed && preservePass) {
                    "통역 음성 확인 결과를 유지했습니다. 방송 여부는 운영자가 판단하세요."
                } else {
                    "통번역 시험이 중지됐습니다."
                },
            )
        }
    }

    private fun releaseTranslationTestResources(expectedSessionId: Long? = null): Boolean {
        return translationSessionCoordinator.releaseResources(expectedSessionId) {
            doReleaseTranslationTestResources()
        }
    }

    private fun doReleaseTranslationTestResources() {
        previewPlaybackJob?.cancel()
        previewPlaybackJob = null
        previewSubscription?.close()
        previewSubscription = null
        previewAudioTrack?.let { track ->
            runCatching { track.stop() }
            track.release()
        }
        previewAudioTrack = null
        translationHealthJob?.cancel()
        translationHealthJob = null
        translationSupportPreparationJob?.cancel()
        translationSupportPreparationJob = null
        recognitionFrames?.close()
        recognitionFrames = null
        translationPipeline?.close()
        translationPipeline = null
        sharedTranslationQueue?.close()
        sharedTranslationQueue = null
        translationPreparationOwner?.let(
            app::releaseSpeechSynthesisBroadcastOwnerIfInitialized,
        )
        translationBackendUseLease?.close()
        translationBackendUseLease = null
        translationPreparationOwner?.let(app::endPreparation)
        translationPreparationOwner = null
        translationProviderWarning = null
        translationTestAudioEvidence = null
        app.translationDiagnostics.end()
    }

    private suspend fun runBroadcastServer(
        mode: OperatorAccessMode,
        pin: CharArray?,
        speakerPin: CharArray?,
        translationLanguages: List<String>,
        sourceLanguageTag: String,
        useGemma: Boolean,
        generation: Long,
        selectiveTranslationRefinement: Boolean = false,
    ) {
        val network = LocalNetworkAddressResolver.resolve()
            ?: error("핫스팟 또는 사설 Wi-Fi 주소를 찾지 못했습니다.")
        val access = when (mode) {
            OperatorAccessMode.QR_TOKEN -> BroadcastAccess.QrToken
            OperatorAccessMode.OPEN -> BroadcastAccess.Open
            OperatorAccessMode.PIN -> BroadcastAccess.Pin.from(
                pin ?: error("PIN을 입력하세요."),
            )
        }
        val speakerAccess = speakerPin?.let { SpeakerAccess.Pin.from(it) } ?: SpeakerAccess.Open

        val isDirectAppOutput = translationLanguages.isEmpty() &&
            app.audioInputRepository.selectedDevice.value?.kind == AudioInputKind.DEVICE_PLAYBACK
        val directSourceLabel = if (isDirectAppOutput) "앱 출력" else "원음"
        val gemmaPrioritySupported = translationLanguages.any { target ->
            GemmaTranslationProvider.supportsTranslation(sourceLanguageTag, target)
        }
        val useGemmaForPriority = useGemma && gemmaPrioritySupported
        val archiveSessionId = if (translationLanguages.isNotEmpty()) {
            app.transcriptArchive.beginSession(sourceLanguageTag)
        } else {
            null
        }
        val channelDescriptors = listOf(
                AudioChannelDescriptor(
                    "source",
                    directSourceLabel,
                    if (isDirectAppOutput) "und" else sourceLanguageTag,
                    SAMPLE_RATE_HZ,
                ),
            ) + translationLanguages.map { languageTag ->
                AudioChannelDescriptor(
                    id = languageTag.lowercase(Locale.ROOT),
                    displayName = requireNotNull(TRANSLATION_LANGUAGES[languageTag]),
                    languageTag = languageTag,
                    sampleRateHz = MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
                )
        }
        // Configure once, before binding the server. The same immutable session handle is handed
        // to the web server, direct-audio producer, translation workers and test-tone producer.
        // A later broadcast generation therefore cannot receive stale same-ID PCM or listeners.
        val streamSession = app.audioStreams.configure(channelDescriptors)
        val publicationCoordinator = ChannelAudioPublicationCoordinator(
            channelDescriptors.map(AudioChannelDescriptor::id),
        )
        val transcriptPublication = BroadcastTranscriptPublication(
            archiveSessionId = archiveSessionId,
            archiveSnapshot = { app.transcriptArchive.snapshot.value },
            runtimeSnapshot = { app.broadcastRuntime.state.value },
        )
        val server = try {
            GuideCastLocalServer(
                context = this,
                streams = app.audioStreams,
                bindAllInterfacesForDebug = BuildConfig.DEBUG,
                isSpeakerInputReady = {
                    val state = app.broadcastRuntime.state.value
                    app.audioInputRepository.selectedDevice.value?.kind == AudioInputKind.WEB_SPEAKER &&
                        state.inputPhase == InputPhase.ACTIVE &&
                        WebAudioInputBridge.subscriptionCount.value > 0
                },
                onSpeakerSessionOpened = {
                    val sessionGen = WebAudioInputBridge.openSession()
                    app.broadcastRuntime.update { current ->
                        current.copy(webSpeakerConnected = true)
                    }
                    sessionGen
                },
                onSpeakerSessionClosed = { sessionGen ->
                    val wasActive = WebAudioInputBridge.closeSession(sessionGen)
                    if (wasActive) {
                        app.broadcastRuntime.update { current ->
                            current.copy(webSpeakerConnected = false)
                        }
                    }
                },
                onSpeakerFrameReceived = { pcmBytes, sessionGen ->
                    WebAudioInputBridge.emitFrame(
                        PcmFrame(
                            bytes = pcmBytes,
                            sampleRateHz = 16_000,
                            capturedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                        ),
                        generation = sessionGen,
                    )
                },
                transcriptSnapshotProvider = transcriptPublication::snapshot,
            ).start(
                bindAddress = network.address,
                config = GuideCastServerConfig(
                    access = access,
                    speakerAccess = speakerAccess,
                ),
                streamSession = streamSession,
            )
        } catch (error: Throwable) {
            streamSession.close()
            throw error
        }
        var prepareJob: Job? = null
        lateinit var countJob: Job
        try {
            coroutineContext.ensureActive()
            synchronized(broadcastResourceLock) {
                if (broadcastGeneration.get() != generation) {
                    throw CancellationException("A newer broadcast session replaced this start request")
                }
                broadcastUsesTranslation = translationLanguages.isNotEmpty()
                runningServer = server
                broadcastStreamSession = streamSession
                broadcastAudioPublicationCoordinator = publicationCoordinator
                acquireWakeLock()
                app.broadcastRuntime.update { current ->
                    current.copy(
                        phase = BroadcastPhase.LIVE,
                        listenerUrl = server.listenerUrl,
                        speakerUrl = server.speakerUrl,
                        caSha256Fingerprint = server.caSha256Fingerprint,
                        caFingerprintWarning = server.caFingerprintWarning,
                        listenerCount = 0,
                        listenerDroppedFrames = 0,
                        webSocketDeliveredFrameCount = 0,
                        translationChannels = translationLanguages.mapIndexed { index, languageTag ->
                            val channelId = languageTag.lowercase(Locale.ROOT)
                            BroadcastChannelSnapshot(
                                channelId = channelId,
                                languageTag = languageTag,
                                displayName = requireNotNull(TRANSLATION_LANGUAGES[languageTag]),
                                listenerUrl = server.listenerUrlFor(channelId),
                                translationProvider = when {
                                    useGemmaForPriority && GemmaTranslationProvider.supportsTranslation(sourceLanguageTag, languageTag) -> "공유 Gemma → ML Kit"
                                    else -> "ML Kit"
                                },
                                synthesisProvider = if (languageTag in MOONSHINE_TTS_LANGUAGES) {
                                    "Moonshine → Galaxy 오프라인"
                                } else {
                                    "Galaxy 오프라인"
                                },
                            )
                        },
                        channelSummary = if (translationLanguages.isEmpty()) {
                            directSourceLabel
                        } else {
                            val providerLabel = preparingTranslationProviderLabel(
                                translationLanguages = translationLanguages,
                                useGemma = useGemmaForPriority,
                                displayName = TRANSLATION_LANGUAGES::getValue,
                            )
                            "원음 병행 · $providerLabel · " + translationLanguages.joinToString {
                                requireNotNull(TRANSLATION_LANGUAGES[it])
                            }
                        },
                        translationWarning = if (translationLanguages.isEmpty()) {
                            null
                        } else {
                            "통역 엔진을 준비 중입니다. 방송 서버는 운영자 제어에 따라 이미 열렸습니다."
                        },
                        errorMessage = null,
                    )
                }
                updateNotification()

                if (translationLanguages.isNotEmpty()) {
                    val sessionId = beginTranslationSession()
                    app.translationDiagnostics.begin()
                    prepareJob = createTranslationPreparationJob(
                        translationLanguages = translationLanguages,
                        sourceLanguageTag = sourceLanguageTag,
                        useGemma = useGemma,
                        selectiveTranslationRefinement = selectiveTranslationRefinement,
                        sessionId = sessionId,
                        archiveSessionId = archiveSessionId,
                        broadcastSession = generation,
                        streamSession = streamSession,
                        audioPublicationCoordinator = publicationCoordinator,
                    ).also { translationPreparationJob = it }
                }

                countJob = serviceScope.launch(start = CoroutineStart.LAZY) {
                    var lastAppliedSnapshot: AudioStreamObservabilitySnapshot? = null
                    while (true) {
                        coroutineContext.ensureActive()
                        val snapshot = streamSession.observabilitySnapshot()
                        if (broadcastGeneration.get() != generation ||
                            snapshot.generation != streamSession.generation ||
                            !snapshot.isActive
                        ) {
                            return@launch
                        }
                        if (snapshot != lastAppliedSnapshot) {
                            lastAppliedSnapshot = snapshot
                            app.broadcastRuntime.update { current ->
                                if (broadcastGeneration.get() != generation) return@update current
                                if (current.phase == BroadcastPhase.LIVE ||
                                    current.phase == BroadcastPhase.PAUSED
                                ) {
                                    current.copy(
                                        listenerCount = snapshot.totalListeners,
                                        listenerDroppedFrames = snapshot.droppedFrames,
                                        webSocketDeliveredFrameCount =
                                            snapshot.webSocketDeliveredFrames,
                                        translationChannels = current.translationChannels.map { channel ->
                                            channel.copy(
                                                listenerCount =
                                                    snapshot.listenersByChannel[channel.channelId] ?: 0,
                                                listenerDroppedFrames =
                                                    snapshot.droppedFramesByChannel[channel.channelId] ?: 0,
                                                webSocketDeliveredFrameCount =
                                                    snapshot.webSocketDeliveredFramesByChannel[
                                                        channel.channelId
                                                    ] ?: 0,
                                                lastWebSocketDeliveredSequence =
                                                    snapshot.lastWebSocketDeliveredSequenceByChannel[
                                                        channel.channelId
                                                    ],
                                            )
                                        },
                                    )
                                } else {
                                    current
                                }
                            }
                            updateNotification()
                        }
                        delay(STREAM_OBSERVABILITY_REFRESH_MILLIS)
                    }
                }
                listenerJob = countJob
            }
        } catch (error: Throwable) {
            // Session invalidation must happen even if Ktor throws while releasing its socket.
            // The original startup error remains the primary failure reported to the operator.
            streamSession.close()
            closeServerHandle(server)?.let { stopError ->
                Log.e(LOG_TAG, "Server cleanup after failed start also failed", stopError)
            }
            throw error
        }
        prepareJob?.start()
        countJob.start()
    }

    private fun createTranslationPreparationJob(
        translationLanguages: List<String>,
        sourceLanguageTag: String,
        useGemma: Boolean,
        sessionId: Long,
        archiveSessionId: Long?,
        broadcastSession: Long,
        streamSession: StreamSession,
        audioPublicationCoordinator: ChannelAudioPublicationCoordinator,
        selectiveTranslationRefinement: Boolean = false,
    ): Job {
        lateinit var job: Job
        job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                val preparation = prepareTranslationPipeline(
                    translationLanguages,
                    sourceLanguageTag,
                    useGemma,
                    sessionId,
                    archiveSessionId = archiveSessionId,
                    awaitChannelPreparation = false,
                    streamSession = streamSession,
                    audioPublicationCoordinator = audioPublicationCoordinator,
                    selectiveTranslationRefinement = selectiveTranslationRefinement,
                )
                ensureTranslationSessionCurrent(sessionId)
                app.broadcastRuntime.update { current ->
                    if (broadcastGeneration.get() != broadcastSession ||
                        !isTranslationSessionCurrent(sessionId)
                    ) {
                        return@update current
                    }
                    current.copy(
                        channelSummary = preparation.providerLabel + " · " +
                            translationLanguages.joinToString {
                                requireNotNull(TRANSLATION_LANGUAGES[it])
                            },
                        translationWarning = preparation.warning,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                synchronized(translationResourceLock) {
                    if (isTranslationSessionCurrent(sessionId) &&
                        broadcastGeneration.get() == broadcastSession
                    ) {
                        translationHealthJob?.cancel()
                        translationHealthJob = null
                        recognitionFrames?.close()
                        recognitionFrames = null
                        translationPipeline?.close()
                        translationPipeline = null
                        app.translationDiagnostics.end()
                        app.broadcastRuntime.update { current ->
                            current.copy(
                                translationWarning = "통역 음원 준비 안 됨 · " +
                                    (error.message ?: error.javaClass.simpleName) +
                                    " · 방송 시작/중지는 운영자가 결정합니다.",
                            )
                        }
                    }
                }
            } finally {
                if (translationPreparationJob === job) translationPreparationJob = null
            }
        }
        return job
    }

    private fun pauseBroadcast() {
        if (app.broadcastRuntime.state.value.phase != BroadcastPhase.LIVE) return
        app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.PAUSED) }
        updateNotification()
    }

    private fun resumeBroadcast() {
        if (app.broadcastRuntime.state.value.phase != BroadcastPhase.PAUSED) return
        app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.LIVE) }
        updateNotification()
    }

    private fun stopBroadcast() {
        releaseBroadcastResources()
        app.broadcastRuntime.update { current ->
            current.copy(
                phase = BroadcastPhase.IDLE,
                accessMode = null,
                listenerUrl = null,
                speakerUrl = null,
                caSha256Fingerprint = null,
                caFingerprintWarning = null,
                listenerCount = 0,
                listenerDroppedFrames = 0,
                webSocketDeliveredFrameCount = 0,
                translationChannels = emptyList(),
                channelSummary = null,
                translationWarning = null,
                testToneActive = false,
                errorMessage = null,
            )
        }
        updateNotification()
        stopServiceIfUnused()
    }

    private fun playTestTone() {
        val current = app.broadcastRuntime.state.value
        if (current.phase != BroadcastPhase.LIVE) return
        if (!testToneRequestPending.compareAndSet(false, true)) return
        val generation = broadcastGeneration.get()
        val requestGeneration = testToneRequestGeneration.incrementAndGet()
        serviceScope.launch {
            var publicationLease: java.io.Closeable? = null
            var markedActive = false
            try {
                val (streamSession, publicationCoordinator) =
                    synchronized(broadcastResourceLock) {
                        broadcastStreamSession to broadcastAudioPublicationCoordinator
                    }
                if (streamSession == null || publicationCoordinator == null) return@launch
                publicationLease = publicationCoordinator.acquireAllChannels(
                    timeoutMillis = TEST_TONE_ACQUIRE_TIMEOUT_MILLIS,
                )
                if (publicationLease == null) {
                    if (broadcastGeneration.get() == generation &&
                        testToneRequestGeneration.get() == requestGeneration
                    ) {
                        app.broadcastRuntime.update { state ->
                            if (broadcastGeneration.get() != generation) return@update state
                            state.copy(
                                translationWarning = listOfNotNull(
                                    state.translationWarning,
                                    "테스트음 대기 시간이 초과됐습니다. 방송 입력과 언어 채널은 그대로 계속됩니다.",
                                ).distinct().joinToString(" · "),
                            )
                        }
                    }
                    return@launch
                }
                if (broadcastGeneration.get() != generation ||
                    testToneRequestGeneration.get() != requestGeneration ||
                    !streamSession.isActive()
                ) {
                    return@launch
                }
                testToneActive.set(true)
                markedActive = true
                app.broadcastRuntime.update { state ->
                    if (broadcastGeneration.get() == generation &&
                        testToneRequestGeneration.get() == requestGeneration
                    ) {
                        state.copy(testToneActive = true)
                    } else {
                        state
                    }
                }
                val channels = streamSession.channels
                val generators = channels.associate { channel ->
                    channel.id to PcmSineWaveGenerator(
                        sampleRateHz = channel.sampleRateHz,
                        frequencyHz = TEST_TONE_HZ,
                        amplitude = TEST_TONE_AMPLITUDE,
                    )
                }
                repeat(TEST_TONE_FRAME_COUNT) {
                    if (broadcastGeneration.get() != generation ||
                        testToneRequestGeneration.get() != requestGeneration
                    ) {
                        return@launch
                    }
                    channels.forEach { channel ->
                        streamSession.tryPublish(
                            channel.id,
                            PcmAudioFrame(
                                bytes = requireNotNull(generators[channel.id])
                                    .nextFrame(TEST_TONE_FRAME_MILLIS),
                                capturedAtElapsedRealtimeNanos =
                                    SystemClock.elapsedRealtimeNanos(),
                            ),
                        )
                    }
                    delay(TEST_TONE_FRAME_MILLIS.toLong())
                }
            } finally {
                // Clear the suppression flag before releasing channel leases so waiting live
                // input resumes on the first frame after the tone, never one frame later.
                if (markedActive && testToneRequestGeneration.get() == requestGeneration) {
                    testToneActive.set(false)
                    app.broadcastRuntime.update { state ->
                        if (broadcastGeneration.get() == generation &&
                            testToneRequestGeneration.get() == requestGeneration
                        ) {
                            state.copy(testToneActive = false)
                        } else {
                            state
                        }
                    }
                }
                publicationLease?.close()
                if (testToneRequestGeneration.compareAndSet(
                        requestGeneration,
                        requestGeneration + 1,
                    )
                ) {
                    testToneRequestPending.set(false)
                }
            }
        }
    }

    private suspend fun prepareTranslationPipeline(
        translationLanguages: List<String>,
        sourceLanguageTag: String,
        useGemma: Boolean,
        sessionId: Long,
        archiveSessionId: Long? = null,
        awaitChannelPreparation: Boolean,
        streamSession: StreamSession? = null,
        audioPublicationCoordinator: ChannelAudioPublicationCoordinator? = null,
        selectiveTranslationRefinement: Boolean = false,
    ): TranslationPreparationResult {
        ensureTranslationSessionCurrent(sessionId)
        require(translationLanguages.size in 1..MAX_TRANSLATION_LANGUAGES)
        requireSupportedSourceLanguage(sourceLanguageTag)
        require(translationLanguages.none {
            normalizeSourceLanguage(it) == normalizeSourceLanguage(sourceLanguageTag)
        }) { "원문과 출력 언어는 서로 달라야 합니다." }
        val preparationOwner = app.beginBroadcastPreparation(
            sourceLanguageTag = sourceLanguageTag,
            targetLanguageTags = translationLanguages.toSet(),
        )
        acquireAndAttachTranslationBackendOwner(
            owner = preparationOwner,
            acquire = {
                app.acquireTranslationBackendUseIf(
                    isOwnerCurrent = { isTranslationSessionCurrent(sessionId) },
                    supersedeSettingsStandby = true,
                )
            },
            attach = { owner, backendUseLease ->
                synchronized(translationResourceLock) {
                    if (!isTranslationSessionCurrent(sessionId)) return@synchronized false
                    translationBackendUseLease?.close()
                    translationPreparationOwner?.let(app::endPreparation)
                    translationBackendUseLease = backendUseLease
                    translationPreparationOwner = owner
                    true
                }
            },
            releaseLease = TranslationBackendUseLease::close,
            endOwner = app::endPreparation,
        )
        val gemmaCapability = GemmaBroadcastCapability.detect(this)
        val gemmaLiveActive = AtomicBoolean(false)
        val gemmaReloadRequiresAdmission = AtomicBoolean(false)
        val serializeNativeColdLoads = shouldSerializeNativeColdLoads(
            constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
            languageCount = translationLanguages.size,
            unconstrainedLoadPreferred = gemmaCapability.loadPermittedNow,
        )
        val nativeTranslationGate = NativeFirstUseGate(
            maxParallelInitializations = translationSupportPreparationParallelism(
                constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
                languageCount = translationLanguages.size,
            ),
            maxParallelReloads = translationSupportReloadParallelism(
                constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
                languageCount = translationLanguages.size,
            ),
            // Healthy Binder workers keep the live fast path. A reclaimed worker fails the cheap
            // liveness probe, re-arms this key and enters the separately bounded reload lane.
            guardEveryOperation = false,
            beforeFirstUse = { key, _ ->
                if (key == MLKIT_RECONCILIATION_FIRST_USE_KEY) {
                    acquireProcessNativeColdLoadLease(
                        key = ProcessNativeColdLoadKeys.MLKIT_RECONCILIATION,
                        serializeWithAllColdLoads = true,
                    )
                } else if (key.startsWith("$GEMMA_NATIVE_FIRST_USE_KEY_PREFIX:") &&
                    (gemmaReloadRequiresAdmission.get() ||
                        !app.gemmaTranslationProvider.hasActivePreparedWorker())
                ) {
                    acquireProcessNativeColdLoadLease(
                        // Gemma owns one process/model regardless of the selected target.
                        key = ProcessNativeColdLoadKeys.GEMMA_MODEL,
                        serializeWithAllColdLoads = serializeNativeColdLoads,
                    )
                } else if (key.startsWith("$MLKIT_NATIVE_FIRST_USE_KEY_PREFIX:")
                ) {
                    val languageTag = key.substringAfterLast(':')
                    if (!app.translationProvider.hasActivePreparedWorker(languageTag)) {
                        acquireProcessNativeColdLoadLease(
                            key = ProcessNativeColdLoadKeys.mlKitTranslation(languageTag),
                            serializeWithAllColdLoads = serializeNativeColdLoads,
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            },
            isInitializationCurrent = { key ->
                when {
                    key.startsWith("$GEMMA_NATIVE_FIRST_USE_KEY_PREFIX:") ->
                        app.gemmaTranslationProvider.hasActivePreparedWorker()

                    key.startsWith("$MLKIT_NATIVE_FIRST_USE_KEY_PREFIX:") ->
                        app.translationProvider.hasActivePreparedWorker(key.substringAfterLast(':'))

                    else -> true
                }
            },
        )
        val nativeSpeechGate = NativeFirstUseGate(
            maxParallelInitializations = translationSupportPreparationParallelism(
                constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
                languageCount = translationLanguages.size,
            ),
            maxParallelReloads = translationSupportReloadParallelism(
                constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
                languageCount = translationLanguages.size,
            ),
            // Voice reload is independently bounded. A slow or unavailable voice must never hold
            // the translation gate and delay web scripts.
            guardEveryOperation = false,
            beforeFirstUse = { key, _ ->
                if (key.startsWith("$SPEECH_NATIVE_FIRST_USE_KEY_PREFIX:") ||
                    key.startsWith("$SPEECH_NATIVE_WARMUP_KEY_PREFIX:")
                ) {
                    val languageTag = key.substringAfterLast(':')
                    if (!app.speechSynthesisProvider.hasActiveMoonshineWorker(languageTag)) {
                        acquireProcessNativeColdLoadLease(
                            // Explicit warm-up and first live PCM are two gate purposes for the
                            // same language worker. Share one process-wide key so they cannot load
                            // that native engine beside each other.
                            key = ProcessNativeColdLoadKeys.speech(languageTag),
                            serializeWithAllColdLoads = serializeNativeColdLoads,
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }
            },
            isInitializationCurrent = { key ->
                if (key.startsWith("$SPEECH_NATIVE_WARMUP_KEY_PREFIX:")) {
                    app.speechSynthesisProvider.hasActiveMoonshineWorker(
                        key.substringAfterLast(':'),
                    )
                } else if (key.startsWith("$SPEECH_NATIVE_FIRST_USE_KEY_PREFIX:")) {
                    val languageTag = key.substringAfterLast(':')
                    app.speechSynthesisProvider.hasActiveMoonshineWorker(languageTag) ||
                        app.speechSynthesisProvider.isFallbackReady(languageTag)
                } else {
                    true
                }
            },
        )
        app.translationDiagnostics.stage(TranslationRunStage.SPEECH_MODEL)
        val capability = app.speechRecognitionEngine.capability(sourceLanguageTag)
        check(capability.available) { capability.reason ?: "온디바이스 음성인식을 사용할 수 없습니다." }
        val speechLanguageStatus = app.prepareSpeechRecognitionWithProcessAdmission(
            languageTag = sourceLanguageTag,
            serializeWithAllColdLoads = serializeNativeColdLoads,
            owner = preparationOwner,
        )
        ensureTranslationSessionCurrent(sessionId)
        check(speechLanguageStatus.isReady) { speechLanguageStatus.message }

        // A broadcast can switch from lightweight ML Kit to Gemma without restarting the app.
        // Release the previous native backend first so both large stacks never contend for RAM.
        ensureTranslationSessionCurrent(sessionId)
        app.selectTranslationSource(preparationOwner)
        ensureTranslationSessionCurrent(sessionId)
        app.translationDiagnostics.stage(TranslationRunStage.TRANSLATION_MODEL)

        val requested = translationLanguages.toSet()
        val voiceReconciliationFailures = try {
            app.speechSynthesisProvider.reconcileLanguages(
                languageTags = requested,
                broadcastGeneration = preparationOwner.generation,
            ).also {
                ensureTranslationSessionCurrent(sessionId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mapOf(
                "TTS" to (error.message?.take(MAX_OPERATOR_ERROR_DETAIL_CHARACTERS)
                    ?: error.javaClass.simpleName),
            )
        }
        val reconciliationFailures = if (awaitChannelPreparation) {
            app.translationProvider.reconcileTargets(
                requested,
                preparationOwner.generation,
            ).also {
                ensureTranslationSessionCurrent(sessionId)
            }
        } else {
            emptyMap()
        }
        // Model downloads/status inspection belong to settings, including when this session is
        // an explicit speech test. A missing optional ML Kit source model must not delay a
        // prepared Gemma translator or voice while Google's non-cancellable download is pending.
        // Both test and broadcast consume the last completed snapshot and warm ready fallbacks.
        val fallbackRefreshWarning: String? = null
        ensureTranslationSessionCurrent(sessionId)
        val missingFallback = missingMlKitModels(requested)
        val readyFallbackTargets = requested - missingFallback.toSet()
        val fallbackWarmupFailures = linkedMapOf<String, String>()
        // READY proves the files exist. Warm each native client independently so one corrupt or
        // unsupported language model cannot prevent the other selected channels from listening.
        if (awaitChannelPreparation) {
            val warmupOutcomes = supervisorScope {
                readyFallbackTargets.map { languageTag ->
                    async {
                        try {
                            nativeTranslationGate.initialize(mlKitNativeFirstUseKey(languageTag)) {
                                app.translationProvider.warm(setOf(languageTag))
                            }
                            ensureTranslationSessionCurrent(sessionId)
                            languageTag to null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            languageTag to error
                        }
                    }
                }.awaitAll()
            }
            warmupOutcomes.forEach { (languageTag, error) ->
                if (error != null) {
                    fallbackWarmupFailures[languageTag] =
                        error.message?.take(300) ?: error.javaClass.simpleName
                    Log.e(LOG_TAG, "ML Kit warm-up failed for isolated channel: $languageTag", error)
                }
            }
        }
        val priorityFallbackReady = translationLanguages.first() in readyFallbackTargets &&
            translationLanguages.first() !in fallbackWarmupFailures

        // Gemma refresh can verify a multi-gigabyte file. Its completed settings snapshot is also
        // consumed here so opening a non-Gemma or live broadcast never performs that I/O.
        val gemmaRefreshWarning: String? = null
        ensureTranslationSessionCurrent(sessionId)
        val gemmaReady = app.gemmaTranslationProvider.modelManager.status.value.readiness ==
            GemmaModelReadiness.READY
        val gemmaTargets = translationLanguages.filter {
            GemmaTranslationProvider.supportsTranslation(sourceLanguageTag, it)
        }.toSet()
        val gemmaTargetSupported = gemmaTargets.isNotEmpty()
        val gemmaWarmupTarget = gemmaTargets.firstOrNull() ?: translationLanguages.first()
        // Model preparation and reconciliation above can retire an older session's native
        // workers. Re-read the pressure-sensitive fields here; using the snapshot captured before
        // that cleanup can incorrectly pin an otherwise healthy 6/8 GB phone to ML Kit for the
        // whole broadcast. Physical-RAM support and constrained/standard mode stay fixed from the
        // session-start snapshot.
        val gemmaAttemptAdmission = gemmaBroadcastAttemptAdmission(
            initialCapability = gemmaCapability,
            latestCapability = GemmaBroadcastCapability.detect(this),
            workerAlreadyPrepared = app.gemmaTranslationProvider.hasActivePreparedWorker(),
        )
        val gemmaAutomaticRetryBlocked = app.gemmaTranslationProvider.isAutomaticRetryBlocked()
        val gemmaEligible = isGemmaBroadcastEligible(
            requested = useGemma,
            languagePairSupported = gemmaTargetSupported,
            hardwareSupported = gemmaAttemptAdmission.hardwareSupported,
            modelReady = gemmaReady,
            automaticRetryBlocked = gemmaAutomaticRetryBlocked,
        )
        // Synchronous and background Gemma warm-up use the same process-lifetime coordinator as
        // ML Kit and TTS. A cancelled JNI call therefore cannot make a replacement session start
        // an overlapping cold load merely because a session-local flag was discarded.
        val speechSynthesisWarning = if (awaitChannelPreparation) {
            app.translationDiagnostics.stage(TranslationRunStage.VOICE_MODEL)
            supervisorScope {
                translationLanguages.map { languageTag ->
                    async {
                        app.speechSynthesisProvider.prepare(
                            languageTags = listOf(languageTag),
                            warmMoonshineWithNativeAdmission = { target, warm ->
                                nativeSpeechGate.initialize(
                                    speechNativeWarmupKey(target),
                                ) {
                                    warm()
                                }
                            },
                        ).warning
                    }
                }.awaitAll()
            }.filterNotNull().distinct().joinToString(" · ").ifEmpty { null }.also {
                ensureTranslationSessionCurrent(sessionId)
            }
        } else {
            null
        }

        // The explicit one-language test waits for the actual warm-up. A live broadcast instead
        // starts healthy channels first and warms Gemma in an isolated background child.
        app.translationDiagnostics.stage(TranslationRunStage.TRANSLATION_MODEL)
        val waitForGemmaBeforeListening = shouldWarmGemmaBeforeListening(
            awaitChannelPreparation = awaitChannelPreparation,
            gemmaEligible = gemmaEligible,
            constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
        )
        val gemmaWarmup = if (waitForGemmaBeforeListening) {
            prepareGemmaBroadcastWarmup(
                eligible = gemmaEligible,
                fallbackReady = priorityFallbackReady,
                priorityLanguageTag = gemmaWarmupTarget,
                warmup = { priorityLanguageTag ->
                    // The explicit test path waits so "말씀하세요" means the actual engine has
                    // already produced one private inference. Live multi-channel broadcasts use
                    // the non-blocking isolated preparation below.
                    nativeTranslationGate.initialize(
                        gemmaNativeFirstUseKey(priorityLanguageTag),
                    ) {
                        app.gemmaTranslationProvider.warmup(
                            targetLanguageTag = priorityLanguageTag,
                                    sourceLanguageTag = sourceLanguageTag,
                            timeoutMillis = if (gemmaCapability.constrainedMemoryMode) {
                                GEMMA_CONSTRAINED_WARMUP_TIMEOUT_MILLIS
                            } else {
                                GEMMA_STANDARD_WARMUP_TIMEOUT_MILLIS
                            },
                        )
                    }
                    ensureTranslationSessionCurrent(sessionId)
                },
                cleanupAfterFailure = {
                    val cleaned = app.resetGemmaAfterFailureIf {
                        isTranslationSessionCurrent(sessionId)
                    }
                    ensureTranslationSessionCurrent(sessionId)
                    check(cleaned) { "Gemma 사전 준비 작업 공간을 정리하지 못했습니다." }
                },
            )
        } else {
            GemmaBroadcastWarmupResult(active = false)
        }
        ensureTranslationSessionCurrent(sessionId)
        gemmaLiveActive.set(gemmaWarmup.active)
        val gemmaPriorityRouteEnabled = AtomicBoolean(
            shouldEnableGemmaPriorityRoute(
                gemmaEligible = gemmaEligible,
                warmupActive = gemmaWarmup.active,
            ),
        )
        val presentation = translationProviderPresentation(
            translationLanguages = translationLanguages,
            gemmaActive = gemmaLiveActive.get(),
            // Presentation describes Gemma's fallback, which belongs only to the first selected
            // language. A missing non-priority model must not falsely downgrade this status.
            mlKitReady = priorityFallbackReady,
            displayName = TRANSLATION_LANGUAGES::getValue,
        )
        val providerSelectionWarning = when {
            useGemma && gemmaAutomaticRetryBlocked ->
                "Gemma 작업자 종료 후 자동 재실행을 보류했습니다. 준비된 ML Kit으로 복구를 시도합니다. " +
                    "설정에서 기존 검증 모델로 추론·TTS 다시 점검을 실행하세요."
            useGemma && !gemmaTargetSupported ->
                "선택한 우선 언어는 공식 Gemma Translator 대상이 아니어서 " +
                    "독립 ML Kit 경로를 사용합니다."
            useGemma && !gemmaAttemptAdmission.hardwareSupported ->
                "Gemma 안전 전환 · ${gemmaAttemptAdmission.operatorMessage}"
            useGemma && !gemmaReady ->
                "Gemma 모델 준비 안 됨 · 경량 오프라인 번역으로 자동 전환했습니다."
            gemmaWarmup.warning != null -> gemmaWarmup.warning
            useGemma && !gemmaAttemptAdmission.loadPermittedNow ->
                "Gemma 메모리 압력 감지 · 실제 초기화를 순차 시도합니다 · " +
                    gemmaAttemptAdmission.operatorMessage
            gemmaEligible && !gemmaLiveActive.get() && !awaitChannelPreparation ->
                "우선 언어 Gemma를 독립 준비 중입니다. 그동안 준비된 ML Kit 채널은 즉시 동작합니다."
            else -> null
        }
        val gemmaMemoryModeWarning = if (
            useGemma && gemmaEligible && gemmaCapability.constrainedMemoryMode
        ) {
            gemmaAttemptAdmission.operatorMessage
        } else {
            null
        }
        val missingFallbackWarning = missingFallback.takeIf { it.isNotEmpty() }?.let {
            "경량 오프라인 번역 모델 준비 확인 필요 · 해당 언어 채널만 독립 재시도합니다: " +
                it.joinToString()
        }
        val reconciliationWarning = reconciliationFailures.takeIf { it.isNotEmpty() }?.entries
            ?.joinToString(
                prefix = "이전 언어 작업 공간 정리 확인 필요: ",
                separator = "; ",
            ) { (languageTag, detail) -> "$languageTag: $detail" }
        val voiceReconciliationWarning = voiceReconciliationFailures.takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString(
                prefix = "이전 음성 작업 공간 정리 확인 필요: ",
                separator = "; ",
            ) { (languageTag, detail) -> "$languageTag: $detail" }
        val fallbackWarmupWarning = fallbackWarmupFailures.takeIf { it.isNotEmpty() }?.entries
            ?.joinToString(
                prefix = "번역 엔진 사전 준비 확인 필요 · 해당 언어만 첫 문장에서 재시도합니다: ",
                separator = "; ",
            ) { (languageTag, detail) -> "$languageTag: $detail" }
        translationProviderWarning = listOfNotNull(
            gemmaMemoryModeWarning,
            providerSelectionWarning,
            fallbackRefreshWarning,
            gemmaRefreshWarning,
            presentation.notice,
            missingFallbackWarning,
            fallbackWarmupWarning,
            reconciliationWarning,
            voiceReconciliationWarning,
        ).joinToString(" · ").ifEmpty { null }

        val activeGemmaPresentation = translationProviderPresentation(
            translationLanguages = translationLanguages,
            gemmaActive = true,
            mlKitReady = priorityFallbackReady,
            displayName = TRANSLATION_LANGUAGES::getValue,
        )
        // Warmup and live demand share these exact admission keys. If speech arrives before a
        // background warmup, its first native operation takes the same device-wide session permit
        // instead of loading up to five ML Kit and five TTS runtimes simultaneously.
        val admittedFallbackTranslationProvider = app.translationProvider.withNativeFirstUseGate(
            gate = nativeTranslationGate,
            keyPrefix = MLKIT_NATIVE_FIRST_USE_KEY_PREFIX,
        )
        val admittedGemmaTranslationProvider = app.gemmaTranslationProvider.withNativeFirstUseGate(
            gate = nativeTranslationGate,
            keyPrefix = GEMMA_NATIVE_FIRST_USE_KEY_PREFIX,
        )
        val admittedSpeechSynthesisProvider = app.speechSynthesisProvider.withNativeFirstUseGate(
            gate = nativeSpeechGate,
            keyPrefix = SPEECH_NATIVE_FIRST_USE_KEY_PREFIX,
        )
        val gemmaWithFailover = if (gemmaEligible) {
            FailoverTranslationEngineProvider(
                    primary = admittedGemmaTranslationProvider,
                    fallback = admittedFallbackTranslationProvider,
                    primaryAttemptTimeoutMillis = GEMMA_PRIMARY_ATTEMPT_TIMEOUT_MILLIS,
                    primaryRetryCooldownMillis = if (
                        shouldRetryGemmaWithinBroadcast(gemmaCapability.constrainedMemoryMode)
                    ) {
                        GEMMA_PRIMARY_RETRY_COOLDOWN_MILLIS
                    } else {
                        null
                    },
                    onPrimaryFailure = { error ->
                        if (isTranslationSessionCurrent(sessionId)) {
                            gemmaLiveActive.set(false)
                            gemmaReloadRequiresAdmission.set(true)
                            val retryWithinBroadcast = shouldRetryGemmaWithinBroadcast(
                                gemmaCapability.constrainedMemoryMode,
                            ) && !app.gemmaTranslationProvider.isAutomaticRetryBlocked()
                            if (!retryWithinBroadcast) {
                                gemmaPriorityRouteEnabled.set(false)
                            }
                            gemmaTargets.forEach { target ->
                                nativeTranslationGate.invalidate(gemmaNativeFirstUseKey(target))
                            }
                            // Keep this channel on the failover provider. Its cooldown sends the
                            // intervening sentences to ML Kit, while the process-local service
                            // generation gate prevents the later Gemma retry from overlapping an
                            // old 2.41 GiB runtime whose native call ignored cancellation.
                            translationProviderWarning = listOfNotNull(
                                gemmaMemoryModeWarning,
                                if (app.gemmaTranslationProvider.isAutomaticRetryBlocked()) {
                                    "Gemma 작업자가 종료되어 자동 재실행을 보류했습니다. " +
                                        "준비된 경량 번역으로 복구를 시도합니다. " +
                                        "Gemma는 설정에서 실행 점검 후 다시 사용하세요 · "
                                } else if (retryWithinBroadcast) {
                                    "현재 문장은 경량 오프라인 번역으로 복구를 시도합니다. " +
                                        "5초 보호 간격 뒤 Gemma를 다시 시도합니다 · "
                                } else {
                                    "현재 문장은 경량 오프라인 번역으로 복구를 시도합니다. " +
                                        "이 방송은 경량 번역으로 유지하고 다음 방송에서 Gemma를 " +
                                        "다시 확인합니다 · "
                                } +
                                    (error.message ?: error.javaClass.simpleName),
                            ).joinToString(" · ")
                            updateProviderFallbackUi(translationLanguages, sessionId)
                            // Engine teardown can include Binder/native cleanup. Keep it out of
                            // this sentence's fallback deadline. A replacement worker generation
                            // independently waits for the predecessor's serialized runtime close.
                            serviceScope.launch {
                                try {
                                    app.resetGemmaAfterFailureIf {
                                        isTranslationSessionCurrent(sessionId)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (cleanupError: Throwable) {
                                    Log.w(LOG_TAG, "Gemma worker cleanup failed", cleanupError)
                                    if (isTranslationSessionCurrent(sessionId)) {
                                        appendTranslationProviderWarning(
                                            "Gemma 작업 공간을 완전히 회수하지 못해 이 방송에서는 " +
                                                "경량 번역을 유지합니다: " +
                                                (cleanupError.message?.take(
                                                    MAX_OPERATOR_ERROR_DETAIL_CHARACTERS,
                                                ) ?: cleanupError.javaClass.simpleName),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    onPrimaryRecovered = {
                        if (isTranslationSessionCurrent(sessionId)) {
                            gemmaReloadRequiresAdmission.set(false)
                            gemmaLiveActive.set(true)
                            translationProviderWarning = listOfNotNull(
                                gemmaMemoryModeWarning,
                                activeGemmaPresentation.notice,
                            ).joinToString(" · ").ifEmpty { null }
                            updateProviderFallbackUi(
                                translationLanguages = translationLanguages,
                                sessionId = sessionId,
                                providerLabel = activeGemmaPresentation.providerLabel,
                                gemmaPriorityActive = true,
                            )
                        }
                    },
                )
        } else null
        // A single shared engine, not one model copy per language. Recovery executes inside an
        // admitted job; time spent awaiting a fair turn is not reported as a failed Gemma call.
        val fairGemma = gemmaWithFailover?.let { failover ->
            FairQueuedTranslationEngineProvider(
                // Review mode preserves its already-computed draft itself: never execute ML Kit
                // a second time inside the review lane or mistake fallback for successful review.
                delegate = if (selectiveTranslationRefinement) admittedGemmaTranslationProvider else failover,
                parentScope = serviceScope,
                config = FairTranslationQueueConfig(
                    maxPendingPerLanguage = 2,
                    queueWaitTimeoutMillis = 30_000L,
                    inferenceTimeoutMillis = GEMMA_PIPELINE_TRANSLATION_TIMEOUT_MILLIS,
                ),
            ).also { queue ->
                try {
                    synchronized(translationResourceLock) {
                        ensureTranslationSessionCurrent(sessionId)
                        sharedTranslationQueue = queue
                    }
                } catch (error: Throwable) {
                    queue.close()
                    throw error
                }
            }
        }
        val preferredTranslationProvider = TranslationEngineProvider { targetLanguageTag ->
            if (targetLanguageTag in gemmaTargets &&
                gemmaPriorityRouteEnabled.get() && fairGemma != null
            ) {
                fairGemma.engineFor(targetLanguageTag)
            } else {
                admittedFallbackTranslationProvider.engineFor(targetLanguageTag)
            }
        }
        val refinementRoutes = java.util.concurrent.ConcurrentHashMap<String, String>()
        val selectiveProvider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = admittedFallbackTranslationProvider,
            reviewerAvailable = { target ->
                target in gemmaTargets && fairGemma != null && gemmaLiveActive.get() &&
                    app.gemmaTranslationProvider.hasActivePreparedWorker() &&
                    !app.gemmaTranslationProvider.isAutomaticRetryBlocked()
            },
            reviewerProvider = TranslationEngineProvider { target ->
                check(target in gemmaTargets && fairGemma != null && gemmaLiveActive.get() &&
                    app.gemmaTranslationProvider.hasActivePreparedWorker() &&
                    !app.gemmaTranslationProvider.isAutomaticRetryBlocked()) {
                    "Optional Gemma reviewer is not ready"
                }
                fairGemma.engineFor(target)
            },
            onDiagnostic = { diagnostic ->
                refinementRoutes[diagnostic.targetLanguageTag] = when (diagnostic.outcome) {
                    SelectiveRefinementOutcome.REVIEW_ACCEPTED -> "ML Kit → Gemma 보완"
                    SelectiveRefinementOutcome.DRAFT_ACCEPTED -> "ML Kit · 보완 불필요"
                    SelectiveRefinementOutcome.REVIEW_UNAVAILABLE -> "ML Kit · 보완 엔진 대기"
                    else -> "ML Kit · 초안 유지"
                }
                RuntimeDiagnosticLog.record(
                    "translation_refinement",
                    "target=${diagnostic.targetLanguageTag} outcome=${diagnostic.outcome} " +
                        "reasons=${diagnostic.reasons.joinToString()}",
                )
                if (isTranslationSessionCurrent(sessionId)) {
                    when (diagnostic.outcome) {
                        SelectiveRefinementOutcome.REVIEW_FAILED,
                        SelectiveRefinementOutcome.REVIEW_TIMED_OUT,
                        SelectiveRefinementOutcome.REVIEW_REJECTED -> {
                            translationProviderWarning =
                                "선택적 보완 · ${diagnostic.targetLanguageTag}: " +
                                    "보완 미적용, ML Kit 초안으로 계속합니다 (${diagnostic.outcome})"
                        }
                        else -> Unit
                    }
                }
            },
        )
        val baseTranslationProvider = TranslationEngineProvider { target ->
            if (selectiveTranslationRefinement && target in readyFallbackTargets &&
                target !in fallbackWarmupFailures) {
                selectiveProvider.engineFor(target)
            } else {
                // Missing draft files must not disable a usable prepared Gemma translator.
                preferredTranslationProvider.engineFor(target)
            }
        }
        val translationProvider = TranslationEngineProvider { targetLanguageTag ->
            val baseEngine = baseTranslationProvider.engineFor(targetLanguageTag)
            if (targetLanguageTag.equals("zh-TW", ignoreCase = true)) {
                TraditionalChineseTranslatingEngine(baseEngine)
            } else {
                baseEngine
            }
        }
        val input = Channel<PcmAudioFrame>(
            // Keep the service-to-recognizer hand-off short. Larger queues made overload sound
            // like a successful but many-seconds-late simultaneous interpretation.
            capacity = RECOGNITION_HANDOFF_FRAMES,
        )
        app.translationDiagnostics.stage(TranslationRunStage.LISTENING)
        val recognizedUtterances = app.speechRecognitionEngine.recognize(
            frames = input.receiveAsFlow(),
            config = SpeechRecognitionConfig(sourceLanguageTag = sourceLanguageTag),
        )
        // Preview owns a private registry. A cancelled test can finish a non-cooperative native
        // callback late, but can never reconfigure or supersede the active web broadcast session.
        val streamRegistry = if (streamSession == null) {
            AudioStreamRegistry(
                maxChannels = MAX_TRANSLATION_LANGUAGES,
                maxListeners = MAX_TRANSLATION_LANGUAGES,
            )
        } else {
            app.audioStreams
        }
        val pipeline = TranslationBroadcastPipeline(
            streams = streamRegistry,
            translationEngines = translationProvider,
            glossaryTerms = app.glossary::matching,
            onGlossaryWarning = { app.glossary.warning.value = it },
            speechEngines = admittedSpeechSynthesisProvider,
            observer = broadcastTranscriptObserver(sessionId, archiveSessionId),
            shouldPublishAudio = {
                val current = app.broadcastRuntime.state.value
                isTranslationSessionCurrent(sessionId) &&
                    (current.translationTestActive || current.phase == BroadcastPhase.LIVE)
            },
            currentElapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
            translationTimeoutMillis = DEFAULT_TRANSLATION_TIMEOUT_MILLIS,
            translationTimeoutMillisForChannel = { channelId ->
                if (channelId == translationLanguages.first().lowercase(Locale.ROOT) &&
                    gemmaPriorityRouteEnabled.get()
                ) {
                    GEMMA_PIPELINE_TRANSLATION_TIMEOUT_MILLIS
                } else {
                    DEFAULT_TRANSLATION_TIMEOUT_MILLIS
                }
            },
            firstAudioTimeoutMillis =
                app.speechSynthesisProvider.pipelineFirstAudioTimeoutMillis,
            synthesisFrameIdleTimeoutMillis =
                app.speechSynthesisProvider.pipelineFrameIdleTimeoutMillis,
            synthesisTotalTimeoutMillis =
                app.speechSynthesisProvider.pipelineTotalTimeoutMillis,
            audioPublicationCoordinator = audioPublicationCoordinator,
        ).start(
            scope = serviceScope,
            utterances = recognizedUtterances,
            targets = translationLanguages.map { languageTag ->
                TranslationTarget(
                    channelId = languageTag.lowercase(Locale.ROOT),
                    displayName = requireNotNull(TRANSLATION_LANGUAGES[languageTag]),
                    languageTag = languageTag,
                    speechSampleRateHz = MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
                )
            },
            sourceLanguageTag = sourceLanguageTag,
            streamSession = streamSession,
        )
        val healthJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            pipeline.health.collectLatest { channels ->
                if (!isTranslationSessionCurrent(sessionId)) return@collectLatest
                val channelWarning = channels.mapNotNull { channel ->
                    channel.lastError?.let { "${channel.channelId}: $it" }
                }.joinToString().ifEmpty { null }
                val currentSpeechSynthesisWarning =
                    app.speechSynthesisProvider.fallbackWarning(translationLanguages)
                val warning = listOfNotNull(
                    translationProviderWarning,
                    currentSpeechSynthesisWarning,
                    channelWarning,
                )
                    .joinToString(" · ")
                    .ifEmpty { null }
                app.broadcastRuntime.update { current ->
                    if (!isTranslationSessionCurrent(sessionId)) return@update current
                    val updatedChannels = channels.map { health ->
                        val existing = current.translationChannels.firstOrNull {
                            it.channelId == health.channelId
                        }
                        (existing ?: BroadcastChannelSnapshot(
                            channelId = health.channelId,
                            languageTag = health.targetLanguageTag,
                            displayName = TRANSLATION_LANGUAGES[health.targetLanguageTag]
                                ?: health.targetLanguageTag,
                        )).copy(
                            translationProvider = if (selectiveTranslationRefinement &&
                                health.targetLanguageTag in readyFallbackTargets &&
                                health.targetLanguageTag !in fallbackWarmupFailures
                            ) {
                                refinementRoutes[health.targetLanguageTag] ?: "ML Kit · 선택적 보완"
                            } else if (
                                gemmaLiveActive.get() &&
                                GemmaTranslationProvider.supportsTranslation(sourceLanguageTag, health.targetLanguageTag)
                            ) {
                                "Gemma → ML Kit"
                            } else {
                                "ML Kit"
                            },
                            synthesisProvider = when {
                                app.speechSynthesisProvider.unavailableReason(
                                    health.targetLanguageTag,
                                ) != null -> "음성 사용 불가"
                                app.speechSynthesisProvider.isFallbackReady(
                                    health.targetLanguageTag,
                                ) -> "Galaxy 오프라인"
                                health.targetLanguageTag in MOONSHINE_TTS_LANGUAGES -> "Moonshine"
                                else -> "Galaxy 오프라인 준비 확인 필요"
                            },
                            translationState = health.translationState.toBroadcastChannelWorkerState(),
                            synthesisState = if (
                                app.speechSynthesisProvider.unavailableReason(
                                    health.targetLanguageTag,
                                ) != null
                            ) {
                                BroadcastChannelWorkerState.DEGRADED
                            } else {
                                health.synthesisState.toBroadcastChannelWorkerState()
                            },
                            lastAcceptedSequence = health.lastAcceptedSequence,
                            lastTranslatedSequence = health.lastTranslatedSequence,
                            lastSynthesizedSequence = health.lastSynthesizedSequence,
                            lastPublishedSequence = health.lastPublishedSequence,
                            publishedFrameCount = health.publishedFrameCount,
                            lastCompletedSequence = health.lastCompletedSequence,
                            droppedUtterances = health.droppedUtterances,
                            translationFailures = health.translationFailures,
                            synthesisFailures = health.synthesisFailures,
                            translationRecoveries = health.translationRecoveries,
                            synthesisRecoveries = health.synthesisRecoveries,
                            lastTranslationElapsedMillis = health.lastTranslationElapsedMillis,
                            lastSynthesisFirstPcmMillis = health.lastFirstAudioElapsedMillis,
                            lastSynthesisElapsedMillis = health.lastSynthesisElapsedMillis,
                            lastTranslationError = health.lastTranslationError,
                            lastSynthesisError = health.lastSynthesisError
                                ?: app.speechSynthesisProvider.unavailableReason(
                                    health.targetLanguageTag,
                                ),
                        )
                    }
                    when {
                        current.phase == BroadcastPhase.LIVE ||
                            current.phase == BroadcastPhase.PAUSED ->
                            current.copy(
                                translationWarning = warning,
                                translationChannels = updatedChannels,
                            )
                        current.translationTestActive && warning != null ->
                            current.copy(
                                translationTestMessage = "통번역 처리 주의 · $warning",
                                translationChannels = updatedChannels,
                            )
                        current.translationTestActive ->
                            current.copy(translationChannels = updatedChannels)
                        else -> current
                    }
                }
            }
        }
        try {
            synchronized(translationResourceLock) {
                ensureTranslationSessionCurrent(sessionId)
                recognitionFrames = input
                translationPipeline = pipeline
                translationHealthJob = healthJob
                healthJob.start()
            }
        } catch (error: Throwable) {
            healthJob.cancel()
            pipeline.close()
            input.close()
            throw error
        }
        if (!awaitChannelPreparation) {
            val supportJob = launchTranslationSupportPreparation(
                translationLanguages = translationLanguages,
                sourceLanguageTag = sourceLanguageTag,
                preparationGeneration = preparationOwner.generation,
                readyFallbackTargets = readyFallbackTargets,
                // Standard-memory devices warm in this isolated background lane. A constrained
                // 6 GB-class device already made one longer, serialized attempt above before it
                // accepted microphone audio; do not immediately duplicate that expensive load.
                gemmaNeedsWarmup = gemmaEligible && !waitForGemmaBeforeListening,
                priorityFallbackReady = priorityFallbackReady,
                gemmaLiveActive = gemmaLiveActive,
                gemmaPriorityRouteEnabled = gemmaPriorityRouteEnabled,
                activeGemmaPresentation = activeGemmaPresentation,
                missingFallbackWarning = missingFallbackWarning,
                warmFallbackTranslationInBackground =
                    shouldWarmFallbackTranslationInBackground(
                        constrainedMemoryMode = gemmaCapability.constrainedMemoryMode,
                        gemmaPriorityActive = gemmaLiveActive.get(),
                    ),
                nativeTranslationGate = nativeTranslationGate,
                nativeSpeechGate = nativeSpeechGate,
                sessionId = sessionId,
            )
            synchronized(translationResourceLock) {
                if (isTranslationSessionCurrent(sessionId)) {
                    translationSupportPreparationJob = supportJob
                    supportJob.invokeOnCompletion {
                        synchronized(translationResourceLock) {
                            if (translationSupportPreparationJob === supportJob) {
                                translationSupportPreparationJob = null
                            }
                        }
                    }
                } else {
                    supportJob.cancel()
                }
            }
        }
        app.translationDiagnostics.stage(TranslationRunStage.RUNNING)
        val initialProviderLabel = if (gemmaEligible && !gemmaLiveActive.get()) {
            val priorityName = requireNotNull(TRANSLATION_LANGUAGES[translationLanguages.first()])
                .substringBefore(" ·")
            "ML Kit 즉시 시작 · $priorityName Gemma 독립 준비 중"
        } else {
            presentation.providerLabel
        }
        return TranslationPreparationResult(
            providerLabel = initialProviderLabel,
            warning = listOfNotNull(translationProviderWarning, speechSynthesisWarning)
                .joinToString(" · ")
                .ifEmpty { null },
        )
    }

    /**
     * Live broadcast preparation never forms an all-language barrier. Every voice is prepared in
     * its own supervised child and Gemma warms independently while prepared ML Kit channels start
     * consuming STT immediately. A failed child updates only its language capability state.
     */
    private fun launchTranslationSupportPreparation(
        translationLanguages: List<String>,
        sourceLanguageTag: String,
        preparationGeneration: Long,
        readyFallbackTargets: Set<String>,
        gemmaNeedsWarmup: Boolean,
        priorityFallbackReady: Boolean,
        gemmaLiveActive: AtomicBoolean,
        gemmaPriorityRouteEnabled: AtomicBoolean,
        activeGemmaPresentation: TranslationProviderPresentation,
        missingFallbackWarning: String?,
        warmFallbackTranslationInBackground: Boolean,
        nativeTranslationGate: NativeFirstUseGate,
        nativeSpeechGate: NativeFirstUseGate,
        sessionId: Long,
    ): Job = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
        supervisorScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    // Retirement owns the native-close acknowledgement that frees old memory.
                    // It must run outside new-load admission or cleanup and admission can deadlock.
                    val failures = app.translationProvider.reconcileTargets(
                        languageTags = translationLanguages.toSet(),
                        preparationGeneration = preparationGeneration,
                    )
                    ensureTranslationSessionCurrent(sessionId)
                    if (failures.isNotEmpty()) {
                        appendTranslationProviderWarning(
                            failures.entries.joinToString(
                                prefix = "이전 언어 작업 공간 정리 확인 필요: ",
                                separator = "; ",
                            ) { (languageTag, detail) -> "$languageTag: $detail" },
                        )
                        updateProviderFallbackUi(
                            translationLanguages = translationLanguages,
                            sessionId = sessionId,
                            gemmaPriorityActive = gemmaLiveActive.get(),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    // Reconciliation is housekeeping for old worker slots, not permission to
                    // terminate the active input, Ktor server, or any already healthy language.
                    Log.e(LOG_TAG, "Background ML Kit reconciliation failed", error)
                    if (isTranslationSessionCurrent(sessionId)) {
                        appendTranslationProviderWarning(
                            "이전 번역 작업 공간 정리 오류 · 현재 채널은 독립 재연결합니다: " +
                                (error.message?.take(MAX_OPERATOR_ERROR_DETAIL_CHARACTERS)
                                    ?: error.javaClass.simpleName),
                        )
                        runCatching {
                            updateProviderFallbackUi(
                                translationLanguages = translationLanguages,
                                sessionId = sessionId,
                                gemmaPriorityActive = gemmaLiveActive.get(),
                            )
                        }.onFailure { reportingError ->
                            Log.e(LOG_TAG, "Failed to report reconciliation warning", reportingError)
                        }
                    }
                }
            }
            translationLanguages.forEach { languageTag ->
                // Live startup deliberately has no all-language warm-up barrier. Each downloaded
                // ML Kit model is initialized in its own Android process, and one failed or slow
                // process cannot postpone voice preparation or any sibling language channel.
                if (warmFallbackTranslationInBackground &&
                    languageTag in readyFallbackTargets
                ) {
                    launch {
                        try {
                            nativeTranslationGate.initialize(mlKitNativeFirstUseKey(languageTag)) {
                                app.translationProvider.warm(listOf(languageTag))
                            }
                            ensureTranslationSessionCurrent(sessionId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Log.e(
                                LOG_TAG,
                                "Background ML Kit warm-up failed for isolated channel: " +
                                    languageTag,
                                error,
                            )
                            if (isTranslationSessionCurrent(sessionId)) {
                                app.broadcastRuntime.update { current ->
                                    if (!isTranslationSessionCurrent(sessionId)) return@update current
                                    current.copy(
                                        translationChannels = current.translationChannels.map { channel ->
                                            if (channel.languageTag == languageTag) {
                                                channel.copy(
                                                    translationState =
                                                        BroadcastChannelWorkerState.DEGRADED,
                                                    lastTranslationError =
                                                        "번역 작업 공간 사전 준비 실패 · " +
                                                            (error.message
                                                                ?: error.javaClass.simpleName),
                                                )
                                            } else {
                                                channel
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                launch {
                    try {
                        app.speechSynthesisProvider.prepare(
                            languageTags = listOf(languageTag),
                            warmMoonshineWithNativeAdmission = { target, warm ->
                                nativeSpeechGate.initialize(
                                    speechNativeWarmupKey(target),
                                ) {
                                    warm()
                                }
                            },
                        )
                        ensureTranslationSessionCurrent(sessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // GalaxySpeechSynthesisProvider also persists this exact language error.
                        // Keep this child isolated even if a provider fails before it can report.
                        Log.e(
                            LOG_TAG,
                            "Background voice preparation failed for isolated channel: $languageTag",
                            error,
                        )
                    } finally {
                        updateProviderFallbackUi(
                            translationLanguages = translationLanguages,
                            sessionId = sessionId,
                            gemmaPriorityActive = gemmaLiveActive.get(),
                        )
                    }
                }
            }

            if (gemmaNeedsWarmup) {
                launch {
                    try {
                        val warmup = prepareGemmaBroadcastWarmup(
                            eligible = true,
                            fallbackReady = priorityFallbackReady,
                            priorityLanguageTag = translationLanguages.firstOrNull {
                                GemmaTranslationProvider.supportsTranslation(sourceLanguageTag, it)
                            } ?: translationLanguages.first(),
                            warmup = { priorityLanguageTag ->
                                nativeTranslationGate.initialize(
                                    gemmaNativeFirstUseKey(
                                        priorityLanguageTag,
                                    ),
                                ) {
                                    app.gemmaTranslationProvider.warmup(
                                        targetLanguageTag = priorityLanguageTag,
                                        sourceLanguageTag = sourceLanguageTag,
                                    )
                                }
                                ensureTranslationSessionCurrent(sessionId)
                            },
                            cleanupAfterFailure = {
                                val cleaned = app.resetGemmaAfterFailureIf {
                                    isTranslationSessionCurrent(sessionId)
                                }
                                ensureTranslationSessionCurrent(sessionId)
                                check(cleaned) { "Gemma 사전 준비 작업 공간을 정리하지 못했습니다." }
                            },
                        )
                        ensureTranslationSessionCurrent(sessionId)
                        gemmaLiveActive.set(warmup.active)
                        gemmaPriorityRouteEnabled.set(warmup.active)
                        translationProviderWarning = listOfNotNull(
                            if (warmup.active) activeGemmaPresentation.notice else warmup.warning,
                            missingFallbackWarning,
                        ).joinToString(" · ").ifEmpty { null }
                        updateProviderFallbackUi(
                            translationLanguages = translationLanguages,
                            sessionId = sessionId,
                            providerLabel = if (warmup.active) {
                                activeGemmaPresentation.providerLabel
                            } else {
                                "ML Kit"
                            },
                            gemmaPriorityActive = warmup.active,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // A failed cleanup after native Gemma warm-up is still only the priority
                        // language's provider failure. Keep ML Kit, every sibling and Ktor live.
                        Log.e(LOG_TAG, "Background Gemma preparation failed", error)
                        gemmaLiveActive.set(false)
                        if (isTranslationSessionCurrent(sessionId)) {
                            appendTranslationProviderWarning(
                                "Gemma 준비 작업 공간 오류 · 우선 언어는 ML Kit로 계속합니다: " +
                                    (error.message?.take(MAX_OPERATOR_ERROR_DETAIL_CHARACTERS)
                                        ?: error.javaClass.simpleName),
                            )
                            runCatching {
                                updateProviderFallbackUi(
                                    translationLanguages = translationLanguages,
                                    sessionId = sessionId,
                                    providerLabel = "ML Kit",
                                    gemmaPriorityActive = false,
                                )
                            }.onFailure { reportingError ->
                                Log.e(LOG_TAG, "Failed to report Gemma preparation warning", reportingError)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun appendTranslationProviderWarning(warning: String) {
        synchronized(translationResourceLock) {
            translationProviderWarning = listOfNotNull(
                translationProviderWarning,
                warning,
            ).distinct().joinToString(" · ")
        }
    }

    private fun beginTranslationSession(): Long =
        translationSessionCoordinator.beginSession()

    private fun isTranslationSessionCurrent(sessionId: Long): Boolean =
        translationSessionCoordinator.isSessionCurrent(sessionId)

    private fun ensureTranslationSessionCurrent(sessionId: Long) {
        if (!isTranslationSessionCurrent(sessionId)) {
            throw CancellationException("A newer translation session replaced this work")
        }
    }

    private fun missingMlKitModels(requested: Set<String>): List<String> = requested.filter {
        languageTag ->
        app.translationProvider.modelManager.statuses.value.none {
            it.languageTag == languageTag && it.readiness == ModelReadiness.READY
        }
    }

    private fun updateProviderFallbackUi(
        translationLanguages: List<String>,
        sessionId: Long,
        providerLabel: String = "ML Kit",
        gemmaPriorityActive: Boolean = false,
    ) {
        if (!isTranslationSessionCurrent(sessionId)) return
        app.broadcastRuntime.update { current ->
            if (!isTranslationSessionCurrent(sessionId)) return@update current
            val warning = listOfNotNull(
                translationProviderWarning,
                app.speechSynthesisProvider.fallbackWarning(translationLanguages),
            ).joinToString(" · ").ifEmpty { null }
            if (current.phase == BroadcastPhase.LIVE || current.phase == BroadcastPhase.PAUSED) {
                current.copy(
                    channelSummary = "$providerLabel · " + translationLanguages.joinToString {
                        requireNotNull(TRANSLATION_LANGUAGES[it])
                    },
                    translationWarning = warning,
                    translationChannels = current.translationChannels.map { channel ->
                        channel.copy(
                            translationProvider = if (
                                gemmaPriorityActive &&
                                GemmaTranslationProvider.supportsTargetLanguage(channel.languageTag)
                            ) {
                                "Gemma → ML Kit"
                            } else {
                                "ML Kit"
                            },
                            synthesisProvider = when {
                                app.speechSynthesisProvider.unavailableReason(
                                    channel.languageTag,
                                ) != null -> "음성 사용 불가"
                                app.speechSynthesisProvider.isFallbackReady(
                                    channel.languageTag,
                                ) -> "Galaxy 오프라인"
                                channel.languageTag in MOONSHINE_TTS_LANGUAGES -> "Moonshine"
                                else -> "Galaxy 오프라인 준비 확인 필요"
                            },
                        )
                    },
                )
            } else if (current.translationTestActive) {
                current.copy(translationTestMessage = warning)
            } else {
                current
            }
        }
    }

    private fun broadcastTranscriptObserver(
        sessionId: Long,
        archiveSessionId: Long? = null,
    ) = object : TranslationPipelineObserver {
        override fun onSourceRecognized(utterance: RecognizedUtterance) {
            if (!isTranslationSessionCurrent(sessionId)) return
            if (utterance.isRetracted) {
                app.broadcastRuntime.update { current ->
                    if (!isTranslationSessionCurrent(sessionId)) return@update current
                    current.copy(
                        transcripts = current.transcripts.filterNot {
                            it.sequence == utterance.sequence
                        },
                    )
                }
                return
            }
            if (utterance.isFinal) {
                app.translationDiagnostics.stage(TranslationRunStage.TRANSLATING)
            }
            app.broadcastRuntime.update { current ->
                if (!isTranslationSessionCurrent(sessionId)) return@update current
                val existing = current.transcripts.firstOrNull { it.sequence == utterance.sequence }
                val line = existing?.copy(
                    sourceText = utterance.text,
                    isFinal = utterance.isFinal,
                    sourceLanguageTag = utterance.sourceLanguageTag,
                ) ?: TranslationTranscriptLine(
                    sequence = utterance.sequence,
                    sourceText = utterance.text,
                    capturedAtElapsedRealtimeNanos = utterance.capturedAtElapsedRealtimeNanos,
                    isFinal = utterance.isFinal,
                    sourceLanguageTag = utterance.sourceLanguageTag,
                )
                current.copy(
                    transcripts = (
                        current.transcripts.filterNot { it.sequence == utterance.sequence } + line
                        ).takeLast(MAX_TRANSCRIPT_LINES),
                )
            }
            if (utterance.isFinal) persistTranscriptIfAvailable(archiveSessionId, utterance.sequence)
        }

        override fun onTranslationCompleted(
            utterance: RecognizedUtterance,
            target: TranslationTarget,
            translatedText: String,
            elapsedMillis: Long,
        ) {
            if (!isTranslationSessionCurrent(sessionId)) return
            app.translationDiagnostics.stage(TranslationRunStage.SPEAKING)
            updateTranscript(sessionId, utterance.sequence) { line ->
                line.copy(
                    translations = line.translations + (target.languageTag to translatedText),
                    translationLatencyMillis = line.translationLatencyMillis +
                        (target.languageTag to elapsedMillis),
                )
            }
            persistTranscriptIfAvailable(archiveSessionId, utterance.sequence)
        }

        override fun onSynthesisAudioCompleted(
            utterance: RecognizedUtterance,
            target: TranslationTarget,
            elapsedMillis: Long,
            pcm: SynthesizedPcmStats,
        ) {
            if (!isTranslationSessionCurrent(sessionId)) return
            app.translationDiagnostics.stage(TranslationRunStage.RUNNING)
            updateTranscript(sessionId, utterance.sequence) { line ->
                line.copy(
                    synthesisLatencyMillis = line.synthesisLatencyMillis +
                        (target.languageTag to elapsedMillis),
                )
            }
            persistTranscriptIfAvailable(archiveSessionId, utterance.sequence)
            recordTranslationTestSynthesis(sessionId, target.languageTag, pcm)
        }

        override fun onSynthesisAudioStarted(
            utterance: RecognizedUtterance,
            target: TranslationTarget,
            elapsedMillis: Long,
        ) {
            if (!isTranslationSessionCurrent(sessionId)) return
            val endToEndMillis = (
                (SystemClock.elapsedRealtimeNanos() - utterance.recognizedAtElapsedRealtimeNanos) /
                    1_000_000L
                ).coerceAtLeast(0L)
            updateTranscript(sessionId, utterance.sequence) { line ->
                line.copy(
                    firstAudioLatencyMillis = line.firstAudioLatencyMillis +
                        (target.languageTag to endToEndMillis),
                )
            }
            persistTranscriptIfAvailable(archiveSessionId, utterance.sequence)
        }
    }

    private fun persistTranscriptIfAvailable(archiveSessionId: Long?, sequence: Long) {
        val persistentSession = archiveSessionId ?: return
        app.broadcastRuntime.state.value.transcripts
            .firstOrNull { it.sequence == sequence && it.isFinal }
            ?.let { line -> app.transcriptArchive.enqueue(persistentSession, line) }
    }

    private fun recordTranslationTestSynthesis(
        sessionId: Long,
        languageTag: String,
        pcm: SynthesizedPcmStats,
    ) {
        val evidence = synchronized(translationResourceLock) {
            val current = translationTestAudioEvidence
            if (current?.sessionId != sessionId || current.languageTag != languageTag) {
                null
            } else {
                current.withSynthesis(pcm).also { translationTestAudioEvidence = it }
            }
        }
        evidence?.let(::updateTranslationTestEvidenceUi)
    }

    private fun recordTranslationTestPlaybackWrite(
        sessionId: Long,
        languageTag: String,
        writtenBytes: Int,
    ) {
        val evidence = synchronized(translationResourceLock) {
            val current = translationTestAudioEvidence
            if (current?.sessionId != sessionId || current.languageTag != languageTag) {
                null
            } else {
                current.withNonSilentPlaybackWrite(writtenBytes).also {
                    translationTestAudioEvidence = it
                }
            }
        }
        evidence?.let(::updateTranslationTestEvidenceUi)
    }

    private fun recordTranslationTestPlaybackFailure(sessionId: Long, languageTag: String) {
        val evidence = synchronized(translationResourceLock) {
            val current = translationTestAudioEvidence
            if (current?.sessionId != sessionId || current.languageTag != languageTag) {
                null
            } else {
                current.withPlaybackWriteFailure().also { translationTestAudioEvidence = it }
            }
        }
        evidence?.let(::updateTranslationTestEvidenceUi)
    }

    private fun updateTranslationTestEvidenceUi(evidence: TranslationTestAudioEvidence) {
        val message = when {
            evidence.passed -> {
                val warnings = evidence.synthesisPcm?.qualityWarnings(
                    MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
                ).orEmpty()
                if (warnings.isEmpty()) {
                    "비무음 통역 음성과 스피커 출력을 확인했습니다. 내용과 자연스러움을 듣고 확인하세요."
                } else {
                    "비무음 통역 음성을 출력했지만 ${warnings.joinToString(" · ")}가 감지됐습니다. 듣고 확인하세요."
                }
            }
            evidence.synthesisPcm == null -> return
            !evidence.hasNonSilentSynthesis ->
                "통역 음성이 비어 있거나 무음입니다. 다시 말하거나 음성 모델을 확인하세요."
            evidence.playbackWriteFailed ->
                "통역 음성은 생성됐지만 스피커 출력에 실패했습니다."
            else ->
                "비무음 통역 음성을 생성했습니다. 스피커 출력 확인 중입니다."
        }
        app.broadcastRuntime.update { current ->
            if (!isTranslationSessionCurrent(evidence.sessionId) ||
                !current.translationTestActive ||
                current.translationTestLanguageTag != evidence.languageTag
            ) {
                return@update current
            }
            current.copy(
                translationTestPassed = evidence.passed,
                translationTestMessage = message,
            )
        }
    }

    private fun updateTranscript(
        sessionId: Long,
        sequence: Long,
        transform: (TranslationTranscriptLine) -> TranslationTranscriptLine,
    ) {
        app.broadcastRuntime.update { current ->
            if (!isTranslationSessionCurrent(sessionId)) return@update current
            current.copy(
                transcripts = current.transcripts.map { line ->
                    if (line.sequence == sequence) transform(line) else line
                },
            )
        }
    }

    /**
     * Clears ownership only after Ktor actually releases its socket. A failed close leaves the
     * exact retryable RunningGuideCastServer attached, keeping the service alive and preventing a
     * second owner from racing to bind :8787.
     */
    private fun closeServerHandle(server: RunningGuideCastServer): Throwable? {
        val result = synchronized(serverCloseLock) {
            closeWithRetryAndRetain(server) { it.close() }
        }
        synchronized(broadcastResourceLock) {
            when {
                result.retained == null && runningServer === server -> runningServer = null
                result.retained != null &&
                    (runningServer == null || runningServer === server) -> runningServer = server
            }
        }
        return result.failure
    }

    private fun releaseBroadcastResources() {
        // Local monitoring is a consumer of this exact stream generation. Stop it first without
        // touching input capture; the operator's broadcast/input controls remain independent.
        stopLocalMonitor()
        var serverToClose: RunningGuideCastServer? = null
        var streamSessionToClose: StreamSession? = null
        var startJobToCancel: Job? = null
        var preparationJobToCancel: Job? = null
        var listenerJobToCancel: Job? = null
        var wakeLockToRelease: PowerManager.WakeLock? = null
        val hadTranslationResources = synchronized(broadcastResourceLock) {
            // Generation invalidation, server detach and every child-job detach are one commit.
            // A slow previous start can therefore never attach itself after stop has returned.
            broadcastGeneration.incrementAndGet()
            val hadResources = broadcastUsesTranslation || translationPipeline != null
            startJobToCancel = serverStartJob
            // Keep the cancelled start job as a teardown barrier. Its completion handler either
            // starts the explicitly queued replacement or clears itself after the bound socket
            // has been closed.
            clearBroadcastSecrets(pendingBroadcastStart?.intent)
            pendingBroadcastStart = null
            preparationJobToCancel = translationPreparationJob
            translationPreparationJob = null
            listenerJobToCancel = listenerJob
            listenerJob = null
            serverToClose = runningServer
            streamSessionToClose = broadcastStreamSession
            broadcastStreamSession = null
            broadcastAudioPublicationCoordinator = null
            broadcastUsesTranslation = false
            testToneRequestGeneration.incrementAndGet()
            testToneRequestPending.set(false)
            testToneActive.set(false)
            wakeLockToRelease = wakeLock
            wakeLock = null
            hadResources
        }

        // Invalidate audio/listener ownership before any potentially throwing Ktor teardown.
        // Every cleanup is independent so one faulty component cannot leak the rest.
        runCatching { streamSessionToClose?.close() }
            .onFailure { Log.e(LOG_TAG, "Stream session cleanup failed", it) }
        startJobToCancel?.cancel()
        preparationJobToCancel?.cancel()
        listenerJobToCancel?.cancel()
        serverToClose?.let { server ->
            closeServerHandle(server)?.let { error ->
                Log.e(LOG_TAG, "Broadcast socket cleanup failed after retry", error)
            }
        }
        runCatching {
            wakeLockToRelease?.let { heldWakeLock ->
                if (heldWakeLock.isHeld) heldWakeLock.release()
            }
        }.onFailure { Log.e(LOG_TAG, "WakeLock cleanup failed", it) }
        releaseTranslationTestResources()
        if (hadTranslationResources) app.translationDiagnostics.end()
    }

    private fun stopAll() {
        stopBroadcast()
        stopInput()
    }

    private fun stopProjection() {
        projectionGeneration.incrementAndGet()
        val projection = mediaProjection ?: run {
            mediaProjectionCallback = null
            playbackTargetUid = null
            return
        }
        val callback = mediaProjectionCallback
        mediaProjection = null
        mediaProjectionCallback = null
        playbackTargetUid = null
        if (callback != null) runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GuideCast::Broadcast",
        ).also { it.acquire(MAX_WAKE_LOCK_MILLIS) }
    }

    private fun startForegroundForInput(playbackCapture: Boolean) {
        val kind = if (playbackCapture) {
            AudioInputKind.DEVICE_PLAYBACK
        } else {
            selectedInput?.kind ?: AudioInputKind.BUILT_IN
        }
        val foregroundType = foregroundTypeForInput(kind, Build.VERSION.SDK_INT)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("음성 입력을 준비하고 있습니다"),
            foregroundType,
        )
    }

    private fun startForegroundForBroadcast() {
        // STARTING and PAUSED still own a live AudioRecord/MediaProjection. Keep the matching
        // Android 14+ foreground type so Samsung does not revoke capture while the UI is paused.
        val activeInputKind = selectedInput?.kind.takeIf { inputJob != null }
        // A server opened without an active input has no more specific Android FGS type.
        // Normal event operation uses microphone/mediaProjection and therefore does not
        // consume Android 15's six-hour dataSync allowance.
        val foregroundType = foregroundTypeForBroadcast(activeInputKind, Build.VERSION.SDK_INT)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("로컬 방송을 준비하고 있습니다"),
            foregroundType,
        )
    }

    private fun stopServiceIfUnused() {
        val state = app.broadcastRuntime.state.value
        val noInput = state.inputPhase == InputPhase.IDLE || state.inputPhase == InputPhase.FAILED
        val noBroadcast = state.phase == BroadcastPhase.IDLE || state.phase == BroadcastPhase.FAILED
        val broadcastTeardownPending = synchronized(broadcastResourceLock) {
            // A cancelled start remains the owner of the foreground-service lifetime until its
            // completion handler has released the :8787 teardown barrier. Stopping the service
            // earlier can strand an already queued startForegroundService request: its
            // onStartCommand then observes serverStartJob, defers correctly, but Android kills the
            // process because the previous stop removed the foreground notification first.
            runningServer != null ||
                serverStartJob != null ||
                pendingBroadcastStart != null ||
                broadcastStreamSession != null
        }
        if (noInput && noBroadcast && !state.translationTestActive && !broadcastTeardownPending) {
            // Do not discard a newer startForegroundService request already accepted by Android
            // but not yet delivered to onStartCommand. Unlike stopSelf(), stopSelfResult(startId)
            // keeps the service alive when ActivityManager has assigned a later start id.
            val deliveredStartId = latestDeliveredStartId
            val stopped = if (deliveredStartId > 0) {
                stopSelfResult(deliveredStartId)
            } else {
                stopSelf()
                true
            }
            // A rejected stop means a newer command is pending. Keep the foreground contract and
            // its notification intact until that command reaches onStartCommand.
            if (stopped) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "음성 입력과 로컬 방송 상태"
                setShowBadge(false)
            },
        )
    }

    private fun updateNotification() {
        val state = app.broadcastRuntime.state.value
        val inputText = when (state.inputPhase) {
            InputPhase.ACTIVE -> "입력 켜짐"
            InputPhase.PAUSED -> "입력 일시정지"
            InputPhase.STARTING -> "입력 준비 중"
            InputPhase.FAILED -> "입력 오류"
            InputPhase.IDLE -> "입력 꺼짐"
        }
        val broadcastText = when (state.phase) {
            BroadcastPhase.LIVE -> "방송 중 · ${state.listenerCount}명"
            BroadcastPhase.PAUSED -> "방송 일시정지 · ${state.listenerCount}명"
            BroadcastPhase.STARTING -> "방송 준비 중"
            BroadcastPhase.FAILED -> "방송 오류"
            BroadcastPhase.IDLE -> "방송 꺼짐"
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("$inputText · $broadcastText"),
        )
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BroadcastService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(content)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "입력·방송 모두 중지", stopIntent)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun Intent.mediaProjectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)
        }

    private fun installedApplicationUid(packageName: String): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }.uid
    }.getOrNull()

    private suspend fun acquireProcessNativeColdLoadLease(
        key: String,
        serializeWithAllColdLoads: Boolean,
    ): NativeColdLoadTicket = app.acquireProcessNativeColdLoadLease(
        key = key,
        serializeWithAllColdLoads = serializeWithAllColdLoads,
    )

    companion object {
        private const val ACTION_START_INPUT = "app.guidecast.action.START_INPUT"
        private const val LOG_TAG = "GuideCastService"
        private const val ACTION_PAUSE_INPUT = "app.guidecast.action.PAUSE_INPUT"
        private const val ACTION_RESUME_INPUT = "app.guidecast.action.RESUME_INPUT"
        private const val ACTION_STOP_INPUT = "app.guidecast.action.STOP_INPUT"
        private const val ACTION_START_BROADCAST = "app.guidecast.action.START_BROADCAST"
        private const val ACTION_PAUSE_BROADCAST = "app.guidecast.action.PAUSE_BROADCAST"
        private const val ACTION_RESUME_BROADCAST = "app.guidecast.action.RESUME_BROADCAST"
        private const val ACTION_STOP_BROADCAST = "app.guidecast.action.STOP_BROADCAST"
        private const val ACTION_STOP_ALL = "app.guidecast.action.STOP_ALL"
        private const val ACTION_TEST_TONE = "app.guidecast.action.TEST_TONE"
        private const val ACTION_START_TRANSLATION_TEST = "app.guidecast.action.START_TRANSLATION_TEST"
        private const val ACTION_STOP_TRANSLATION_TEST = "app.guidecast.action.STOP_TRANSLATION_TEST"
        private const val ACTION_START_LOCAL_MONITOR = "app.guidecast.action.START_LOCAL_MONITOR"
        private const val ACTION_PAUSE_LOCAL_MONITOR = "app.guidecast.action.PAUSE_LOCAL_MONITOR"
        private const val ACTION_RESUME_LOCAL_MONITOR = "app.guidecast.action.RESUME_LOCAL_MONITOR"
        private const val ACTION_STOP_LOCAL_MONITOR = "app.guidecast.action.STOP_LOCAL_MONITOR"
        private const val ACTION_SET_LOCAL_MONITOR_VOLUME = "app.guidecast.action.SET_LOCAL_MONITOR_VOLUME"
        private const val EXTRA_ACCESS_MODE = "access_mode"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_SPEAKER_PIN = "speaker_pin"
        private const val EXTRA_TRANSLATION_LANGUAGES = "translation_languages"
        private const val EXTRA_SOURCE_LANGUAGE = "source_language"
        private const val EXTRA_USE_GEMMA = "use_gemma"
        private const val EXTRA_SELECTIVE_REFINEMENT = "selective_translation_refinement"
        private const val EXTRA_MEDIA_PROJECTION_RESULT_CODE = "media_projection_result_code"
        private const val EXTRA_MEDIA_PROJECTION_DATA = "media_projection_data"
        private const val EXTRA_PLAYBACK_TARGET_PACKAGE = "playback_target_package"
        private const val EXTRA_PLAYBACK_TARGET_UID = "playback_target_uid"
        private const val EXTRA_TEST_LANGUAGE = "test_language"
        private const val EXTRA_MONITOR_CHANNEL = "monitor_channel"
        private const val EXTRA_MONITOR_OUTPUT_DEVICE_ID = "monitor_output_device_id"
        private const val EXTRA_MONITOR_VOLUME = "monitor_volume"
        private const val NOTIFICATION_CHANNEL_ID = "guidecast_broadcast"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE_HZ = 16_000
        private const val RECOGNITION_HANDOFF_FRAMES = 16
        private const val TEST_TONE_FRAME_MILLIS = 20
        private const val TEST_TONE_FRAME_COUNT = 150
        private const val TEST_TONE_ACQUIRE_TIMEOUT_MILLIS = 5_000L
        private const val TEST_TONE_HZ = 1_000.0
        private const val TEST_TONE_AMPLITUDE = 0.35
        private const val MAX_TRANSCRIPT_LINES = 100
        private const val MAX_TRANSLATION_LANGUAGES = MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
        private const val MAX_OPERATOR_ERROR_DETAIL_CHARACTERS = 300
        private const val MLKIT_NATIVE_FIRST_USE_KEY_PREFIX = "mlkit-translation"
        private const val GEMMA_NATIVE_FIRST_USE_KEY_PREFIX = "gemma-translation"
        private const val SPEECH_NATIVE_FIRST_USE_KEY_PREFIX = "speech"
        private const val SPEECH_NATIVE_WARMUP_KEY_PREFIX = "speech-warm"
        private const val MLKIT_RECONCILIATION_FIRST_USE_KEY = "mlkit-reconciliation"
        /** Caps listener/drop UI and foreground-notification churn at four refreshes per second. */
        private const val STREAM_OBSERVABILITY_REFRESH_MILLIS = 250L
        private const val GEMMA_PRIMARY_ATTEMPT_TIMEOUT_MILLIS = 10_500L
        private const val GEMMA_PRIMARY_RETRY_COOLDOWN_MILLIS = 5_000L
        private const val GEMMA_PIPELINE_TRANSLATION_TIMEOUT_MILLIS = 15_000L
        private const val GEMMA_STANDARD_WARMUP_TIMEOUT_MILLIS = 30_000L
        private const val GEMMA_CONSTRAINED_WARMUP_TIMEOUT_MILLIS = 90_000L
        private const val DEFAULT_TRANSLATION_TIMEOUT_MILLIS = 4_000L
        private const val LOCAL_MONITOR_BUFFER_MILLIS = 120
        private const val LOCAL_MONITOR_START_THRESHOLD_MILLIS = 40
        private const val LOCAL_MONITOR_ROUTE_WAIT_STEP_MILLIS = 25L
        private const val LOCAL_MONITOR_ROUTE_WAIT_ATTEMPTS = 80
        /** 20 ms frames: update local-monitor UI at most twice per second. */
        private const val LOCAL_MONITOR_UI_FRAME_CADENCE = 25L
        private const val MAX_WAKE_LOCK_MILLIS = 4 * 60 * 60 * 1000L

        private fun mlKitNativeFirstUseKey(languageTag: String): String =
            "$MLKIT_NATIVE_FIRST_USE_KEY_PREFIX:$languageTag"

        private fun gemmaNativeFirstUseKey(targetLanguageTag: String): String =
            "$GEMMA_NATIVE_FIRST_USE_KEY_PREFIX:$targetLanguageTag"

        private fun speechNativeFirstUseKey(languageTag: String): String =
            "$SPEECH_NATIVE_FIRST_USE_KEY_PREFIX:$languageTag"

        private fun speechNativeWarmupKey(languageTag: String): String =
            "$SPEECH_NATIVE_WARMUP_KEY_PREFIX:$languageTag"

        private val TRANSLATION_LANGUAGES = mapOf(
            "ko" to "한국어 · Korean",
            "en" to "영어 · English",
            "ja" to "일본어 · 日本語",
            "zh" to "중국어(간체) · 中文(简体)",
            "zh-TW" to "중국어(번체·대만) · 繁體中文",
            "vi" to "베트남어 · Tiếng Việt",
            "nl" to "네덜란드어 · Nederlands",
            "es" to "스페인어 · Español",
            "ar" to "아랍어 · العربية",
        )
        private val MOONSHINE_TTS_LANGUAGES = setOf("en", "ja", "zh", "nl", "es", "ar")

        fun startInput(
            context: Context,
            mediaProjectionResultCode: Int? = null,
            mediaProjectionData: Intent? = null,
            playbackTarget: PlaybackTargetApp? = null,
        ) {
            val intent = Intent(context, BroadcastService::class.java).setAction(ACTION_START_INPUT)
            if (mediaProjectionResultCode != null && mediaProjectionData != null) {
                intent.putExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, mediaProjectionResultCode)
                intent.putExtra(EXTRA_MEDIA_PROJECTION_DATA, mediaProjectionData)
            }
            if (playbackTarget != null) {
                intent.putExtra(EXTRA_PLAYBACK_TARGET_PACKAGE, playbackTarget.packageName)
                intent.putExtra(EXTRA_PLAYBACK_TARGET_UID, playbackTarget.uid)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseInput(context: Context) = sendAction(context, ACTION_PAUSE_INPUT)

        fun resumeInput(context: Context) = sendAction(context, ACTION_RESUME_INPUT)

        fun stopInput(context: Context) = sendAction(context, ACTION_STOP_INPUT)

        fun start(
            context: Context,
            accessMode: OperatorAccessMode,
            pin: CharArray? = null,
            speakerPin: CharArray? = null,
            translationLanguages: Array<String> = emptyArray(),
            sourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
            useGemma: Boolean = false,
            selectiveTranslationRefinement: Boolean = false,
        ) {
            val intent = Intent(context, BroadcastService::class.java)
                .setAction(ACTION_START_BROADCAST)
                .putExtra(EXTRA_ACCESS_MODE, accessMode.name)
                .putExtra(EXTRA_TRANSLATION_LANGUAGES, translationLanguages)
                .putExtra(EXTRA_SOURCE_LANGUAGE, sourceLanguageTag)
                .putExtra(EXTRA_USE_GEMMA, useGemma)
                .putExtra(EXTRA_SELECTIVE_REFINEMENT, selectiveTranslationRefinement && useGemma)
            if (pin != null) intent.putExtra(EXTRA_PIN, pin)
            if (speakerPin != null) intent.putExtra(EXTRA_SPEAKER_PIN, speakerPin)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseBroadcast(context: Context) = sendAction(context, ACTION_PAUSE_BROADCAST)

        fun resumeBroadcast(context: Context) = sendAction(context, ACTION_RESUME_BROADCAST)

        fun stopBroadcast(context: Context) = sendAction(context, ACTION_STOP_BROADCAST)

        fun stop(context: Context) = sendAction(context, ACTION_STOP_ALL)

        fun playTestTone(context: Context) = sendAction(context, ACTION_TEST_TONE)

        fun startTranslationTest(
            context: Context,
            languageTag: String,
            sourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
            useGemma: Boolean,
            selectiveTranslationRefinement: Boolean = false,
        ) {
            context.startService(
                Intent(context, BroadcastService::class.java)
                    .setAction(ACTION_START_TRANSLATION_TEST)
                    .putExtra(EXTRA_TEST_LANGUAGE, languageTag)
                    .putExtra(EXTRA_SOURCE_LANGUAGE, sourceLanguageTag)
                    .putExtra(EXTRA_USE_GEMMA, useGemma)
                    .putExtra(EXTRA_SELECTIVE_REFINEMENT, selectiveTranslationRefinement && useGemma),
            )
        }

        fun stopTranslationTest(context: Context) =
            sendAction(context, ACTION_STOP_TRANSLATION_TEST)

        fun startLocalMonitor(context: Context, channelId: String, outputDeviceId: Int) {
            context.startService(
                Intent(context, BroadcastService::class.java)
                    .setAction(ACTION_START_LOCAL_MONITOR)
                    .putExtra(EXTRA_MONITOR_CHANNEL, channelId)
                    .putExtra(EXTRA_MONITOR_OUTPUT_DEVICE_ID, outputDeviceId),
            )
        }

        fun pauseLocalMonitor(context: Context) =
            sendAction(context, ACTION_PAUSE_LOCAL_MONITOR)

        fun resumeLocalMonitor(context: Context) =
            sendAction(context, ACTION_RESUME_LOCAL_MONITOR)

        fun stopLocalMonitor(context: Context) =
            sendAction(context, ACTION_STOP_LOCAL_MONITOR)

        fun setLocalMonitorVolume(context: Context, gain: Float) {
            context.startService(Intent(context, BroadcastService::class.java)
                .setAction(ACTION_SET_LOCAL_MONITOR_VOLUME)
                .putExtra(EXTRA_MONITOR_VOLUME, gain))
        }

        private fun sendAction(context: Context, action: String) {
            context.startService(Intent(context, BroadcastService::class.java).setAction(action))
        }
    }
}

private fun TranslationWorkerState.toBroadcastChannelWorkerState(): BroadcastChannelWorkerState =
    when (this) {
        TranslationWorkerState.IDLE -> BroadcastChannelWorkerState.IDLE
        TranslationWorkerState.ACTIVE -> BroadcastChannelWorkerState.ACTIVE
        TranslationWorkerState.DEGRADED -> BroadcastChannelWorkerState.DEGRADED
    }

private class TraditionalChineseTranslatingEngine(
    private val delegate: TextTranslationEngine,
) : BoundedQueuedTranslationEngine {
    override val maximumCallDurationMillis: Long
        get() = (delegate as? BoundedQueuedTranslationEngine)?.maximumCallDurationMillis ?: 4_000L
    override suspend fun translateWithContext(
        text: String,
        contextBefore: String?,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String {
        val raw = if (delegate is ContextualTextTranslationEngine) {
            delegate.translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
        } else {
            delegate.translate(text, sourceLanguageTag, targetLanguageTag)
        }
        return convertToTraditionalChinese(raw)
    }

    override suspend fun translate(
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String = translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
}

internal fun convertToTraditionalChinese(text: String): String {
    if (text.isEmpty()) return text
    return OpenCcSimplifiedToTraditionalConverter.convert(text)
}

internal class TranslationSessionCoordinator(
    private val lock: Any = Any(),
    initialSessionId: Long = 0L,
) {
    private val generation = AtomicLong(initialSessionId)
    private val active = AtomicBoolean(false)

    fun beginSession(): Long = synchronized(lock) {
        active.set(true)
        generation.incrementAndGet()
    }

    fun isSessionCurrent(sessionId: Long): Boolean =
        generation.get() == sessionId

    fun currentSessionId(): Long = generation.get()

    fun isSessionActive(): Boolean = active.get()

    fun handleSessionFailure(
        sessionId: Long,
        onStateUpdate: () -> Unit,
        onReleaseResources: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!isSessionCurrent(sessionId)) return false
        try {
            onStateUpdate()
        } finally {
            generation.incrementAndGet()
            active.set(false)
        }
        onReleaseResources()
        true
    }

    fun releaseResources(
        expectedSessionId: Long? = null,
        onRelease: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (expectedSessionId != null && !isSessionCurrent(expectedSessionId)) {
            return false
        }
        generation.incrementAndGet()
        active.set(false)
        onRelease()
        true
    }
}
