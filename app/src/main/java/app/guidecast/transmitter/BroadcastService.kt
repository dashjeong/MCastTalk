package app.guidecast.transmitter

import app.guidecast.core.translation.protectedTranslationReviewMessage

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
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.PcmFrame
import app.guidecast.core.audio.PcmSineWaveGenerator
import app.guidecast.core.audio.WebAudioInputBridge
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.server.GuideCastBroadcastStatus
import app.guidecast.core.server.GuideCastBroadcastPhase
import app.guidecast.core.server.GuideCastChannelReadiness
import app.guidecast.core.server.GuideCastChannelStatus
import app.guidecast.core.server.GuideCastListenerNextAction
import app.guidecast.core.server.BroadcastAccess
import app.guidecast.core.server.ReplayCaptionSnapshot
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
import app.guidecast.core.translation.TranslationStyle
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

private data class TranslationPreparationResult(
    val providerLabel: String,
    val warning: String?,
)

private const val MAX_STANDARD_NATIVE_SUPPORT_RELOADS = 2

private data class PendingStreamingInput(
    val intent: Intent,
    val request: StreamingInputPreparationRequest,
)

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
    private var recordingCaptionJob: Job? = null
    private var translationPreparationJob: Job? = null
    @Volatile private var relayMicrophoneRequested = false
    @Volatile private var streamingInputSuspended = false
    @Volatile private var streamingTranslationRequest: StreamingTranslationRequest? = null
    @Volatile private var pendingStreamingInput: PendingStreamingInput? = null
    private var relayArchiveSessionId: Long? = null
    private var translationSupportPreparationJob: Job? = null
    private var selectiveRefinementRecovery: SelectiveRefinementRecovery? = null
    private var translationHealthJob: Job? = null
    private var translationPipeline: RunningTranslationPipeline? = null
    @Volatile private var geminiLiveSessions: List<LiveAudioSession> = emptyList()
    private var nativeLearningSessions: List<NativeLearningSession> = emptyList()
    @Volatile private var nativeRelayLifecycle: NativeRelayChannelLifecycle? = null
    @Volatile private var nativeRelayConnections: Map<String, LiveAudioSession> = emptyMap()
    @Volatile private var nativeGeminiTimings: Map<String, NativeLiveTiming> = emptyMap()
    private fun nativeTimingFor(channelId: String, sessionId: Long) = nativeGeminiTimings[channelId]?.takeIf { it.nativeAudioSessionId == sessionId }
    private var nativeWebTimingLease: java.io.Closeable? = null
    private val nativeAudioPlaybackEpoch = AtomicLong(0)
    private var previewPlaybackBoundary: NativeAudioPlaybackBoundary? = null
    private var previewPlaybackChannelId: String? = null
    private var previewPlaybackSessionId: Long? = null
    private var localMonitorPlaybackBoundary: NativeAudioPlaybackBoundary? = null
    private var nativePlaybackOwnerId = 0L
    @Volatile private var nativeAudioRetiredByChannel: Map<String, NativeAudioRetiredTurns> = emptyMap()
    private val nonNativeRetiredTurns = NativeAudioRetiredTurns()
    private fun retiredTurnsFor(channelId: String) = nativeAudioRetiredByChannel[channelId] ?: nonNativeRetiredTurns
    private var geminiLivePreview: StreamSession? = null
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
    private var localMonitorNativeSessionId: Long? = null
    @Volatile private var localMonitorPlaybackReceipt: NativeLocalPlaybackSnapshot? = null
    private val localMonitorPaused = AtomicBoolean(false)
    private val localMonitorPlaybackEpoch = AtomicLong(0)
    private val localMonitorGeneration = AtomicLong(0)
    @Volatile private var recognitionFrames: Channel<PcmAudioFrame>? = null
    private var runningServer: RunningGuideCastServer? = null
    private var webBroadcastLease: WebBroadcastLease? = null
    /** Exact audio generation shared by the server and every producer of this broadcast. */
    @Volatile private var broadcastStreamSession: StreamSession? = null
    /** Per-session ordering: language channels stay parallel; an operator tone owns them all. */
    @Volatile private var broadcastAudioPublicationCoordinator:
        ChannelAudioPublicationCoordinator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewalJob: Job? = null
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
    private val diagnosticSessionId = java.util.UUID.randomUUID().toString()
    private var lastCaptureDiagnosticMillis = 0L
    private var lastRecognitionDiagnosticMillis = 0L
    private var recognitionPartialCount = 0L
    private var recognitionFinalCount = 0L
    private fun recordInputControl(action: ServiceFlowAction, before: InputPhase, after: InputPhase,
        reason: ServiceFlowReason = ServiceFlowReason.REQUEST) {
        RuntimeDiagnosticLog.record("input_control", serviceFlowSnapshot(app.translationApiSettings.state.value,
            diagnosticSessionId, inputGeneration.get(), action, reason, before, after))
    }
    private val broadcastGeneration = AtomicLong(0)
    @Volatile private var latestDeliveredStartId = 0
    // Resetting old input during a new start must not stopSelf the command being dispatched.
    @Volatile private var foregroundStartDispatch = false
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
        nativePlaybackOwnerId = NativeLocalPlaybackProgress.beginOwner()
        audioManager = getSystemService(AudioManager::class.java)
        createNotificationChannel()
        serviceScope.launch {
            var previous = app.broadcastRuntime.state.value
            while (true) {
                delay(60_000L)
                val current = app.broadcastRuntime.state.value
                if (current.inputPhase == InputPhase.ACTIVE ||
                    current.phase == BroadcastPhase.LIVE || current.translationTestActive
                ) RuntimeDiagnosticLog.record("pipeline_heartbeat", pipelineHeartbeat(current, previous))
                previous = current
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestDeliveredStartId = startId
        val action = intent?.action
        val foregroundStart = action == ACTION_START_INPUT || action == ACTION_START_BROADCAST || action == ACTION_START_RELAY_MICROPHONE
        foregroundStartDispatch = foregroundStart
        try {
            // Satisfy Android's foreground deadline before archive IO, validation, deferred
            // server teardown or input reset. The specific capture type is promoted later.
            if (foregroundStart) {
                val beforePromotion = SystemClock.elapsedRealtime()
                startForegroundForBroadcast()
                RuntimeDiagnosticLog.durableRecord("service_foreground", "stage=PROMOTED action=" +
                    (if (action in setOf(ACTION_START_INPUT, ACTION_START_RELAY_MICROPHONE)) "INPUT_START" else "BROADCAST_START") +
                    " elapsed_ms=${SystemClock.elapsedRealtime() - beforePromotion}")
            }
            when (action) {
                ACTION_START_INPUT -> startInput(intent)
                ACTION_START_RELAY_MICROPHONE -> {
                    val current = app.broadcastRuntime.state.value
                    if (relayMicrophoneRequestMatches(intent.getStringExtra(EXTRA_RECORDING_ID), current)) startRelayMicrophone()
                }
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
        } finally {
            foregroundStartDispatch = false
            if (foregroundStart) stopServiceIfUnused()
        }
        return START_NOT_STICKY
    }

    private fun handleActionFailure(action: String?, error: Throwable) {
        Log.e(LOG_TAG, "Service action failed: $action", error)
        val message = error.message ?: error.javaClass.simpleName
        when (action) {
            ACTION_START_INPUT, ACTION_START_RELAY_MICROPHONE -> failInput("입력을 시작하지 못했습니다: $message")
            ACTION_START_BROADCAST -> {
                releaseBroadcastResources()
                app.recordings.finish(failed = true)
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
        app.broadcastRuntime.invalidateInputRequest()
        app.translationApiSettings.endSessionLearning()
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
        val current = app.broadcastRuntime.state.value
        if (current.isInterpreterRelay && current.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED)) {
            startRelayMicrophone()
            return
        }
        if (inputJob != null) return
        streamingInputSuspended = false
        val request = streamingTranslationRequest
        if (request != null && request.broadcastGeneration == broadcastGeneration.get() &&
            current.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED) &&
            request.translationLanguages.isNotEmpty()) {
            startStreamingInput(intent, request)
        } else startInputCapture(intent)
    }

    private fun startStreamingInput(intent: Intent, request: StreamingTranslationRequest) {
        val input = app.audioInputRepository.selectedDevice.value ?: run {
            failInput("사용할 입력을 먼저 선택하세요."); return
        }
        if (translationPipeline != null && recognitionFrames != null && translationSessionCoordinator.isSessionActive()) {
            startInputCapture(intent)
            return
        }
        val generation = inputGeneration.incrementAndGet()
        inputStopCompletion.supersede()
        pendingStreamingInput = PendingStreamingInput(Intent(intent), StreamingInputPreparationRequest(
            generation, request.broadcastGeneration, input.platformId, input.kind))
        app.broadcastRuntime.update { it.copy(inputPhase = InputPhase.STARTING, inputStopping = false, inputLabel = input.label,
            inputErrorMessage = null, translationWarning = "입력과 통역을 준비 중입니다. 방송 주소와 이력은 유지됩니다.") }
        if (translationPreparationJob?.isActive == true) return
        val stream = broadcastStreamSession ?: run { failInput("방송을 다시 시작한 뒤 입력을 켜세요."); return }
        val publication = broadcastAudioPublicationCoordinator ?: run { failInput("방송 출력을 준비하지 못했습니다."); return }
        val sessionId = beginTranslationSession()
        app.translationDiagnostics.begin()
        createTranslationPreparationJob(request.translationLanguages, request.sourceLanguageTag, request.useGemma,
            sessionId, request.archiveSessionId, request.broadcastGeneration, stream, publication,
            request.selectiveTranslationRefinement).also { translationPreparationJob = it }.start()
        updateNotification()
    }

    private fun startRelayMicrophone() {
        if (inputJob != null || translationPreparationJob?.isActive == true) return
        val current = app.broadcastRuntime.state.value
        val stream = broadcastStreamSession ?: return
        if (!current.isInterpreterRelay || current.phase !in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED)) return
        val api = app.translationApiSettings.state.value
        val input = app.audioInputRepository.selectedDevice.value
        val permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val issue = relayInputIssue(input?.kind, permission, runCatching { audioManager.isMicrophoneMute }.getOrNull())
        if (issue != null || !api.usesNativeLiveAudio || !app.translationApiSettings.authorized(api) || !api.allowLiveAudio) {
            app.broadcastRuntime.update { it.copy(inputPhase = InputPhase.FAILED,
                inputErrorMessage = issue?.message ?: "통역 AI 설정과 음성 전송 동의를 확인한 뒤 마이크를 켜세요.") }
            return
        }
        val publication = broadcastAudioPublicationCoordinator ?: return
        relayMicrophoneRequested = true
        inputStopCompletion.supersede()
        streamingInputSuspended = false
        val sessionId = beginTranslationSession()
        app.broadcastRuntime.update { it.copy(inputPhase = InputPhase.STARTING, inputStopping = false, relayPhase = InterpreterRelayPhase.CONNECTING,
            inputErrorMessage = null, translationWarning = "마이크 통역 연결을 준비하고 있습니다. 방송 주소는 유지됩니다.") }
        app.translationDiagnostics.begin()
        val relay = app.interpreterRelaySettings.state.value
        createTranslationPreparationJob(
            translationLanguages = current.translationChannels.map { it.languageTag },
            sourceLanguageTag = relay.source, useGemma = false, selectiveTranslationRefinement = false,
            sessionId = sessionId, archiveSessionId = relayArchiveSessionId,
            broadcastSession = broadcastGeneration.get(), streamSession = stream,
            audioPublicationCoordinator = publication,
        ).also { translationPreparationJob = it }.start()
    }

    private fun startInputCapture(intent: Intent) {
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
        app.recordedPlayback.beginInput()
        inputStopCompletion.supersede()
        val generation = inputGeneration.incrementAndGet()
        lastCaptureDiagnosticMillis = 0L
        recordInputControl(ServiceFlowAction.INPUT_START, app.broadcastRuntime.state.value.inputPhase, InputPhase.STARTING)
        val signalTracker = InputSignalTracker()
        app.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.STARTING,
                inputStopping = false,
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
                Handler(Looper.getMainLooper()).post {
                    if (isInputGenerationCurrent(generation) && inputJob === job) {
                        failInput(error.message ?: "오디오 입력을 시작하지 못했습니다.")
                    }
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
                    config = relayMicrophoneCaptureConfig(app.microphoneNoiseSettings.profileFor(input.kind),
                        app.broadcastRuntime.state.value.isInterpreterRelay, app.interpreterRelaySettings.state.value.localPlayback),
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
        val diagnosticNow = SystemClock.elapsedRealtime()
        if (signal.frameCount == 1L || diagnosticNow - lastCaptureDiagnosticMillis >= 2_000L) {
            lastCaptureDiagnosticMillis = diagnosticNow
            RuntimeDiagnosticLog.record("capture_progress", "session_id=$diagnosticSessionId input_generation=$generation " +
                "frames=${signal.frameCount} audible_frames=${signal.audibleFrameCount} " +
                "last_frame_ns=${frame.capturedAtElapsedRealtimeNanos} observed_ms=$diagnosticNow")
        }
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
        if (relayInputProcessingEnabled(current)) nativeRelayConnections.forEach { (target, connection) ->
            if (isInputGenerationCurrent(generation) && !inputPaused.get() && nativeRelayLifecycle?.accepts(target) == true) connection.offer(pcm)
        }
        val recognitionInput = recognitionFrames.takeIf {
            current.translationTestActive || current.phase == BroadcastPhase.LIVE
        }
        // Output congestion never stops this capture frame from reaching speech recognition.
        if (!publishSourceThenForwardRecognitionFrame(
                frame = pcm,
            publishSource = relayInputProcessingEnabled(current) && current.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED),
                publication = broadcastAudioPublicationCoordinator,
                publish = { captured ->
                    val latest = app.broadcastRuntime.state.value
                    if (isInputGenerationCurrent(generation) && !inputPaused.get() && !testToneActive.get() && relayInputProcessingEnabled(latest)) {
                        broadcastStreamSession?.tryPublish("source", captured)
                    }
                },
                recognition = recognitionInput,
            )) {
            app.broadcastRuntime.update { latest ->
                if (!isInputGenerationCurrent(generation)) latest else latest.copy(
                    recognitionDroppedFrameCount = latest.recognitionDroppedFrameCount + 1,
                )
            }
        }
    }

    private fun pauseInput() {
        app.broadcastRuntime.invalidateInputRequest()
        val current = app.broadcastRuntime.state.value
        if (!canTurnInputOff(current.inputPhase, current.phase == BroadcastPhase.STARTING ||
                translationPreparationJob != null || pendingStreamingInput != null || relayMicrophoneRequested)) return
        recordInputControl(ServiceFlowAction.PAUSE_REQUEST, app.broadcastRuntime.state.value.inputPhase, InputPhase.PAUSED)
        stopInput()
        app.broadcastRuntime.update { it.copy(inputPhase = InputPhase.PAUSED) }
        updateNotification()
    }

    private fun resumeInput() {
        if (app.broadcastRuntime.state.value.isInterpreterRelay) {
            startRelayMicrophone()
            return
        }
        if (app.broadcastRuntime.state.value.inputPhase !in setOf(InputPhase.PAUSED, InputPhase.IDLE, InputPhase.FAILED)) return
        if (app.audioInputRepository.selectedDevice.value?.kind == AudioInputKind.DEVICE_PLAYBACK) {
            failInput("앱 소리 캡처는 입력 다시 켜기에서 Android 캡처 동의를 새로 허용하세요.")
            return
        }
        recordInputControl(ServiceFlowAction.RESUME_REQUEST, app.broadcastRuntime.state.value.inputPhase, InputPhase.STARTING)
        startInput(Intent())
    }

    private fun suspendInputTranslation() {
        val releaseTranslation = synchronized(broadcastResourceLock) {
            streamingInputSuspended = true
            pendingStreamingInput = null
            relayMicrophoneRequested = false
            val hadPreparation = translationPreparationJob != null
            translationPreparationJob?.cancel()
            translationPreparationJob = null
            app.broadcastRuntime.state.value.isInterpreterRelay || broadcastUsesTranslation || hadPreparation
        }
        if (releaseTranslation) releaseTranslationTestResources()
    }

    private fun stopInput(invalidateRequest: Boolean = true) {
        if (invalidateRequest) app.broadcastRuntime.invalidateInputRequest()
        // Invalidate capture before closing translation queues so an inflight producer cannot
        // send another frame into a replacement provider or the source broadcast.
        invalidateInputCapture()
        suspendInputTranslation()
        if (app.broadcastRuntime.state.value.isInterpreterRelay) {
            app.broadcastRuntime.update { it.copy(relayPhase = nativeRelayPhaseAfterInputStop(it.relayPhase)) }
        }
        recordInputControl(ServiceFlowAction.STOP_REQUEST, app.broadcastRuntime.state.value.inputPhase, InputPhase.IDLE)
        if (app.broadcastRuntime.state.value.translationTestActive) {
            stopTranslationTest(preservePass = true)
        }
        inputPaused.set(false)
        stopProjection()
        selectedInput = null
        app.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.IDLE,
                recognitionErrorMessage = null,
                translationChannels = current.translationChannels.map { it.copy(
                    translationState = BroadcastChannelWorkerState.IDLE, synthesisState = BroadcastChannelWorkerState.IDLE) },
                translationWarning = if (current.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED))
                    "입력 꺼짐 · 새 음성을 수집·전송하지 않습니다. 이미 받은 통역 출력이 잠시 이어질 수 있습니다." else current.translationWarning,
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
        app.broadcastRuntime.invalidateInputRequest()
        invalidateInputCapture()
        suspendInputTranslation()
        if (app.broadcastRuntime.state.value.isInterpreterRelay) {
            app.broadcastRuntime.update { it.copy(relayPhase = InterpreterRelayPhase.FAILED) }
        }
        recordInputControl(ServiceFlowAction.INPUT_FAILURE, app.broadcastRuntime.state.value.inputPhase, InputPhase.FAILED,
            ServiceFlowReason.UNKNOWN)
        if (app.broadcastRuntime.state.value.translationTestActive) {
            stopTranslationTest(preservePass = false)
        }
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
        val generation = inputGeneration.incrementAndGet()
        val stop = inputStopCompletion.beginStop(inputJob)
        inputJob = null
        app.recordedPlayback.pauseInput()
        app.broadcastRuntime.update { it.copy(inputStopping = stop.jobs.isNotEmpty()) }
        stop.jobs.forEach(Job::cancel)
        fun finishIfClosed() {
            inputStopCompletion.completeIfReady(stop.token) {
                app.broadcastRuntime.update { current ->
                    if (isInputGenerationCurrent(generation)) current.copy(inputStopping = false) else current
                }
            }
        }
        if (stop.jobs.isEmpty()) finishIfClosed()
        else stop.jobs.forEach { job -> job.invokeOnCompletion { finishIfClosed() } }
    }

    private fun isInputGenerationCurrent(generation: Long): Boolean =
        inputGeneration.get() == generation

    private fun clearBroadcastSecrets(intent: Intent?) {
        intent?.getCharArrayExtra(EXTRA_PIN)?.fill('\u0000')
        intent?.getCharArrayExtra(EXTRA_SPEAKER_PIN)?.fill('\u0000')
    }

    private fun startBroadcast(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_INTERPRETER_RELAY, false) && !intent.getBooleanExtra(EXTRA_DEFER_RELAY_INPUT, false) && app.broadcastRuntime.state.value.phase !in
            setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED)) {
            val input = app.audioInputRepository.selectedDevice.value
            val permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val muted = runCatching { getSystemService(AudioManager::class.java).isMicrophoneMute }.getOrNull()
            val issue = relayInputIssue(input?.kind, permission, muted)
            RuntimeDiagnosticLog.record("relay_preflight", "input_kind=${input?.kind ?: "UNKNOWN"} permission=$permission " +
                "system_muted=${muted ?: "UNKNOWN"} input_phase=${app.broadcastRuntime.state.value.inputPhase} " +
                "capture_state=${app.audioCaptureEngine.diagnostics.value.state} " +
                "client_silenced=${app.audioCaptureEngine.diagnostics.value.clientSilenced ?: "UNKNOWN"} issue=${issue?.name ?: "NONE"}")
            if (issue != null) {
                clearBroadcastSecrets(intent)
                app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.FAILED,
                    relayPhase = InterpreterRelayPhase.FAILED, errorMessage = issue.message) }
                stopServiceIfUnused()
                return
            }
        }
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
            if (runningServer != null || broadcastStreamSession != null) {
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
        synchronized(broadcastResourceLock) {
            if (webBroadcastLease == null) {
                webBroadcastLease = app.webBroadcastOwnership.tryAcquire("streaming")
                if (webBroadcastLease == null) {
                    clearBroadcastSecrets(intent)
                    app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.FAILED,
                        errorMessage = "다른 메뉴의 웹 방송을 종료한 뒤 시작하세요.") }
                    stopServiceIfUnused()
                    return
                }
            }
        }
        if (app.broadcastRuntime.state.value.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED))
            app.broadcastRuntime.invalidateInputRequest()
        val generation = broadcastGeneration.incrementAndGet()
        if (app.broadcastRuntime.state.value.phase != BroadcastPhase.PAUSED) app.recordings.finish(app.broadcastRuntime.state.value.phase == BroadcastPhase.FAILED)
        val relay = intent.getBooleanExtra(EXTRA_INTERPRETER_RELAY, false)
        if (relay) stopInput(invalidateRequest = false)
        else streamingInputSuspended = false
        relayMicrophoneRequested = relay && !intent.getBooleanExtra(EXTRA_DEFER_RELAY_INPUT, false)
        val broadcastTitle = intent.getStringExtra(EXTRA_BROADCAST_TITLE).orEmpty()
        val runMode = intent.getStringExtra(EXTRA_RUN_MODE)
            ?.let { runCatching { BroadcastRunMode.valueOf(it) }.getOrNull() }
            ?: BroadcastRunMode.NETWORK
        val mode = intent.getStringExtra(EXTRA_ACCESS_MODE)
            ?.let { runCatching { OperatorAccessMode.valueOf(it) }.getOrNull() }
            ?: OperatorAccessMode.QR_TOKEN
        startForegroundForBroadcast()
        app.broadcastRuntime.update { current ->
            current.copy(
                phase = BroadcastPhase.STARTING,
                broadcastTitle = broadcastTitle,
                recordingWarning = null,
                isInterpreterRelay = relay,
                relayPhase = if (relay && relayMicrophoneRequested) InterpreterRelayPhase.CONNECTING else InterpreterRelayPhase.IDLE,
                relayPlayedBytes = 0,
                relayReferenceCharacters = 0,
                relayReferenceEntries = 0,
                relayAvailableReferenceEntries = 0,
                relayContext = null,
                runMode = runMode,
                recognitionErrorMessage = null,
                recognitionDroppedFrameCount = 0,
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
                if (relay) requireNativeRelayLanguageSelection(sourceLanguageTag, translationLanguages)
                else {
                    require(
                        translationLanguages.size <= MAX_TRANSLATION_LANGUAGES &&
                            translationLanguages.distinct().size == translationLanguages.size &&
                            translationLanguages.all(TRANSLATION_LANGUAGES::containsKey) &&
                            translationLanguages.none {
                                normalizeSourceLanguage(it) == normalizeSourceLanguage(sourceLanguageTag)
                            },
                    ) { "지원하지 않는 번역 언어가 포함되어 있습니다." }
                    requireSupportedSourceLanguage(sourceLanguageTag)
                }
                if (relay) {
                    check(app.interpreterRelaySettings.state.value.let { it.localPlayback || it.networkBroadcast }) {
                        "기기 재생 또는 LAN 방송을 켜 주세요."
                    }
                    check(translationLanguages.size in 1..MAX_RELAY_LANGUAGES && app.translationApiSettings.state.value.usesNativeLiveAudio) {
                        "통역 중계는 Live 음성 서비스와 출력 언어 1~5개를 선택하세요."
                    }
                }
                runBroadcastServer(
                    runMode = runMode,
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
                    app.recordings.finish(failed = true)
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
            releaseWebBroadcastLeaseIfUnused()
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

    private fun startPreviewPlayback(languageTag: String, sessionId: Long, relayStream: StreamSession? = null) {
        val channelId = languageTag.lowercase(Locale.ROOT)
        val stageTrace = nativeTimingFor(channelId, sessionId)?.stages
        val nativePlayback = app.translationApiSettings.state.value.usesNativeLiveAudio
        val subscription = (relayStream ?: translationPipeline?.streamSession ?: geminiLivePreview)?.subscribeLocalMonitor(channelId,
            preserveNativeAudio = nativePlayback)
            ?: error("통역 음성 시험 세션이 종료되었습니다.")
        val track = try {
            createMonitorAudioTrack(MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ, nativePlayback)
        } catch (error: Throwable) {
            subscription.close()
            throw error
        }
        val trackReleased = AtomicBoolean(false)
        val playbackBoundary = if (nativePlayback) NativeAudioPlaybackBoundary(nativeAudioPlaybackEpoch.get()) else null
        fun releaseTrackOnce() {
            if (trackReleased.compareAndSet(false, true)) releaseMonitorAudioTrack(track)
        }
        lateinit var playbackJob: Job
        playbackJob = serviceScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val headProgress = NativeAudioHeadProgress()
            val headReady = CompletableDeferred<Unit>()
            var timingHeadJob: Job? = null
            val initialEpoch = nativeAudioPlaybackEpoch.get()
            val writtenBytes = AtomicLong(0)
            val unwrittenDequeuedBytes = AtomicLong(0)
            val inFlightTail = AtomicLong(0)
            val epochChanged = AtomicBoolean(false)
            val rejectedNotified = AtomicBoolean(false)
            val progressClosed = AtomicBoolean(false)
            fun publishProgress(closed: Boolean = false) {
                if (!nativePlayback) return
                val head = synchronized(translationResourceLock) {
                    val epoch = nativeAudioPlaybackEpoch.get()
                    if (epoch != initialEpoch) epochChanged.set(true)
                    runCatching { track.playbackHeadPosition }.getOrNull()
                        ?.let { headProgress.samplePosition(it, epoch) }.also { stageTrace?.head(it, epoch) }
                }
                val snapshot = NativeLocalPlaybackSnapshot(sessionId, subscription.bufferSnapshot(), writtenBytes.get(),
                    head, unwrittenDequeuedBytes.get() + inFlightTail.get(), epochChanged.get(), closed || progressClosed.get(),
                    playbackOwner = nativePlaybackOwnerId)
                NativeLocalPlaybackProgress.update(snapshot)
                if (snapshot.buffer.rejectedBytes > 0 && rejectedNotified.compareAndSet(false, true)) {
                    app.broadcastRuntime.update { it.copy(translationWarning =
                        "기기 재생 대기열이 가득 차 일부 음성을 받지 못했습니다. 받은 음성은 계속 재생되며 저장 이력에서 다시 들을 수 있습니다.") }
                }
                if (closed) RuntimeDiagnosticLog.durableRecord("native_local_output", snapshot.countsOnly().toString())
            }
            try {
                track.play()
                publishProgress()
                timingHeadJob = if (nativePlayback) launch {
                    headReady.await()
                    var signalSeen = false
                    val fastPollEnd = System.nanoTime() + 6_000_000_000L
                    while (isActive && isTranslationSessionCurrent(sessionId)) {
                            val signalReached = synchronized(translationResourceLock) {
                                val epoch = nativeAudioPlaybackEpoch.get()
                                runCatching { track.playbackHeadPosition }.getOrNull()
                                    ?.let { headProgress.reached(it, epoch) } == true
                            }
                            if (!signalSeen && signalReached) {
                                nativeTimingFor(channelId, sessionId)?.localSignalHead()
                                signalSeen = true
                            }
                            publishProgress()
                            delay(if (signalSeen || System.nanoTime() >= fastPollEnd) 100 else 10)
                        }
                } else null
                while (true) {
                    val frame = subscription.receiveNext() ?: break
                    stageTrace?.dequeued(frame.nativeTimingTurn, frame.bytes.size, frame.localEnqueuedAtNanos)
                    if (!retiredTurnsFor(channelId).allows(frame.utteranceSequence)) {
                        unwrittenDequeuedBytes.addAndGet(frame.bytes.size.toLong())
                        publishProgress(); continue
                    }
                    inFlightTail.set(frame.bytes.size.toLong())
                    val playbackEpoch = synchronized(translationResourceLock) {
                        nativeAudioPlaybackEpoch.get()
                    }
                    val frameStats = frame.bytes.pcmS16LeSignalStats()
                    val complete = writeLocalMonitorPcm(
                        frame.bytes,
                        isCurrent = { isTranslationSessionCurrent(sessionId) && nativeAudioPlaybackEpoch.get() == playbackEpoch &&
                            retiredTurnsFor(channelId).allows(frame.utteranceSequence) },
                        writeNonBlocking = { bytes, offset, count ->
                            synchronized(translationResourceLock) {
                                if (!isTranslationSessionCurrent(sessionId) || nativeAudioPlaybackEpoch.get() != playbackEpoch ||
                                    !retiredTurnsFor(channelId).allows(frame.utteranceSequence)) 0 else
                                    retiredTurnsFor(channelId).writeIfAllowed(frame.utteranceSequence) {
                                        if (playbackBoundary == null) track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                                        else playbackBoundary.write(frame.utteranceSequence, playbackEpoch, track.playbackHeadPosition) {
                                            track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                                        }
                                    }.also { written ->
                                        if (written > 0 && written % 2 == 0 && written <= count) {
                                            writtenBytes.addAndGet(written.toLong()); inFlightTail.addAndGet(-written.toLong())
                                            stageTrace?.written(frame.nativeTimingTurn, bytes, offset, written, playbackEpoch)
                                            if (frame.nativeAudioSessionId == sessionId)
                                                nativeTimingFor(channelId, sessionId)?.localWrite(written / 2)
                                            if (nativePlayback) {
                                                headProgress.accepted(bytes, offset, written, playbackEpoch)
                                                headReady.complete(Unit)
                                            }
                                        }
                                    }
                            }
                        },
                    )
                    if (!complete) {
                        unwrittenDequeuedBytes.addAndGet(inFlightTail.getAndSet(0))
                        publishProgress()
                        if (isTranslationSessionCurrent(sessionId) && (nativeAudioPlaybackEpoch.get() != playbackEpoch ||
                            !retiredTurnsFor(channelId).allows(frame.utteranceSequence))) continue
                        break
                    }
                    inFlightTail.set(0)
                    publishProgress()
                    if (frameStats.sampleCount > 0 &&
                        frameStats.nonZeroSamples > 0 &&
                        frameStats.rms > 0f &&
                        frameStats.peak > MIN_TRANSLATION_TEST_PEAK
                    ) {
                        if (relayStream != null) app.broadcastRuntime.update { current ->
                            if (isTranslationSessionCurrent(sessionId) && current.isInterpreterRelay)
                                current.copy(relayPlayedBytes = current.relayPlayedBytes + frame.bytes.size) else current
                        } else recordTranslationTestPlaybackWrite(
                            sessionId = sessionId, languageTag = languageTag, writtenBytes = frame.bytes.size,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (relayStream != null && isTranslationSessionCurrent(sessionId)) {
                    app.broadcastRuntime.update { it.copy(translationWarning = "기기 음성 재생을 시작하지 못했습니다. 출력 장치를 확인하세요.") }
                    return@launch
                }
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
                progressClosed.set(true)
                timingHeadJob?.cancel()
                subscription.close()
                publishProgress(closed = true)
                stageTrace?.let {
                    val receipt = it.snapshot(close = true)
                    val rows = receipt.getJSONArray("turns")
                    receipt.remove("turns")
                    RuntimeDiagnosticLog.durableRecord("native_audio_stages", receipt.put("generation", sessionId)
                        .put("owner", nativePlaybackOwnerId).toString())
                    for (i in 0 until rows.length()) RuntimeDiagnosticLog.durableRecord("native_audio_turn",
                        rows.getJSONObject(i).put("generation", sessionId).put("owner", nativePlaybackOwnerId).toString())
                }
                releaseTrackOnce()
            }
        }
        // Also owns cleanup when a lazy job is cancelled before its body ever starts.
        playbackJob.invokeOnCompletion { subscription.close(); releaseTrackOnce() }
        try {
            synchronized(translationResourceLock) {
                ensureTranslationSessionCurrent(sessionId)
                previewAudioTrack = track
                previewPlaybackChannelId = channelId
                previewPlaybackSessionId = sessionId
                previewPlaybackBoundary = playbackBoundary
                previewSubscription = subscription
                previewPlaybackJob = playbackJob
                playbackJob.start()
            }
        } catch (error: Throwable) {
            playbackJob.cancel()
            subscription.close()
            throw error
        }
    }

    private fun createMonitorAudioTrack(sampleRateHz: Int, isolateNativeTurns: Boolean = false): AudioTrack =
        createLocalMonitorAudioTrack(sampleRateHz, isolateNativeTurns)

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

        val nativeMonitor = descriptor.sampleRateHz == 24_000 && app.translationApiSettings.state.value.usesNativeLiveAudio
        val nativeMonitorSessionId = if (nativeMonitor) translationSessionCoordinator.currentSessionId() else null
        if (nativeMonitor) check(translationSessionCoordinator.isSessionActive()) { "직접 음성 연결이 종료됐습니다. 다시 시작한 뒤 기기 재생을 선택하세요." }
        val track = createMonitorAudioTrack(descriptor.sampleRateHz, nativeMonitor)
        track.setVolume(state.localMonitor.volume)
        if (!track.setPreferredDevice(outputDevice)) {
            track.release()
            error("Android가 선택한 출력 장치를 로컬 모니터에 지정하지 못했습니다.")
        }
        val playbackBoundary = if (nativeMonitor) NativeAudioPlaybackBoundary(localMonitorPlaybackEpoch.get()) else null
        val subscription = try {
            session.subscribeLocalMonitor(channelId, preserveNativeAudio = nativeMonitor)
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
            val headProgress = NativeAudioHeadProgress()
            val writtenBytes = AtomicLong(0)
            val unwrittenBytes = AtomicLong(0)
            val inFlight = AtomicLong(0)
            val initialEpoch = localMonitorPlaybackEpoch.get()
            val epochChanged = AtomicBoolean(false)
            val progressClosed = AtomicBoolean(false)
            val rejectionNotified = AtomicBoolean(false)
            var primingSamples = 0L
            var progressJob: Job? = null
            fun publishProgress(closed: Boolean = false) {
                if (!nativeMonitor) return
                val head = synchronized(localMonitorLock) {
                    val epoch = localMonitorPlaybackEpoch.get()
                    if (epoch != initialEpoch) epochChanged.set(true)
                    runCatching { track.playbackHeadPosition }.getOrNull()
                        ?.let { headProgress.samplePosition(it, epoch)?.minus(primingSamples)?.coerceAtLeast(0) }
                }
                val snapshot = NativeLocalPlaybackSnapshot(generation, subscription.bufferSnapshot(), writtenBytes.get(),
                    head, unwrittenBytes.get() + inFlight.get(), epochChanged.get(), closed || progressClosed.get(),
                    playbackOwner = nativePlaybackOwnerId)
                synchronized(localMonitorLock) {
                    val previous = localMonitorPlaybackReceipt
                    if (localMonitorGeneration.get() == generation || snapshot.closed && previous?.generation == generation) {
                        if (previous?.generation != generation || !previous.closed || snapshot.closed)
                            localMonitorPlaybackReceipt = snapshot
                    }
                }
                if (snapshot.buffer.rejectedBytes > 0 && rejectionNotified.compareAndSet(false, true)) {
                    app.broadcastRuntime.update { current -> if (localMonitorGeneration.get() != generation) current else
                        current.copy(localMonitor = current.localMonitor.copy(warning =
                            "기기 재생 대기열이 가득 차 일부 음성을 받지 못했습니다. 저장 이력에서 다시 들을 수 있습니다.")) }
                }
                if (closed) RuntimeDiagnosticLog.durableRecord("native_local_monitor_output", snapshot.countsOnly().toString())
            }
            try {
                track.play()
                val primingEpoch = localMonitorPlaybackEpoch.get()
                primingSamples = primeMonitorRoute(track, descriptor.sampleRateHz).toLong()
                synchronized(localMonitorLock) {
                    if (localMonitorPlaybackEpoch.get() == primingEpoch) playbackBoundary?.recordPriming(primingSamples.toInt())
                }
                progressJob = if (nativeMonitor) launch {
                    while (isActive && localMonitorGeneration.get() == generation) { publishProgress(); delay(100) }
                } else null
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

                while (true) {
                    val frame = subscription.receiveNext() ?: break
                    if (!retiredTurnsFor(channelId).allows(frame.utteranceSequence)) {
                        unwrittenBytes.addAndGet(frame.bytes.size.toLong()); publishProgress(); continue
                    }
                    inFlight.set(frame.bytes.size.toLong())
                    val frameEpoch = synchronized(localMonitorLock) {
                        localMonitorPlaybackEpoch.get()
                    }
                    if (localMonitorGeneration.get() != generation) return@launch
                    if (localMonitorPaused.get()) {
                        unwrittenBytes.addAndGet(inFlight.getAndSet(0)); publishProgress(); continue
                    }
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
                        localMonitorPlaybackEpoch.get() == frameEpoch && !localMonitorPaused.get() &&
                        (nativeMonitorSessionId == null || isTranslationSessionCurrent(nativeMonitorSessionId)) &&
                        retiredTurnsFor(channelId).allows(frame.utteranceSequence)
                    val complete = writeLocalMonitorPcm(
                        frame.bytes,
                        isCurrent = ::isCurrentFrame,
                        writeNonBlocking = { bytes, offset, count ->
                            synchronized(localMonitorLock) {
                                if (!isCurrentFrame()) 0 else
                                    retiredTurnsFor(channelId).writeIfAllowed(frame.utteranceSequence) {
                                        if (playbackBoundary == null) track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                                        else playbackBoundary.write(frame.utteranceSequence, frameEpoch, track.playbackHeadPosition) {
                                            track.write(bytes, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                                        }
                                    }.also { n ->
                                        if (n > 0 && n % 2 == 0 && n <= count) {
                                            writtenBytes.addAndGet(n.toLong()); inFlight.addAndGet(-n.toLong())
                                            if (nativeMonitor) headProgress.accepted(bytes, offset, n, frameEpoch)
                                        }
                                    }
                            }
                        },
                    )
                    if (!complete) {
                        unwrittenBytes.addAndGet(inFlight.getAndSet(0)); publishProgress(); continue
                    }
                    inFlight.set(0); publishProgress()
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
                progressClosed.set(true)
                progressJob?.cancel()
                subscription.close()
                publishProgress(closed = true)
                synchronized(localMonitorLock) {
                    if (localMonitorJob === job) {
                        localMonitorJob = null
                        localMonitorSubscription = null
                        localMonitorAudioTrack = null
                        localMonitorNativeSessionId = null
                        localMonitorPlaybackBoundary = null
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
                if (nativeMonitorSessionId != null) ensureTranslationSessionCurrent(nativeMonitorSessionId)
                localMonitorJob = job
                localMonitorSubscription = subscription
                localMonitorAudioTrack = track
                localMonitorNativeSessionId = nativeMonitorSessionId
                localMonitorPlaybackBoundary = playbackBoundary
                job.start()
            }
        } catch (error: Throwable) {
            job.cancel()
            subscription.close()
            releaseMonitorAudioTrack(track)
            throw error
        }
    }

    private fun primeMonitorRoute(track: AudioTrack, sampleRateHz: Int): Int {
        val primingFrames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            track.startThresholdInFrames
        } else {
            track.bufferCapacityInFrames
        }.coerceAtLeast(sampleRateHz / 50)
        val silence = ByteArray(primingFrames * 2)
        val written = track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
        check(written == silence.size) { "시스템 미디어 출력 경로를 열지 못했습니다." }
        return written / 2
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
                    warning = listOfNotNull(warning, localMonitorPlaybackReceipt?.takeIf {
                        it.generation == generation && it.buffer.rejectedBytes > 0
                    }?.let { "기기 재생 대기열에서 일부 음성을 받지 못했습니다. 저장 이력에서 다시 들을 수 있습니다." })
                        .joinToString(" · ").takeIf { it.isNotEmpty() },
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
            localMonitorPlaybackBoundary?.resetAfterFlush(localMonitorPlaybackEpoch.get())
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

    private fun stopLocalMonitor(updateRuntime: Boolean = true, expectedNativeSessionId: Long? = null) {
        var stoppedGeneration = 0L
        val resources = synchronized(localMonitorLock) {
            if (expectedNativeSessionId != null && localMonitorNativeSessionId != expectedNativeSessionId) return
            stoppedGeneration = localMonitorGeneration.incrementAndGet()
            localMonitorPaused.set(false)
            Triple(localMonitorJob, localMonitorSubscription, localMonitorAudioTrack).also {
                localMonitorJob = null
                localMonitorSubscription = null
                localMonitorAudioTrack = null
                localMonitorNativeSessionId = null
                localMonitorPlaybackBoundary = null
            }
        }
        resources.first?.cancel()
        resources.second?.close()
        resources.third?.let(::stopMonitorAudioTrack)
        if (updateRuntime) {
            app.broadcastRuntime.update { current ->
                if (localMonitorGeneration.get() != stoppedGeneration) current else
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
                recognitionErrorMessage = null,
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
            // The playback job releases only after its last write has returned.
            stopMonitorAudioTrack(track)
        }
        previewAudioTrack = null
        previewPlaybackBoundary = null
        previewPlaybackChannelId = null; previewPlaybackSessionId = null
        translationHealthJob?.cancel()
        translationHealthJob = null
        translationSupportPreparationJob?.cancel()
        translationSupportPreparationJob = null
        selectiveRefinementRecovery?.close()
        selectiveRefinementRecovery = null
        recognitionFrames?.cancel()
        recognitionFrames = null
        nativeRelayLifecycle?.close(); nativeRelayLifecycle = null
        nativeRelayConnections = emptyMap()
        nativeLearningSessions.forEach { it.close() }; nativeLearningSessions = emptyList()
        geminiLiveSessions.forEach { it.close() }; geminiLiveSessions = emptyList()
        nativeAudioRetiredByChannel = emptyMap()
        nativeWebTimingLease?.close(); nativeWebTimingLease = null; nativeGeminiTimings = emptyMap()
        geminiLivePreview?.close(); geminiLivePreview = null
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
        runMode: BroadcastRunMode,
        mode: OperatorAccessMode,
        pin: CharArray?,
        speakerPin: CharArray?,
        translationLanguages: List<String>,
        sourceLanguageTag: String,
        useGemma: Boolean,
        generation: Long,
        selectiveTranslationRefinement: Boolean = false,
    ) {
        val translationLanguageNames = if (app.broadcastRuntime.state.value.isInterpreterRelay)
            NATIVE_RELAY_LANGUAGE_NAMES else TRANSLATION_LANGUAGES
        val network = if (runMode == BroadcastRunMode.NETWORK) {
            LocalNetworkAddressResolver.resolve()
                ?: error("핫스팟 또는 사설 Wi-Fi 주소가 없습니다. '이 기기에서 사용'을 선택하면 네트워크 없이 사용할 수 있습니다.")
        } else null
        val access = when (if (runMode == BroadcastRunMode.STANDALONE) OperatorAccessMode.OPEN else mode) {
            OperatorAccessMode.QR_TOKEN -> BroadcastAccess.QrToken
            OperatorAccessMode.OPEN -> BroadcastAccess.Open
            OperatorAccessMode.PIN -> BroadcastAccess.Pin.from(
                pin ?: error("PIN을 입력하세요."),
            )
        }
        val speakerAccess = if (runMode == BroadcastRunMode.STANDALONE) SpeakerAccess.Open else
            speakerPin?.let { SpeakerAccess.Pin.from(it) } ?: SpeakerAccess.Open

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
                    displayName = requireNotNull(translationLanguageNames[languageTag]),
                    languageTag = languageTag,
                    sampleRateHz = MoonshineSpeechSynthesisProvider.OUTPUT_SAMPLE_RATE_HZ,
                )
        }
        // Configure once, before binding the server. The same immutable session handle is handed
        // to the web server, direct-audio producer, translation workers and test-tone producer.
        // A later broadcast generation therefore cannot receive stale same-ID PCM or listeners.
        val streamSession = app.audioStreams.configure(channelDescriptors)
        val recordingId = synchronized(broadcastResourceLock) {
            if (broadcastGeneration.get() != generation) throw CancellationException("Recording start superseded")
            app.recordings.startPart(streamSession, app.broadcastRuntime.state.value.broadcastTitle)
        }
        val captionJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            var previous: List<TranslationTranscriptLine>? = null
            app.broadcastRuntime.state.collect { snapshot ->
                if (snapshot.transcripts != previous) {
                    app.recordings.capture(streamSession.generation, snapshot.transcripts)
                    previous = snapshot.transcripts
                }
            }
        }
        synchronized(broadcastResourceLock) {
            if (broadcastGeneration.get() != generation) { captionJob.cancel(); throw CancellationException("Caption recording superseded") }
            recordingCaptionJob = captionJob
        }
        captionJob.start()
        val publicationCoordinator = ChannelAudioPublicationCoordinator(
            channelDescriptors.map(AudioChannelDescriptor::id),
        )
        val transcriptPublication = BroadcastTranscriptPublication(
            archiveSessionId = archiveSessionId,
            archiveSnapshot = { app.transcriptArchive.snapshot.value },
            runtimeSnapshot = { app.broadcastRuntime.state.value },
        )
        if (app.broadcastRuntime.state.value.isInterpreterRelay) relayArchiveSessionId = archiveSessionId
        val server = try {
            if (runMode == BroadcastRunMode.STANDALONE) null else GuideCastLocalServer(
                context = this,
                streams = app.audioStreams,
                bindAllInterfacesForDebug = BuildConfig.DEBUG,
                isLiveAudioBroadcastEnabled = { app.broadcastRuntime.state.value.phase == BroadcastPhase.LIVE },
                broadcastStatusProvider = ::listenerBroadcastStatus,
                isSpeakerInputReady = {
                    val state = app.broadcastRuntime.state.value
                    app.audioInputRepository.selectedDevice.value?.kind == AudioInputKind.WEB_SPEAKER &&
                        state.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE) &&
                        !streamingInputSuspended && WebAudioInputBridge.subscriptionCount.value > 0
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
                    if (app.audioInputRepository.selectedDevice.value?.kind == AudioInputKind.WEB_SPEAKER &&
                        app.broadcastRuntime.state.value.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE) && !streamingInputSuspended)
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
                replayProvider = { app.recordings.audio.snapshot(recordingId) },
                replayCaptionSnapshotProvider = {
                    val committedLength = app.recordings.committedCaptionLength(recordingId)
                    ReplayCaptionSnapshot(committedLength) { part, sequence ->
                    val after = if (part != null && sequence != null) part to sequence else null
                    recordedReplayCaptionJson(app.recordings.captions(recordingId, after, committedLength = committedLength),
                        allowNativeGroups = after == null)
                    }
                },
            ).start(
                bindAddress = requireNotNull(network).address,
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
                streamingTranslationRequest = if (app.broadcastRuntime.state.value.isInterpreterRelay) null else
                    StreamingTranslationRequest(translationLanguages.toList(), sourceLanguageTag, useGemma,
                        selectiveTranslationRefinement, archiveSessionId, generation)
                runningServer = server
                broadcastStreamSession = streamSession
                broadcastAudioPublicationCoordinator = publicationCoordinator
                acquireWakeLock()
                app.broadcastRuntime.update { current ->
                    current.copy(
                        phase = BroadcastPhase.LIVE,
                        recordingId = recordingId,
                        listenerUrl = server?.listenerUrl,
                        speakerUrl = server?.speakerUrl,
                        caSha256Fingerprint = server?.caSha256Fingerprint,
                        caFingerprintWarning = server?.caFingerprintWarning,
                        listenerCount = 0,
                        listenerDroppedFrames = 0,
                        webSocketDeliveredFrameCount = 0,
                        translationChannels = translationLanguages.mapIndexed { index, languageTag ->
                            val channelId = languageTag.lowercase(Locale.ROOT)
                            BroadcastChannelSnapshot(
                                channelId = channelId,
                                languageTag = languageTag,
                                displayName = requireNotNull(translationLanguageNames[languageTag]),
                                listenerUrl = server?.listenerUrlFor(channelId),
                                translationProvider = when {
                                    app.broadcastRuntime.state.value.isInterpreterRelay -> if (relayMicrophoneRequested) "Live API 연결 중" else "마이크 꺼짐"
                                    useGemmaForPriority && GemmaTranslationProvider.supportsTranslation(sourceLanguageTag, languageTag) -> "공유 Gemma → ML Kit"
                                    else -> "ML Kit"
                                },
                                synthesisProvider = if (app.broadcastRuntime.state.value.isInterpreterRelay) { "API 음성 · 24 kHz" }
                                else if (languageTag in MOONSHINE_TTS_LANGUAGES) {
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
                                displayName = translationLanguageNames::getValue,
                            )
                            "원음 병행 · $providerLabel · " + translationLanguages.joinToString {
                                requireNotNull(translationLanguageNames[it])
                            }
                        },
                        translationWarning = if (translationLanguages.isEmpty()) {
                            null
                        } else {
                            if (app.broadcastRuntime.state.value.isInterpreterRelay) {
                                if (relayMicrophoneRequested) "Live API 연결 중 · 준비가 완료되면 마이크 입력을 시작합니다."
                                else "방송 준비됨 · 마이크 켜기를 누르면 통역을 시작합니다."
                            } else if (streamingInputSuspended) {
                                "입력 꺼짐 · 입력을 다시 켜면 통역을 준비합니다. 방송 주소와 이력은 유지됩니다."
                            } else if (runMode == BroadcastRunMode.STANDALONE) {
                                "통역 엔진을 준비 중입니다. 이 기기 안에서만 처리하며 네트워크 방송은 열지 않습니다."
                            } else "통역 엔진을 준비 중입니다. 방송 서버는 운영자 제어에 따라 이미 열렸습니다."
                        },
                        errorMessage = null,
                    )
                }
                updateNotification()

                if (translationLanguages.isNotEmpty() &&
                    (if (app.broadcastRuntime.state.value.isInterpreterRelay) relayMicrophoneRequested else !streamingInputSuspended)) {
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
                        val recordingHealth = app.recordings.audio.health(recordingId)
                        val recordingWarning = when {
                            recordingHealth?.failure != null -> "녹음 저장 오류 · 실시간 방송은 별도 경로로 계속됩니다. 방송 이력에서 확인하세요."
                            (recordingHealth?.droppedFrames ?: 0) > 0 -> "녹음 공백 ${recordingHealth?.droppedFrames}회 · 실시간 방송과 별도로 표시합니다."
                            else -> null
                        }
                        app.broadcastRuntime.update { current ->
                            if (broadcastGeneration.get() == generation && current.recordingWarning != recordingWarning) current.copy(recordingWarning = recordingWarning) else current
                        }
                    }
                }
                listenerJob = countJob
            }
        } catch (error: Throwable) {
            // Session invalidation must happen even if Ktor throws while releasing its socket.
            // The original startup error remains the primary failure reported to the operator.
            streamSession.close()
            server?.let(::closeServerHandle)?.let { stopError ->
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
                    if (current.isInterpreterRelay && (current.relayPhase == InterpreterRelayPhase.FAILED ||
                        nativeRelayLifecycle?.let { it.sessionId == sessionId && !it.isClosed && it.readyTargets().isNotEmpty() } != true))
                        return@update current
                    current.copy(
                        channelSummary = preparation.providerLabel + " · " +
                            translationLanguages.joinToString {
                                if (current.isInterpreterRelay) nativeRelayLanguageName(it) else requireNotNull(TRANSLATION_LANGUAGES[it])
                            },
                        translationWarning = preparation.warning,
                        relayPhase = if (current.isInterpreterRelay) InterpreterRelayPhase.READY else current.relayPhase,
                        translationChannels = if (current.isInterpreterRelay) current.translationChannels.map { channel ->
                            val ready = channel.languageTag in nativeRelayLifecycle?.readyTargets().orEmpty()
                            channel.copy(translationProvider = preparation.providerLabel, synthesisProvider = "API 음성 · 24 kHz",
                                translationState = if (ready) BroadcastChannelWorkerState.ACTIVE else BroadcastChannelWorkerState.DEGRADED,
                                synthesisState = if (ready) BroadcastChannelWorkerState.ACTIVE else BroadcastChannelWorkerState.DEGRADED)
                        } else current.translationChannels,
                    )
                }
                if (app.broadcastRuntime.state.value.isInterpreterRelay) {
                    Handler(Looper.getMainLooper()).post {
                        if (relayMicrophoneRequested && broadcastGeneration.get() == broadcastSession && isTranslationSessionCurrent(sessionId) &&
                            app.broadcastRuntime.state.value.relayPhase in setOf(InterpreterRelayPhase.READY, InterpreterRelayPhase.RECEIVING) &&
                            app.translationApiSettings.state.value.let { app.translationApiSettings.authorized(it) && it.allowLiveAudio }) {
                            if (app.interpreterRelaySettings.state.value.localPlayback) {
                                val monitorTarget = app.interpreterRelaySettings.state.value.target
                                if (monitorTarget in nativeRelayLifecycle?.readyTargets().orEmpty()) runCatching {
                                    startPreviewPlayback(monitorTarget, sessionId, streamSession)
                                }.onFailure { app.broadcastRuntime.update { state -> state.copy(
                                    translationWarning = "기기 재생을 시작하지 못했습니다. 출력 장치를 확인하세요.") } }
                                else app.broadcastRuntime.update { state -> state.copy(translationWarning =
                                    "$monitorTarget 기기 청취 연결이 준비되지 않았습니다. 준비된 다른 언어의 LAN 중계는 계속됩니다.") }
                            }
                            startInputCapture(Intent())
                        }
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        val pending = pendingStreamingInput
                        if (pending != null && isTranslationSessionCurrent(sessionId) &&
                            pending.request.inputGeneration == inputGeneration.get() &&
                            pending.request.broadcastGeneration == broadcastGeneration.get() && !streamingInputSuspended) {
                            if (pending.request.matches(inputGeneration.get(), broadcastGeneration.get(), true,
                                    app.broadcastRuntime.state.value, app.audioInputRepository.selectedDevice.value)) {
                                pendingStreamingInput = null
                                runCatching { startInputCapture(pending.intent) }
                                    .onFailure { failInput(it.message ?: "입력을 다시 켜세요.") }
                            } else if (app.broadcastRuntime.state.value.inputPhase == InputPhase.STARTING) {
                                failInput("준비 중 입력 장치가 변경됐습니다. 사용할 입력을 확인하고 다시 켜세요.")
                            }
                        }
                    }
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
                        nativeRelayLifecycle?.close()
        nativeRelayConnections = emptyMap()
        geminiLiveSessions.forEach { it.close() }; geminiLiveSessions = emptyList()
                        geminiLivePreview?.close(); geminiLivePreview = null
                        translationPipeline?.close()
                        translationPipeline = null
                        app.translationDiagnostics.end()
                        app.broadcastRuntime.update { current ->
                            current.copy(
                                relayPhase = if (current.isInterpreterRelay) InterpreterRelayPhase.FAILED else current.relayPhase,
                                inputPhase = if (current.isInterpreterRelay || pendingStreamingInput != null) InputPhase.FAILED else current.inputPhase,
                                inputErrorMessage = if (pendingStreamingInput != null) "통역 준비에 실패했습니다. 입력을 다시 켜세요." else current.inputErrorMessage,
                                translationWarning = "통역 음원 준비 안 됨 · " +
                                    (error.message ?: error.javaClass.simpleName) +
                                    " · 방송 시작/중지는 운영자가 결정합니다.",
                            )
                        }
                    }
                }
                val failedPending = pendingStreamingInput
                if (failedPending != null) Handler(Looper.getMainLooper()).post {
                    if (pendingStreamingInput === failedPending && isTranslationSessionCurrent(sessionId) &&
                        failedPending.request.inputGeneration == inputGeneration.get() &&
                        failedPending.request.broadcastGeneration == broadcastGeneration.get()) {
                        failInput(error.message ?: "통역 준비에 실패했습니다. 입력을 다시 켜세요.")
                    }
                }
            } finally {
                if (translationPreparationJob === job) translationPreparationJob = null
            }
        }
        return job
    }

    private fun stopGeminiLiveForPause() {
        if (geminiLiveSessions.isEmpty()) return
        nativeRelayLifecycle?.close()
        nativeRelayConnections = emptyMap()
        geminiLiveSessions.forEach { it.close() }; geminiLiveSessions = emptyList()
        app.broadcastRuntime.update { it.copy(translationWarning = "Gemini Live 연결 중지됨 · 이전 음성을 재전송하지 않습니다. 통역을 다시 시작하세요.") }
    }

    private fun pauseBroadcast() {
        if (app.broadcastRuntime.state.value.phase != BroadcastPhase.LIVE) return
        if (app.broadcastRuntime.state.value.isInterpreterRelay) {
            app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.PAUSED) }
        } else {
            stopGeminiLiveForPause()
            app.recordings.activeId?.let { app.recordings.audio.markPaused(it, true) }
            app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.PAUSED) }
        }
        updateNotification()
    }

    private fun resumeBroadcast() {
        if (app.broadcastRuntime.state.value.phase != BroadcastPhase.PAUSED) return
        if (app.broadcastRuntime.state.value.isInterpreterRelay) {
            app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.LIVE) }
        } else {
            app.recordings.activeId?.let { app.recordings.audio.markPaused(it, false) }
            app.broadcastRuntime.update { it.copy(phase = BroadcastPhase.LIVE) }
        }
        updateNotification()
    }

    private fun stopBroadcast() {
        app.broadcastRuntime.invalidateInputRequest()
        if (app.broadcastRuntime.state.value.isInterpreterRelay) stopInput()
        releaseBroadcastResources()
        val socketCleanupPending = synchronized(broadcastResourceLock) { runningServer != null }
        app.recordings.finish()
        relayArchiveSessionId = null
        app.broadcastRuntime.update { current ->
            current.copy(
                phase = if (socketCleanupPending) BroadcastPhase.FAILED else BroadcastPhase.IDLE,
                relayPhase = InterpreterRelayPhase.IDLE,
                isInterpreterRelay = false,
                recordingId = null,
                broadcastTitle = "",
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
                recognitionErrorMessage = null,
                testToneActive = false,
                errorMessage = if (socketCleanupPending)
                    "웹 서버를 종료하지 못했습니다. 방송 중지를 다시 누르세요. 종료 전에는 다른 방송을 시작할 수 없습니다."
                else null,
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

    private suspend fun prepareGeminiLive(targets: List<String>, source: String, sessionId: Long,
        archiveSessionId: Long?, stream: StreamSession?, publication: ChannelAudioPublicationCoordinator?): TranslationPreparationResult {
        val options = app.translationApiSettings.state.value
        val lifecycle = NativeRelayChannelLifecycle(sessionId, targets)
        check(app.translationApiSettings.authorized(options) && options.allowLiveAudio) { "Gemini Live 키·온라인 허용·이번 실행의 원음 전송 동의를 확인하세요." }
        val referencesByTarget = targets.associateWith { prepareRelayReferences(options, source, it) }
        val monitorTarget = app.interpreterRelaySettings.state.value.target.takeIf { it in targets } ?: targets.first()
        val session = stream ?: AudioStreamRegistry().configure(targets.map { tag ->
            AudioChannelDescriptor(tag.lowercase(Locale.ROOT), nativeRelayLanguageName(tag), tag, 24_000)
        }).also { geminiLivePreview = it }
        val timings = targets.associateWith { NativeLiveTiming(sessionId) }
        val retirements = targets.associate { it.lowercase(Locale.ROOT) to NativeAudioRetiredTurns() }
        nativeAudioRetiredByChannel = retirements
        nativeWebTimingLease?.close()
        nativeWebTimingLease = session.observeWebSocketDelivery { channel, frame ->
            if (frame.nativeAudioSessionId == sessionId) targets.firstOrNull { it.lowercase(Locale.ROOT) == channel.id }
                ?.let { timings.getValue(it).webSocketSent(frame.bytes.size) }
        }
        val connections = targets.distinct().mapIndexed { index, tag ->
            val timing = timings.getValue(tag)
            val retiredTurns = retirements.getValue(tag.lowercase(Locale.ROOT))
            val segments = GeminiLiveSegments(tag, source, relayNativeSequenceBase(sessionId, index), sessionId)
            GeminiLiveSession(serviceScope, options, tag, app.translationApiSettings, app.geminiLiveMonitor,
                allowed = { isTranslationSessionCurrent(sessionId) && session.isActive() && lifecycle.accepts(tag) },
                onEvent = { event ->
                    ensureTranslationSessionCurrent(sessionId)
                    val now = SystemClock.elapsedRealtimeNanos()
                    val segment = segments.accept(event, now)
                    if (event.interrupted) {
                        cancelNativeAudioOutputTurn(segment?.sequence, retiredTurns,
                            { sequence -> session.discardQueuedAudio(tag.lowercase(Locale.ROOT), sequence) },
                            { sequence -> flushNativeAudioPlayback(tag.lowercase(Locale.ROOT), sequence, sessionId) },
                            isCurrent = { isTranslationSessionCurrent(sessionId) })
                    }
                    if (segment != null) {
                        app.broadcastRuntime.update { current ->
                            if (!isTranslationSessionCurrent(sessionId) || !lifecycle.accepts(tag)) current else current.copy(
                                transcripts = (current.transcripts.filterNot { it.sequence == segment.sequence } + segment).takeLast(MAX_TRANSCRIPT_LINES))
                        }
                        // Do not archive an invented source for untranslated/late-transcription audio.
                        if (segment.isFinal && segment.sourceText.isNotBlank()) persistTranscriptIfAvailable(archiveSessionId, segment.sequence)
                    }
                    event.audio.forEach { bytes ->
                        ensureTranslationSessionCurrent(sessionId)
                        val publicationStarted = System.nanoTime()
                        val current = app.broadcastRuntime.state.value
                        if (current.isInterpreterRelay) app.broadcastRuntime.update { state ->
                            if (isTranslationSessionCurrent(sessionId) && lifecycle.accepts(tag) && state.isInterpreterRelay &&
                                state.relayPhase != InterpreterRelayPhase.FAILED) state.copy(relayPhase = InterpreterRelayPhase.RECEIVING) else state
                        }
                        var result: app.guidecast.core.stream.StreamPublishResult? = null
                        if (relayInputProcessingEnabled(current)) {
                            val lease = publication?.tryAcquireChannel(tag.lowercase(Locale.ROOT))
                            if (publication == null || lease != null) try {
                                ensureTranslationSessionCurrent(sessionId)
                                if (lifecycle.accepts(tag) && app.translationApiSettings.authorized(options)) result = session.tryPublish(tag.lowercase(Locale.ROOT),
                                    PcmAudioFrame(bytes, now, segment?.sequence, nativeAudioSessionId = sessionId,
                                        nativeTimingTurn = event.timingTurn))
                            } finally { lease?.close() }
                        }
                        recordLivePublication(result, bytes.size) { reason, count ->
                            app.geminiLiveMonitor.loss(tag, reason, count)
                            app.broadcastRuntime.update { it.copy(translationWarning = "Gemini Live $tag 음성 송출 누락 · API 설정의 언어별 누락 계측을 확인하세요.") }
                        }
                        if (result?.accepted == true) {
                            timing.published(bytes.size)
                            timing.stages.published(event.timingTurn, bytes.size, publicationStarted)
                        }
                    }
                    if (event.finished && tag == monitorTarget) NativeLocalPlaybackProgress.providerCompleted(sessionId,
                        timing.snapshot().getLong("provider_audio_bytes"), nativePlaybackOwnerId)
                }, onFailure = { warning -> nativeRelayChannelFailed(lifecycle, tag, warning) },
                onStatus = { notice -> app.broadcastRuntime.update { current ->
                    if (isTranslationSessionCurrent(sessionId) && lifecycle.accepts(tag)) current.copy(translationWarning = notice) else current
                } },
                onSourceDelayStatus = { previous, next -> app.broadcastRuntime.update { current ->
                    if (isTranslationSessionCurrent(sessionId) && lifecycle.accepts(tag) &&
                        current.inputPhase == InputPhase.ACTIVE && !current.inputStopping &&
                        current.relayPhase != InterpreterRelayPhase.FAILED)
                        current.copy(translationWarning = geminiSourceDelayWarning(current.translationWarning, previous, next))
                    else current
                } },
                onDiagnostic = { action -> RuntimeDiagnosticLog.record("service_flow", serviceFlowSnapshot(options,
                    diagnosticSessionId, inputGeneration.get(), action)) },
                onEnded = { reason ->
                    if (tag == monitorTarget) app.nativeLearningMonitor.end(sessionId)
                    handleNativeAudioSessionEnd(reason,
                    terminalize = { app.broadcastRuntime.update { current -> current.copy(transcripts =
                        terminalizeNativeAudioTranscripts(current.transcripts, sessionId, reason, tag)) } },
                    releaseRevokedSession = { lifecycle.revokeConsent(); releaseNativeAudioAfterConsent(sessionId, tag.lowercase(Locale.ROOT), session) })
                    if (!lifecycle.isClosed && reason != NativeAudioEndReason.CONSENT_REVOKED)
                        nativeRelayChannelFailed(lifecycle, tag, "$tag 연결 종료 · ${reason.label}")
                }, timing = timing, references = referencesByTarget.getValue(tag), sourceLanguageTag = source)
        }
        synchronized(translationResourceLock) {
            ensureTranslationSessionCurrent(sessionId)
            nativeRelayLifecycle?.close()
            geminiLiveSessions.forEach { it.close() }
            nativeLearningSessions.forEach { it.close() }
            nativeLearningSessions = listOfNotNull(app.createNativeLearningSession(sessionId, options, source, monitorTarget,
                nativeLearningInputBoundaryKnown(inputJob != null, app.broadcastRuntime.state.value.inputPhase,
                    app.audioCaptureEngine.diagnostics.value.state)) {
                isTranslationSessionCurrent(sessionId) && lifecycle.accepts(monitorTarget)
            })
            nativeRelayLifecycle = lifecycle
            nativeRelayConnections = targets.zip(connections).toMap()
            geminiLiveSessions = connections
            nativeGeminiTimings = timings.mapKeys { it.key.lowercase(Locale.ROOT) }
            connections.forEach { it.start() }
        }
        try {
            lifecycle.awaitReadyChannels(awaitReady = { tag -> connections[targets.indexOf(tag)].awaitReady() },
                onFailure = { tag, _ -> nativeRelayChannelFailed(lifecycle, tag, "$tag 연결 준비 실패 · 다른 언어 중계는 계속됩니다.") })
            ensureTranslationSessionCurrent(sessionId)
        } catch (failure: Throwable) { lifecycle.close(); connections.forEach { it.close() }; throw failure }
        return TranslationPreparationResult("Gemini Live · ${options.model}",
            "${lifecycle.readyTargets().size}/${targets.size}개 언어 연결 준비됨 · 언어별 음성·자막·청취는 별도 확인합니다.")
    }

    private suspend fun prepareOpenAiAudio(targets: List<String>, source: String, sessionId: Long,
        archiveSessionId: Long?, stream: StreamSession?, publication: ChannelAudioPublicationCoordinator?): TranslationPreparationResult {
        nativeWebTimingLease?.close(); nativeWebTimingLease = null; nativeGeminiTimings = emptyMap()
        val options = app.translationApiSettings.state.value
        val lifecycle = NativeRelayChannelLifecycle(sessionId, targets)
        val monitorTarget = app.interpreterRelaySettings.state.value.target.takeIf { it in targets } ?: targets.first()
        check(options.usesNativeLiveAudio && app.translationApiSettings.authorized(options) && options.allowLiveAudio) {
            "OpenAI 음성 전송 동의와 API 키를 확인하세요."
        }
        val referencesByTarget = targets.associateWith { prepareRelayReferences(options, source, it) }
        val session = stream ?: AudioStreamRegistry().configure(targets.map { tag ->
            AudioChannelDescriptor(tag.lowercase(Locale.ROOT), nativeRelayLanguageName(tag), tag, 24_000)
        }).also { geminiLivePreview = it }
        val retirements = targets.associate { it.lowercase(Locale.ROOT) to NativeAudioRetiredTurns() }
        nativeAudioRetiredByChannel = retirements
        val learningSessions = mutableListOf<NativeLearningSession>()
        val connections = targets.mapIndexed { index, tag ->
        val channelId = tag.lowercase(Locale.ROOT)
        val references = referencesByTarget.getValue(tag)
        check(session.descriptor(channelId)?.sampleRateHz == 24_000) { "Native audio channel format mismatch" }
        val sequenceBase = relayNativeSequenceBase(sessionId, index)
        val segments = OpenAiAudioSegments(tag, source, sessionId, sequenceBase = sequenceBase)
        val sessionEnded = java.util.concurrent.atomic.AtomicReference<NativeAudioEndReason?>(null)
        val learning = if (app.broadcastRuntime.state.value.isInterpreterRelay && tag == monitorTarget)
            app.createNativeLearningSession(sessionId, options, source, tag,
                nativeLearningInputBoundaryKnown(inputJob != null, app.broadcastRuntime.state.value.inputPhase,
                    app.audioCaptureEngine.diagnostics.value.state)) {
                isTranslationSessionCurrent(sessionId) && lifecycle.accepts(tag) && sessionEnded.get() == null
            } else null
        learning?.let(learningSessions::add)
        val retiredTurns = retirements.getValue(channelId)
        fun interrupt(sequence: Long? = null) {
            if (!isTranslationSessionCurrent(sessionId)) return
            if (sequence == null) {
                session.discardQueuedAudio(channelId)
                flushNativeAudioPlayback(channelId, null, sessionId)
            } else cancelNativeAudioOutputTurn(sequence, retiredTurns,
                { session.discardQueuedAudio(channelId, it) }, { flushNativeAudioPlayback(channelId, it, sessionId) },
                isCurrent = { isTranslationSessionCurrent(sessionId) })
        }
        val connection = OpenAiAudioSession(serviceScope, options, source, tag, app.translationApiSettings,
            app.geminiLiveMonitor, allowed = { isTranslationSessionCurrent(sessionId) && session.isActive() && lifecycle.accepts(tag) },
            onTranscript = { event ->
                ensureTranslationSessionCurrent(sessionId)
                val row = segments.accept(event, SystemClock.elapsedRealtimeNanos())
                app.broadcastRuntime.update { current -> if (sessionEnded.get() != null || !isTranslationSessionCurrent(sessionId) || !lifecycle.accepts(tag)) current else
                    current.copy(transcripts = (current.transcripts.filterNot { it.sequence == row.sequence } + row)
                        .sortedBy { it.capturedAtElapsedRealtimeNanos }.takeLast(MAX_TRANSCRIPT_LINES)) }
                if (row.isFinal && row.sourceText.isNotBlank()) persistTranscriptIfAvailable(archiveSessionId, row.sequence)
                learning?.accept(event)
            },
            onAudio = { event, bytes ->
                ensureTranslationSessionCurrent(sessionId)
                val current = app.broadcastRuntime.state.value
                if (current.isInterpreterRelay) app.broadcastRuntime.update { state ->
                            if (isTranslationSessionCurrent(sessionId) && lifecycle.accepts(tag) && state.isInterpreterRelay &&
                                state.relayPhase != InterpreterRelayPhase.FAILED) state.copy(relayPhase = InterpreterRelayPhase.RECEIVING) else state
                        }
                var result: app.guidecast.core.stream.StreamPublishResult? = null
                if (relayInputProcessingEnabled(current)) {
                    val lease = publication?.tryAcquireChannel(channelId)
                    if (publication == null || lease != null) try {
                        ensureTranslationSessionCurrent(sessionId)
                        if (lifecycle.accepts(tag) && app.translationApiSettings.authorized(options)) {
                            val row = segments.accept(event, SystemClock.elapsedRealtimeNanos())
                            result = session.tryPublish(channelId, PcmAudioFrame(bytes, SystemClock.elapsedRealtimeNanos(), row.sequence, nativeAudioSessionId = sessionId))
                        }
                    } finally { lease?.close() }
                }
                recordLivePublication(result, bytes.size) { reason, count ->
                    app.geminiLiveMonitor.loss(tag, reason, count)
                    app.broadcastRuntime.update { it.copy(translationWarning = "OpenAI $tag 음성 송출 누락 · 사용량 상세를 확인하세요.") }
                }
            }, onInterrupted = { interrupt() }, onTurnInterrupted = { interrupt(it) },
            onEnded = { reason ->
                sessionEnded.compareAndSet(null, reason)
                learning?.close()
                handleNativeAudioSessionEnd(reason,
                    terminalize = { app.broadcastRuntime.update { current -> current.copy(transcripts =
                        terminalizeNativeAudioTranscripts(current.transcripts, sessionId, requireNotNull(sessionEnded.get()), tag)) } },
                    releaseRevokedSession = { lifecycle.revokeConsent(); releaseNativeAudioAfterConsent(sessionId, channelId, session) })
                if (!lifecycle.isClosed && reason != NativeAudioEndReason.CONSENT_REVOKED)
                    nativeRelayChannelFailed(lifecycle, tag, "$tag 연결 종료 · ${reason.label}")
            },
            onFailure = { warning -> nativeRelayChannelFailed(lifecycle, tag, warning) }, onDiagnostic = { action -> RuntimeDiagnosticLog.record("service_flow",
                serviceFlowSnapshot(options, diagnosticSessionId, inputGeneration.get(), action)) }, references = references, sequenceBase = sequenceBase)
        connection
        }
        synchronized(translationResourceLock) {
            ensureTranslationSessionCurrent(sessionId)
            nativeRelayLifecycle?.close()
            geminiLiveSessions.forEach { it.close() }
            nativeLearningSessions.forEach { it.close() }
            nativeLearningSessions = learningSessions.toList()
            nativeRelayLifecycle = lifecycle
            nativeRelayConnections = targets.zip(connections).toMap()
            geminiLiveSessions = connections
            connections.forEach { it.start() }
        }
        try {
            lifecycle.awaitReadyChannels(awaitReady = { tag -> connections[targets.indexOf(tag)].awaitReady() },
                onFailure = { tag, _ -> nativeRelayChannelFailed(lifecycle, tag, "$tag 연결 준비 실패 · 다른 언어 중계는 계속됩니다.") })
            ensureTranslationSessionCurrent(sessionId)
        } catch (failure: Throwable) {
            lifecycle.close(); learningSessions.forEach { it.close() }; connections.forEach { it.close() }; throw failure
        }
        return TranslationPreparationResult("OpenAI 직접 음성 · ${options.model}",
            "${lifecycle.readyTargets().size}/${targets.size}개 언어 연결 준비됨 · 언어별 음성·자막·청취는 별도 확인합니다.")
    }

    /** Retire only the failed language; the microphone stops when every route has failed. */
    private fun nativeRelayChannelFailed(lifecycle: NativeRelayChannelLifecycle, tag: String, warning: String) {
        if (!isTranslationSessionCurrent(lifecycle.sessionId) || !lifecycle.fail(tag)) return
        nativeRelayConnections[tag]?.close()
        app.broadcastRuntime.update { state -> if (!isTranslationSessionCurrent(lifecycle.sessionId) || lifecycle.isClosed) state else
            state.copy(translationWarning = warning, translationTestMessage = warning,
            translationChannels = state.translationChannels.map { channel -> if (channel.languageTag == tag)
                channel.copy(translationState = BroadcastChannelWorkerState.DEGRADED, synthesisState = BroadcastChannelWorkerState.DEGRADED,
                    lastTranslationError = warning, lastSynthesisError = warning) else channel },
            relayPhase = if (state.isInterpreterRelay && lifecycle.allFailed()) InterpreterRelayPhase.FAILED else state.relayPhase) }
        Handler(Looper.getMainLooper()).post {
            if (isTranslationSessionCurrent(lifecycle.sessionId) && lifecycle.allFailed() &&
                app.broadcastRuntime.state.value.isInterpreterRelay) {
                stopInput(); releaseTranslationTestResources()
            }
        }
    }

    private fun releaseNativeAudioAfterConsent(sessionId: Long, channelId: String, stream: StreamSession): Boolean {
        var cleanupGeneration = -1L
        val released = releaseNativeAudioConsentOwner(translationSessionCoordinator, sessionId,
            discardQueuedOutput = { stream.discardQueuedAudio(channelId) },
            stopOwnedMonitor = { stopLocalMonitor(expectedNativeSessionId = sessionId) },
            releasePreviewAndProvider = {
                cleanupGeneration = translationSessionCoordinator.currentSessionId()
                doReleaseTranslationTestResources()
                app.broadcastRuntime.update { state -> state.copy(translationTestActive = false,
                    translationWarning = NativeAudioEndReason.CONSENT_REVOKED.label,
                    translationTestMessage = "온라인 동의를 해제해 통역 음성을 중지했습니다.",
                    relayPhase = if (state.isInterpreterRelay) InterpreterRelayPhase.FAILED else state.relayPhase) }
            })
        if (released) Handler(Looper.getMainLooper()).post {
            // Broadcast startup takes these locks in this order before replacing the owner.
            synchronized(broadcastResourceLock) { synchronized(translationResourceLock) {
                if (translationSessionCoordinator.currentSessionId() == cleanupGeneration &&
                    app.broadcastRuntime.state.value.isInterpreterRelay) stopInput()
            } }
        }
        return released
    }

    private fun flushNativeAudioPlayback(channelId: String, sequence: Long?, sessionId: Long) {
        synchronized(translationResourceLock) {
            if (!isTranslationSessionCurrent(sessionId)) return
            previewAudioTrack?.takeIf { previewPlaybackChannelId == channelId && previewPlaybackSessionId == sessionId }?.let { track -> runCatching {
                val boundary = previewPlaybackBoundary
                fun flush() {
                    nativeAudioPlaybackEpoch.incrementAndGet()
                    track.pause(); track.flush(); track.play()
                }
                if (boundary != null) boundary.flushIfMatches(sequence, nativeAudioPlaybackEpoch.get() + 1, flush = ::flush)
                else if (sequence == null) flush()
            } }
        }
        synchronized(localMonitorLock) {
            if (!isTranslationSessionCurrent(sessionId)) return
            if (app.broadcastRuntime.state.value.localMonitor.channelId == channelId) {
                localMonitorAudioTrack?.let { track -> runCatching {
                    val boundary = localMonitorPlaybackBoundary
                    fun flush() {
                        localMonitorPlaybackEpoch.incrementAndGet()
                        track.pause(); track.flush(); if (!localMonitorPaused.get()) track.play()
                    }
                    if (boundary != null) boundary.flushIfMatches(sequence, localMonitorPlaybackEpoch.get() + 1, flush = ::flush)
                    else if (sequence == null) flush()
                } }
            }
        }
    }

    private suspend fun prepareRelayReferences(options: TranslationApiOptions, source: String, target: String): NativeReferenceSnapshot {
        val references = if (options.allowDomainReferences && serviceExperience(options).supportsReferences) {
            try { withTimeout(1_000) { app.domainCorpus.prepareNativeReferences(source, target, options.tone) } }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                app.broadcastRuntime.update { it.copy(translationWarning = "참고 자료를 준비하지 못했습니다. 이번 중계에는 자료를 보내지 않고 음성 통역을 계속합니다.") }
                NativeReferenceSnapshot()
            }
        } else NativeReferenceSnapshot()
        app.broadcastRuntime.update { current -> current.copy(relayReferenceCharacters = references.characters,
            relayReferenceEntries = references.includedEntries, relayAvailableReferenceEntries = references.availableEntries,
            relayContext = RelayContextPresentation(options.model,
                options.domainPrompt.takeIf { serviceExperience(options).supportsDomainInstructions && options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL }.orEmpty(),
                options.interpreterInstructions.takeIf { serviceExperience(options).supportsDomainInstructions }?.length ?: 0,
                options.revision, serviceExperience(options).supportsDomainInstructions)) }
        return references
    }

    private suspend fun prepareTranslationPipeline(
        translationLanguages: List<String>,
        sourceLanguageTag: String,
        requestedGemma: Boolean,
        sessionId: Long,
        archiveSessionId: Long? = null,
        awaitChannelPreparation: Boolean,
        streamSession: StreamSession? = null,
        audioPublicationCoordinator: ChannelAudioPublicationCoordinator? = null,
        selectiveTranslationRefinement: Boolean = false,
    ): TranslationPreparationResult {
        val useGemma = requestedGemma && app.translationApiSettings.state.value.provider == TranslationApiProvider.LOCAL
        val needsLocalTranslation = app.translationApiSettings.state.value.provider == TranslationApiProvider.LOCAL ||
            app.translationApiSettings.state.value.alwaysLearnOnline || app.translationApiSettings.sessionLearning.value
        ensureTranslationSessionCurrent(sessionId)
        if (app.translationApiSettings.state.value.usesNativeLiveAudio) {
            requireNativeRelayLanguageSelection(sourceLanguageTag, translationLanguages)
        } else {
            require(translationLanguages.size in 1..MAX_TRANSLATION_LANGUAGES)
            requireSupportedSourceLanguage(sourceLanguageTag)
            require(translationLanguages.none {
                normalizeSourceLanguage(it) == normalizeSourceLanguage(sourceLanguageTag)
            }) { "원문과 출력 언어는 서로 달라야 합니다." }
        }
        check(!app.translationApiSettings.state.value.usesNativeLiveAudio || app.broadcastRuntime.state.value.isInterpreterRelay) {
            "통번역 스트리밍은 문장 번역 서비스와 기기 TTS를 사용합니다. Live 음성 서비스는 통역 중계를 선택하세요."
        }
        if (app.translationApiSettings.state.value.provider == TranslationApiProvider.GEMINI_LIVE) {
            return prepareGeminiLive(translationLanguages, sourceLanguageTag, sessionId, archiveSessionId, streamSession, audioPublicationCoordinator)
        }
        if (app.translationApiSettings.state.value.usesNativeLiveAudio) {
            return prepareOpenAiAudio(translationLanguages, sourceLanguageTag, sessionId, archiveSessionId, streamSession, audioPublicationCoordinator)
        }
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
        val reconciliationFailures = if (awaitChannelPreparation && needsLocalTranslation) {
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
        val missingFallback = if (needsLocalTranslation) missingMlKitModels(requested) else emptyList()
        val readyFallbackTargets = if (needsLocalTranslation) requested - missingFallback.toSet() else emptySet()
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
                    allowFallbackForPrimaryFailure = { protectedTranslationReviewMessage(it.message) == null },
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
                                    (protectedTranslationReviewMessage(error.message)
                                        ?: error.message ?: error.javaClass.simpleName),
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
                                        gemmaPriorityRouteEnabled.set(false)
                                        gemmaLiveActive.set(false)
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
        val gemmaQueueConfig = FairTranslationQueueConfig(
            maxPendingPerLanguage = 2,
            queueWaitTimeoutMillis = 30_000L,
            inferenceTimeoutMillis = GEMMA_PIPELINE_TRANSLATION_TIMEOUT_MILLIS,
        )
        val fairGemma = gemmaWithFailover?.let { failover ->
            FairQueuedTranslationEngineProvider(
                // Review mode preserves its already-computed draft itself: never execute ML Kit
                // a second time inside the review lane or mistake fallback for successful review.
                delegate = if (selectiveTranslationRefinement) admittedGemmaTranslationProvider else failover,
                parentScope = serviceScope,
                config = gemmaQueueConfig,
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
        val refinementRecovery = if (selectiveTranslationRefinement && gemmaEligible) {
            fun recoveryAllowed(): Boolean = isTranslationSessionCurrent(sessionId) &&
                app.gemmaTranslationProvider.modelManager.status.value.readiness == GemmaModelReadiness.READY &&
                !app.gemmaTranslationProvider.isAutomaticRetryBlocked()
            SelectiveRefinementRecovery(
                scope = serviceScope,
                canRecover = ::recoveryAllowed,
                isPrepared = app.gemmaTranslationProvider::hasActivePreparedWorker,
                cleanupLatestFailure = { app.resetGemmaAfterFailureIf(::recoveryAllowed) },
                recover = {
                    // Cancellation does not enter Gemma's failed-generation ledger. A false
                    // reset result therefore means no reset was needed, not that preparation
                    // succeeded. Only a real warmup can restore the provider's prepared state.
                    ensureTranslationSessionCurrent(sessionId)
                    if (!recoveryAllowed()) throw CancellationException("Gemma recovery is no longer allowed")
                    nativeTranslationGate.initialize(gemmaNativeFirstUseKey(gemmaWarmupTarget)) {
                        ensureTranslationSessionCurrent(sessionId)
                        if (!recoveryAllowed()) throw CancellationException("Gemma recovery is no longer allowed")
                        // Retain the existing process lease, worker-generation barrier and native
                        // terminal acknowledgement. Never force-ready or release a cancelled call.
                        app.gemmaTranslationProvider.warmup(
                            targetLanguageTag = gemmaWarmupTarget,
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
                onResult = { result ->
                    synchronized(translationResourceLock) {
                        if (isTranslationSessionCurrent(sessionId)) {
                            if (result == SelectiveRefinementRecoveryResult.RECOVERED) {
                                gemmaLiveActive.set(true)
                            } else {
                                appendTranslationProviderWarning(
                                    "선택적 보완 엔진 재준비를 마치지 못했습니다. " +
                                        "ML Kit 초안으로 계속합니다. 자동 재준비는 다음 세션에서 다시 시도합니다.",
                                )
                            }
                            RuntimeDiagnosticLog.record("translation_refinement_recovery", "outcome=$result")
                        }
                    }
                },
            ).also { recovery ->
                synchronized(translationResourceLock) {
                    ensureTranslationSessionCurrent(sessionId)
                    selectiveRefinementRecovery?.close()
                    selectiveRefinementRecovery = recovery
                }
            }
        } else null
        val selectiveProvider = SelectiveRefinementTranslationEngineProvider(
            draftProvider = admittedFallbackTranslationProvider,
            // The operator enabled meaning review. Keep its fair queue's wait/inference bounds
            // rather than cancelling all languages under a shared 800 ms wall-clock deadline.
            queuedReviewTimeoutMillis = gemmaQueueConfig.maximumCallDurationMillis,
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
                refinementRecovery?.onDiagnostic(diagnostic.outcome)
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
            if (!needsLocalTranslation) {
                TextTranslationEngine { _, _, _ ->
                    error("온라인 주 모드에는 기기 번역을 자동 실행하지 않습니다.")
                }
            } else if (selectiveTranslationRefinement && target in readyFallbackTargets &&
                target !in fallbackWarmupFailures) {
                selectiveProvider.engineFor(target)
            } else {
                // Missing draft files must not disable a usable prepared Gemma translator.
                preferredTranslationProvider.engineFor(target)
            }
        }
        // No first-utterance disk/index warm-up. A failure remains visible before capture starts.
        app.domainCorpus.prepareActiveIndex()
        val translationProvider = TranslationEngineProvider { targetLanguageTag ->
            val localDomainEngine = DomainCorpusTranslationEngine(baseTranslationProvider.engineFor(targetLanguageTag), app.domainCorpus) {
                if (app.gemmaTranslationProvider.modelManager.selectedVariant ==
                    GemmaModelVariant.E4B_IT) 600
                else DomainCorpusFormat.MAX_HINTS_LENGTH
            }
            val domainEngine = SentenceRefiningTranslationEngine(
                app.translationApiService.engine(localDomainEngine), app.cloudTranslationReviewer,
                allowLocalRefinement = { app.translationApiSettings.state.value.provider == TranslationApiProvider.LOCAL },
            )
            if (targetLanguageTag.equals("zh-TW", ignoreCase = true)) {
                TraditionalChineseTranslatingEngine(domainEngine)
            } else {
                domainEngine
            }
        }
        val input = Channel<PcmAudioFrame>(
            // Keep the service-to-recognizer hand-off short. Larger queues made overload sound
            // like a successful but many-seconds-late simultaneous interpretation.
            capacity = RECOGNITION_HANDOFF_FRAMES,
        )
        app.translationDiagnostics.stage(TranslationRunStage.LISTENING)
        val (recognitionSequences, recognitionRun) = synchronized(translationResourceLock) {
            ensureTranslationSessionCurrent(sessionId)
            val sequences = streamingTranslationRequest?.takeIf {
                streamSession != null && it.broadcastGeneration == broadcastGeneration.get() &&
                    streamSession === broadcastStreamSession
            }?.recognitionSequences ?: StreamingRecognitionSequences()
            sequences to sequences.beginRun(if (streamSession != null)
                app.broadcastRuntime.state.value.transcripts.map { it.sequence } else emptyList())
        }
        val recognizedUtterances = app.speechRecognitionEngine.recognize(
            frames = input.receiveAsFlow(),
            config = SpeechRecognitionConfig(sourceLanguageTag = sourceLanguageTag),
        ).map { utterance ->
            val now = SystemClock.elapsedRealtime()
            if (utterance.isFinal) recognitionFinalCount++ else recognitionPartialCount++
            if (utterance.isFinal || now - lastRecognitionDiagnosticMillis >= 2_000L) {
                lastRecognitionDiagnosticMillis = now
                RuntimeDiagnosticLog.record("recognition_progress", "session_id=$diagnosticSessionId input_generation=${inputGeneration.get()} " +
                    "sequence=${utterance.sequence} final=${utterance.isFinal} partial_count=$recognitionPartialCount final_count=$recognitionFinalCount " +
                    "captured_ns=${utterance.capturedAtElapsedRealtimeNanos} recognized_ns=${utterance.recognizedAtElapsedRealtimeNanos} observed_ms=$now")
            }
            val lab = app.developerLabSettings.state.value
            val enabled = app.uiDisplaySettings.developerInfo.value
            utterance.copy(
                sequence = recognitionSequences.reserve(recognitionRun, utterance.sequence),
                speechExpression = if (enabled && lab.expressiveTtsEnabled)
                    deriveSpeechExpression(utterance.text, lab.translationRegister) else null,
                translationStyle = if (enabled && lab.paraphraseEnabled)
                    TranslationStyle.valueOf(lab.translationRegister.name)
                else app.translationApiSettings.state.value.tone,
            )
        }.onCompletion { cause ->
            // Wake suspended producers when the recognizer exits; a dead consumer must never
            // hold the independently controlled microphone/original-audio producer.
            input.cancel()
            if (cause !is CancellationException && isTranslationSessionCurrent(sessionId)) {
                RuntimeDiagnosticLog.record("recognition_stream_end",
                    "failed=${cause != null}")
                app.broadcastRuntime.update { current ->
                    if (!isTranslationSessionCurrent(sessionId)) current else current.copy(
                        recognitionErrorMessage = "음성인식 스트림이 종료됐습니다. 통역을 중지한 뒤 다시 시작하세요.",
                        translationWarning = "음성인식 스트림이 종료됐습니다. 입력과 원음은 유지됩니다. 통역을 중지한 뒤 다시 시작하세요.",
                        translationTestMessage = if (current.translationTestActive) {
                            "음성인식이 종료됐습니다. 통번역 시험을 다시 시작하세요."
                        } else current.translationTestMessage,
                    )
                }
            }
        }
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
                            translationProvider = if (app.translationApiSettings.state.value.provider != TranslationApiProvider.LOCAL) {
                                app.translationApiSettings.state.value.provider.label + " · " +
                                    (app.translationApiService.states.value[health.targetLanguageTag]?.label ?: "선택됨 · 아직 응답 없음")
                            } else if (selectiveTranslationRefinement &&
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
                            lastDroppedSequence = health.lastDroppedSequence,
                            sourceBacklogDrops = health.sourceBacklogDrops,
                            speechBacklogDrops = health.speechBacklogDrops,
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

    private fun beginTranslationSession(): Long {
        val sessionId = translationSessionCoordinator.beginSession()
        app.broadcastRuntime.update { it.copy(recognitionErrorMessage = null) }
        return sessionId
    }

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
        providerLabel: String? = null,
        gemmaPriorityActive: Boolean = false,
    ) {
        if (!isTranslationSessionCurrent(sessionId)) return
        // Voice/reconciliation updates must preserve the active translation route.
        val currentProviderLabel = providerLabel ?: translationProviderPresentation(
            translationLanguages = translationLanguages,
            gemmaActive = gemmaPriorityActive,
            mlKitReady = true,
            displayName = TRANSLATION_LANGUAGES::getValue,
        ).providerLabel
        app.broadcastRuntime.update { current ->
            if (!isTranslationSessionCurrent(sessionId)) return@update current
            val warning = listOfNotNull(
                translationProviderWarning,
                app.speechSynthesisProvider.fallbackWarning(translationLanguages),
            ).joinToString(" · ").ifEmpty { null }
            if (current.phase == BroadcastPhase.LIVE || current.phase == BroadcastPhase.PAUSED) {
                current.copy(
                    channelSummary = "$currentProviderLabel · " + translationLanguages.joinToString {
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
            RuntimeDiagnosticLog.record("translation_complete", "session_id=$diagnosticSessionId input_generation=${inputGeneration.get()} " +
                "sequence=${utterance.sequence} target=${target.languageTag} elapsed_ms=$elapsedMillis")
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
            RuntimeDiagnosticLog.record("synthesis_complete", "session_id=$diagnosticSessionId input_generation=${inputGeneration.get()} " +
                "sequence=${utterance.sequence} target=${target.languageTag} elapsed_ms=$elapsedMillis frames=${pcm.frameCount} bytes=${pcm.byteCount}")
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
            RuntimeDiagnosticLog.record("synthesis_first_pcm", "session_id=$diagnosticSessionId input_generation=${inputGeneration.get()} " +
                "sequence=${utterance.sequence} target=${target.languageTag} elapsed_ms=$elapsedMillis observed_ns=${SystemClock.elapsedRealtimeNanos()}")
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
            ?.let { line ->
                app.transcriptArchive.enqueue(persistentSession, line)
                // Final observer events are durable inputs, not a conflated UI snapshot.
                // Save them before a later utterance evicts this row from the display window.
                broadcastStreamSession?.generation?.let { part -> app.recordings.capture(part, listOf(line)) }
            }
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
    private fun listenerBroadcastStatus(): GuideCastBroadcastStatus {
        val snapshot = app.broadcastRuntime.state.value
        val phase = when (snapshot.phase) {
            BroadcastPhase.IDLE -> GuideCastBroadcastPhase.IDLE
            BroadcastPhase.STARTING -> GuideCastBroadcastPhase.PREPARING
            BroadcastPhase.LIVE -> GuideCastBroadcastPhase.LIVE
            BroadcastPhase.PAUSED -> GuideCastBroadcastPhase.PAUSED
            BroadcastPhase.FAILED -> GuideCastBroadcastPhase.FAILED
        }
        val ready = GuideCastChannelReadiness.READY
        val preparing = GuideCastChannelReadiness.PREPARING
        val channels = snapshot.translationChannels.associate { channel ->
            val audio = when {
                channel.synthesisState == BroadcastChannelWorkerState.DEGRADED -> GuideCastChannelReadiness.SUBTITLES_ONLY
                channel.publishedFrameCount > 0 || channel.synthesisState == BroadcastChannelWorkerState.ACTIVE -> ready
                else -> preparing
            }
            channel.channelId to GuideCastChannelStatus(audio,
                nextAction = if (audio == GuideCastChannelReadiness.SUBTITLES_ONLY)
                    GuideCastListenerNextAction.ASK_BROADCASTER_TO_RETRY
                else GuideCastListenerNextAction.NONE)
        }.toMutableMap()
        channels["source"] = GuideCastChannelStatus(
            if (snapshot.inputPhase == InputPhase.ACTIVE || snapshot.testToneActive) ready else preparing)
        return GuideCastBroadcastStatus(phase, channels)
    }

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
        recordingCaptionJob?.cancel(); recordingCaptionJob = null
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
            streamingTranslationRequest = null
            pendingStreamingInput = null
            testToneRequestGeneration.incrementAndGet()
            testToneRequestPending.set(false)
            testToneActive.set(false)
            wakeLockToRelease = wakeLock
            wakeLock = null
            wakeLockRenewalJob?.cancel()
            wakeLockRenewalJob = null
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
        app.recordings.closePartAfterTerminalization {
            releaseTranslationTestResources()
            app.broadcastRuntime.state.value.transcripts
        }
        if (hadTranslationResources) app.translationDiagnostics.end()
        releaseWebBroadcastLeaseIfUnused()
    }

    private fun releaseWebBroadcastLeaseIfUnused() {
        synchronized(broadcastResourceLock) {
            if (runningServer == null && broadcastStreamSession == null && serverStartJob == null) {
                webBroadcastLease?.close()
                webBroadcastLease = null
            }
        }
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
        if (wakeLockRenewalJob?.isActive == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val heldLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GuideCast::Broadcast",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_LEASE_MILLIS)
        }
        wakeLock = heldLock
        wakeLockRenewalJob = serviceScope.renewSessionLease(WAKE_LOCK_RENEW_MILLIS) {
            synchronized(broadcastResourceLock) {
                if (wakeLock !== heldLock || broadcastStreamSession == null) false else {
                    heldLock.acquire(WAKE_LOCK_LEASE_MILLIS)
                    true
                }
            }
        }
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
        // Derive the Android 14+ foreground type from the current capture owner. A broadcast
        // pause can keep capture running, while turning input off releases that owner.
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
        if (foregroundStartDispatch) return
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
        private val inputStopCompletion = StreamingInputStopCompletion()
        private const val ACTION_START_INPUT = "app.guidecast.action.START_INPUT"
        private const val ACTION_START_RELAY_MICROPHONE = "app.guidecast.action.START_RELAY_MICROPHONE"
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
        private const val EXTRA_RUN_MODE = "run_mode"
        private const val EXTRA_INTERPRETER_RELAY = "interpreter_relay"
        private const val EXTRA_DEFER_RELAY_INPUT = "defer_relay_input"
        private const val EXTRA_BROADCAST_TITLE = "broadcast_title"
        private const val EXTRA_RECORDING_ID = "recording_id"
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
        private const val LOCAL_MONITOR_ROUTE_WAIT_STEP_MILLIS = 25L
        private const val LOCAL_MONITOR_ROUTE_WAIT_ATTEMPTS = 80
        /** 20 ms frames: update local-monitor UI at most twice per second. */
        private const val LOCAL_MONITOR_UI_FRAME_CADENCE = 25L
        // Renew a bounded lease while a session is owned; stop releases it immediately.
        private const val WAKE_LOCK_LEASE_MILLIS = 10 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MILLIS = 5 * 60 * 1000L

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
            "ru" to "러시아어 · Русский",
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
            runMode: BroadcastRunMode = BroadcastRunMode.NETWORK,
            interpreterRelay: Boolean = false,
            deferRelayInput: Boolean = false,
            broadcastTitle: String = "",
        ) {
            val intent = Intent(context, BroadcastService::class.java)
                .setAction(ACTION_START_BROADCAST)
                .putExtra(EXTRA_ACCESS_MODE, accessMode.name)
                .putExtra(EXTRA_RUN_MODE, runMode.name)
                .putExtra(EXTRA_INTERPRETER_RELAY, interpreterRelay)
                .putExtra(EXTRA_DEFER_RELAY_INPUT, deferRelayInput)
                .putExtra(EXTRA_BROADCAST_TITLE, broadcastTitle)
                .putExtra(EXTRA_TRANSLATION_LANGUAGES, translationLanguages)
                .putExtra(EXTRA_SOURCE_LANGUAGE, sourceLanguageTag)
                .putExtra(EXTRA_USE_GEMMA, useGemma)
                .putExtra(EXTRA_SELECTIVE_REFINEMENT, selectiveTranslationRefinement && useGemma)
            if (pin != null) intent.putExtra(EXTRA_PIN, pin)
            if (speakerPin != null) intent.putExtra(EXTRA_SPEAKER_PIN, speakerPin)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startRelayMicrophone(context: Context, recordingId: String) {
            ContextCompat.startForegroundService(context, Intent(context, BroadcastService::class.java)
                .setAction(ACTION_START_RELAY_MICROPHONE).putExtra(EXTRA_RECORDING_ID, recordingId))
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
