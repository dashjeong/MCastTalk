package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.ProviderTranscriptSemanticAssembler
import app.guidecast.core.translation.RealtimeInterpretationPolicy
import app.guidecast.core.translation.RecognizedUtterance
import app.guidecast.core.translation.SpeechRecognitionConfig
import app.guidecast.core.translation.SpeechRecognitionEngine
import app.guidecast.core.translation.sentenceCompletionInterpretationPolicy
import app.guidecast.provider.android.stt.AndroidOnDeviceSpeechRecognitionEngine
import app.guidecast.provider.android.stt.AndroidSpeechRecognitionException
import app.guidecast.provider.moonshine.stt.MoonshineSpeechRecognitionEngine
import app.guidecast.provider.moonshine.stt.MoonshineSpeechLanguageStatus
import app.guidecast.provider.moonshine.stt.MoonshineSttNativeReleaseResult
import java.io.Closeable
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GalaxySpeechRecognitionCapability(
    val available: Boolean,
    val reason: String? = null,
)

data class GalaxySpeechLanguageStatus(
    val isReady: Boolean,
    val message: String,
)

private sealed interface RecognitionAttemptOutcome {
    data object Completed : RecognitionAttemptOutcome
    data class Failed(val error: Throwable) : RecognitionAttemptOutcome
    data class EndpointRestart(val attemptId: Long) : RecognitionAttemptOutcome
    data object CaptureSilenced : RecognitionAttemptOutcome
}

internal enum class RecognitionRecoveryAction {
    RESTART_SESSION,
    SWITCH_BACKEND,
    FAIL,
}

internal data class RecognitionInputProfile(
    val interpretationPolicy: RealtimeInterpretationPolicy,
    val pcmBufferCapacity: Int,
    val sentenceCompletionMode: Boolean,
)

/** Every device keeps semantic phrases and a short bounded realtime PCM reserve. */
internal fun recognitionInputProfile(
    manufacturer: String,
    model: String,
    sdkInt: Int,
    isLowRamDevice: Boolean,
): RecognitionInputProfile {
    val normalizedManufacturer = manufacturer.uppercase(Locale.ROOT)
    val normalizedModel = model.uppercase(Locale.ROOT)
    val samsungS21OrOlder = normalizedManufacturer == "SAMSUNG" &&
        (normalizedModel.startsWith("SM-G") || normalizedModel.startsWith("SM-N"))
    val compatibility = samsungS21OrOlder || sdkInt <= Build.VERSION_CODES.S || isLowRamDevice
    return RecognitionInputProfile(
        // Semantic sentence completion is a quality rule for every device. A slow recognizer must
        // apply backpressure instead of accumulating tens of seconds of stale audio.
        interpretationPolicy = sentenceCompletionInterpretationPolicy(),
        pcmBufferCapacity = if (compatibility) 16 else 8,
        sentenceCompletionMode = compatibility,
    )
}

/** Samsung recognizer session endings must not be promoted to a channel-wide translation error. */
internal fun recognitionRecoveryAction(
    error: Throwable,
    alternateBackendReady: Boolean,
): RecognitionRecoveryAction = when {
    error is AndroidSpeechRecognitionException &&
        error.errorCode in setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        ) -> RecognitionRecoveryAction.RESTART_SESSION

    error is AndroidSpeechRecognitionException &&
        error.errorCode in setOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        ) -> if (alternateBackendReady) {
            RecognitionRecoveryAction.SWITCH_BACKEND
        } else {
            RecognitionRecoveryAction.FAIL
        }

    alternateBackendReady -> RecognitionRecoveryAction.SWITCH_BACKEND
    else -> RecognitionRecoveryAction.RESTART_SESSION
}

/** Normal endpoint/no-match restarts are listening state, not an operator-facing engine failure. */
internal fun recognitionRecoveryStatusMessage(error: Throwable): String =
    if (error is AndroidSpeechRecognitionException &&
        error.errorCode in setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
    ) {
        "발화를 기다리는 중 · 문장 인식을 계속합니다."
    } else {
        "음성인식 경로를 자동 복구했습니다: " +
            (error.message ?: error.javaClass.simpleName)
    }

/** Rejects a duplicate final and any partial that races in after that final. */
internal class ProviderFinalGate(
    private val maximumCompletedSequences: Int = 256,
) {
    private val completedSequences = LinkedHashSet<Long>()

    init {
        require(maximumCompletedSequences in 8..1_024)
    }

    @Synchronized
    fun shouldAccept(providerSequence: Long, isFinal: Boolean): Boolean {
        if (providerSequence in completedSequences) return false
        if (isFinal) {
            completedSequences += providerSequence
            while (completedSequences.size > maximumCompletedSequences) {
                completedSequences.remove(completedSequences.first())
            }
        }
        return true
    }
}

internal enum class RecognitionBackend {
    ANDROID,
    MOONSHINE,
}

internal data class RecognitionBackendDecision(
    val backend: RecognitionBackend?,
    val status: GalaxySpeechLanguageStatus,
)

internal typealias MoonshineRecognitionRestartAdmission = suspend (
    languageTag: String,
    warm: suspend () -> MoonshineSpeechLanguageStatus,
) -> MoonshineSpeechLanguageStatus

/**
 * Keeps healthy session restarts on the hot path while forcing a reclaimed worker through the
 * process-wide cold-load admission supplied by [GuideCastApplication]. The second liveness check
 * inside [warmWithNativeAdmission] makes concurrent restarts single-flight at the shared key.
 */
internal suspend fun ensureMoonshineWorkerForRecognition(
    languageTag: String,
    hasActiveWorker: () -> Boolean,
    activeStatus: () -> MoonshineSpeechLanguageStatus,
    prepareNative: suspend () -> MoonshineSpeechLanguageStatus,
    warmWithNativeAdmission: MoonshineRecognitionRestartAdmission,
): MoonshineSpeechLanguageStatus {
    if (hasActiveWorker()) return activeStatus()
    val result = warmWithNativeAdmission(languageTag) {
        if (hasActiveWorker()) activeStatus() else prepareNative()
    }
    check(result.isReady && hasActiveWorker()) {
        result.message.ifBlank { "Moonshine STT 작업자 재시작 준비에 실패했습니다." }
    }
    return result
}

internal fun preferredKoreanRecognitionBackend(
    sdkInt: Int,
    androidReady: Boolean,
    moonshineReady: Boolean,
): RecognitionBackend? = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU && androidReady -> RecognitionBackend.ANDROID
    moonshineReady -> RecognitionBackend.MOONSHINE
    else -> null
}

internal fun koreanRecognitionBackendPolicy(
    sdkInt: Int,
    androidReady: Boolean,
    moonshineReady: Boolean,
    androidMessage: String? = null,
    moonshineMessage: String? = null,
): RecognitionBackendDecision {
    val backend = preferredKoreanRecognitionBackend(sdkInt, androidReady, moonshineReady)
    val modern = sdkInt >= Build.VERSION_CODES.TIRAMISU
    val status = when (backend) {
        RecognitionBackend.ANDROID -> GalaxySpeechLanguageStatus(
            isReady = true,
            message = if (moonshineReady) {
                "Galaxy 한국어 오프라인 음성인식 준비됨 · 독립 PCM 대체 준비됨"
            } else {
                "Galaxy 한국어 오프라인 음성인식 준비됨 · PCM 호환 엔진 준비 필요"
            },
        )
        RecognitionBackend.MOONSHINE -> GalaxySpeechLanguageStatus(
            isReady = true,
            message = "독립 PCM 한국어 오프라인 음성인식 준비됨",
        )
        null -> GalaxySpeechLanguageStatus(
            isReady = false,
            message = if (modern) {
                androidMessage ?: moonshineMessage ?: "한국어 오프라인 음성 모델 준비가 필요합니다."
            } else {
                moonshineMessage ?: androidMessage ?: "한국어 오프라인 음성 모델 준비가 필요합니다."
            },
        )
    }
    return RecognitionBackendDecision(backend = backend, status = status)
}

/**
 * S23-and-newer recognition path.
 *
 * For modern API 33+ devices, the installed READY Android on-device recognizer is preferred because
 * it uses the Galaxy language pack and formatting. Moonshine is prepared and kept ready as an
 * immediate fallback if Samsung rejects injected PCM (e.g. ERROR_CLIENT) or returns an error.
 * API 29 / Note 9 and modern devices without a READY Android Korean model continue using Moonshine.
 */
class GalaxySpeechRecognitionEngine(
    context: Context,
    private val captureSilenced: StateFlow<Boolean> = MutableStateFlow(false),
    private val recognitionHints: (String) -> List<String> = { emptyList() },
    private val warmMoonshineForRestartWithNativeAdmission: MoonshineRecognitionRestartAdmission =
        { _, _ ->
            error("Moonshine STT live-restart native admission is not configured")
        },
) : SpeechRecognitionEngine, Closeable {
    private val android = AndroidOnDeviceSpeechRecognitionEngine(context)
    private val moonshine = MoonshineSpeechRecognitionEngine(context)
    private val inputProfile = context.recognitionInputProfile()
    private val mutableStatus = MutableStateFlow(
        GalaxySpeechLanguageStatus(
            isReady = false,
            message = "원문 언어 오프라인 음성인식을 확인하는 중입니다.",
        ),
    )
    val status: StateFlow<GalaxySpeechLanguageStatus> = mutableStatus

    @Volatile
    private var preparedBackend: RecognitionBackend? = null

    @Volatile
    private var preparedLanguageTag: String? = null

    @Volatile
    private var moonshineReady = false

    @Volatile
    private var androidReady = false

    fun capability(): GalaxySpeechRecognitionCapability {
        val androidCapability = android.capability()
        val moonshineCapability = moonshine.capability()
        return when {
            androidCapability.available || moonshineCapability.available ->
                GalaxySpeechRecognitionCapability(available = true)
            else -> GalaxySpeechRecognitionCapability(
                available = false,
                reason = listOfNotNull(androidCapability.reason, moonshineCapability.reason)
                    .joinToString(" · ")
                    .ifBlank { "이 기기에서 오프라인 음성인식을 사용할 수 없습니다." },
            )
        }
    }

    /** Non-Korean PCM recognition is intentionally Android-only until pinned models are bundled. */
    fun capability(languageTag: String): GalaxySpeechRecognitionCapability {
        val normalized = requireSupportedSourceLanguage(languageTag)
        if (shouldUseMoonshineForSource(normalized)) return capability()
        val androidCapability = android.capability()
        return if (androidCapability.available) {
            GalaxySpeechRecognitionCapability(available = true)
        } else {
            GalaxySpeechRecognitionCapability(
                available = false,
                reason = androidCapability.reason
                    ?: "${languageTag.sourceLanguageDisplayName()} 오프라인 음성인식을 사용할 수 없습니다.",
            )
        }
    }

    suspend fun languageStatus(languageTag: String): GalaxySpeechLanguageStatus {
        val normalizedLanguage = requireSupportedSourceLanguage(languageTag)
        val androidStatus = if (android.capability().available) {
            runCatching { android.languageStatus(languageTag) }.getOrNull()
        } else {
            null
        }
        androidReady = androidStatus?.isReady == true
        if (!shouldUseMoonshineForSource(normalizedLanguage)) {
            moonshineReady = false
            preparedBackend = if (androidReady) RecognitionBackend.ANDROID else null
            preparedLanguageTag = languageTag.takeIf { androidReady }
            return GalaxySpeechLanguageStatus(
                isReady = androidReady,
                message = androidStatus?.message
                    ?: "${languageTag.sourceLanguageDisplayName()} 오프라인 음성 모델 준비가 필요합니다.",
            ).also { mutableStatus.value = it }
        }
        val moonshineStatus = if (moonshine.capability().available) {
            runCatching { moonshine.languageStatus(languageTag) }.getOrNull()
        } else {
            null
        }
        moonshineReady = moonshineStatus?.isReady == true

        val decision = koreanRecognitionBackendPolicy(
            sdkInt = Build.VERSION.SDK_INT,
            androidReady = androidReady,
            moonshineReady = moonshineReady,
            androidMessage = androidStatus?.message,
            moonshineMessage = moonshineStatus?.message,
        )
        preparedBackend = decision.backend
        preparedLanguageTag = languageTag.takeIf { decision.backend != null }
        return decision.status.also { mutableStatus.value = it }
    }

    suspend fun prepareLanguage(languageTag: String): GalaxySpeechLanguageStatus = prepareLanguage(
        languageTag = languageTag,
        isPreparationCurrent = { true },
        commitIfPreparationCurrent = { mutation -> mutation(); true },
        warmMoonshineWithNativeAdmission = { _, warm -> warm() },
    )

    suspend fun prepareLanguage(
        languageTag: String,
        isPreparationCurrent: () -> Boolean,
        commitIfPreparationCurrent: ((() -> Unit) -> Boolean),
        warmMoonshineWithNativeAdmission: suspend (
            languageTag: String,
            warm: suspend () -> app.guidecast.provider.moonshine.stt.MoonshineSpeechLanguageStatus,
        ) -> app.guidecast.provider.moonshine.stt.MoonshineSpeechLanguageStatus,
    ): GalaxySpeechLanguageStatus {
        val normalizedLanguage = requireSupportedSourceLanguage(languageTag)
        if (!shouldUseMoonshineForSource(normalizedLanguage)) {
            val preparing = GalaxySpeechLanguageStatus(
                isReady = false,
                message = "${languageTag.sourceLanguageDisplayName()} Galaxy 오프라인 음성인식을 준비하고 있습니다.",
            )
            if (!commitIfPreparationCurrent { mutableStatus.value = preparing }) {
                throw CancellationException("Speech-recognition preparation was superseded")
            }
            val androidStatus = if (android.capability().available) {
                try {
                    android.prepareLanguage(languageTag)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            if (!isPreparationCurrent()) {
                throw CancellationException("Speech-recognition preparation was superseded")
            }
            val nextAndroidReady = androidStatus?.isReady == true
            val result = GalaxySpeechLanguageStatus(
                isReady = nextAndroidReady,
                message = androidStatus?.message
                    ?: "${languageTag.sourceLanguageDisplayName()} 오프라인 음성인식 준비에 실패했습니다.",
            )
            if (!commitIfPreparationCurrent {
                    moonshineReady = false
                    androidReady = nextAndroidReady
                    preparedBackend = if (nextAndroidReady) RecognitionBackend.ANDROID else null
                    preparedLanguageTag = languageTag.takeIf { nextAndroidReady }
                    mutableStatus.value = result
                }
            ) {
                throw CancellationException("Speech-recognition preparation was superseded")
            }
            return result
        }
        val preparing = GalaxySpeechLanguageStatus(
            isReady = false,
            message = "독립 PCM 한국어 음성인식을 준비하고 있습니다.",
        )
        if (!commitIfPreparationCurrent { mutableStatus.value = preparing }) {
            throw CancellationException("Speech-recognition preparation was superseded")
        }
        val moonshineResult = if (moonshine.capability().available) {
            try {
                // Model bytes are downloaded and fully hashed before shared native admission.
                moonshine.prepareLanguageAssets(languageTag)
                warmMoonshineWithNativeAdmission(languageTag) {
                    moonshine.prepareNativeLanguage(languageTag)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val nextMoonshineReady = moonshineResult?.isReady == true

        val androidStatus = if (android.capability().available) {
            if (!isPreparationCurrent()) {
                throw CancellationException("Speech-recognition preparation was superseded")
            }
            val compatibilityCheck = GalaxySpeechLanguageStatus(
                isReady = false,
                message = "Galaxy 오프라인 음성인식 호환 경로를 확인하고 있습니다.",
            )
            if (!commitIfPreparationCurrent { mutableStatus.value = compatibilityCheck }) {
                throw CancellationException("Speech-recognition preparation was superseded")
            }
            prepareRecognitionAlternative(
                primaryReady = nextMoonshineReady,
                inspectInstalled = { android.languageStatus(languageTag) },
                prepareRequired = { android.prepareLanguage(languageTag) },
            ).onFailure { error ->
                Log.w("GuideCastRecognition", "Android alternative preparation failed; Moonshine ready=$nextMoonshineReady", error)
            }.getOrNull()
        } else {
            null
        }
        if (!isPreparationCurrent()) {
            throw CancellationException("Speech-recognition preparation was superseded")
        }
        val nextAndroidReady = androidStatus?.isReady == true

        val decision = koreanRecognitionBackendPolicy(
            sdkInt = Build.VERSION.SDK_INT,
            androidReady = nextAndroidReady,
            moonshineReady = nextMoonshineReady,
            androidMessage = androidStatus?.message,
            moonshineMessage = moonshineResult?.message,
        )
        if (!commitIfPreparationCurrent {
                androidReady = nextAndroidReady
                moonshineReady = nextMoonshineReady
                preparedBackend = decision.backend
                preparedLanguageTag = languageTag.takeIf { decision.backend != null }
                mutableStatus.value = decision.status
            }
        ) {
            throw CancellationException("Speech-recognition preparation was superseded")
        }
        return decision.status
    }

    fun hasActiveMoonshineWorker(languageTag: String): Boolean =
        shouldUseMoonshineForSource(requireSupportedSourceLanguage(languageTag)) &&
            moonshine.hasActivePreparedWorker(languageTag)

    fun activeMoonshineStatus(
        languageTag: String,
    ): app.guidecast.provider.moonshine.stt.MoonshineSpeechLanguageStatus {
        require(shouldUseMoonshineForSource(requireSupportedSourceLanguage(languageTag))) {
            "Moonshine STT is not available for $languageTag"
        }
        return moonshine.status.value
    }

    override fun recognize(
        frames: Flow<PcmAudioFrame>,
        config: SpeechRecognitionConfig,
    ): Flow<RecognizedUtterance> = channelFlow {
        val normalizedSourceLanguage = requireSupportedSourceLanguage(config.sourceLanguageTag)
        val interpretationSegmenter = ProviderTranscriptSemanticAssembler(
            inputProfile.interpretationPolicy,
        )
        val segmenterMutex = Mutex()
        val speechActivity = AdaptiveSpeechActivityDetector()
        val nextSourceSequence = AtomicLong(0)
        val nextRecognitionAttempt = AtomicLong(0)
        val activeRecognitionAttempt = AtomicLong(NO_ACTIVE_RECOGNITION_ATTEMPT)
        val endpointRestartRequests = Channel<Long>(capacity = Channel.CONFLATED)

        suspend fun emitSegmenterOutput(block: () -> List<RecognizedUtterance>) {
            segmenterMutex.withLock {
                block().forEach { interpretedUnit -> send(interpretedUnit) }
            }
        }

        suspend fun advanceSegmenter(nowNanos: Long): Boolean = segmenterMutex.withLock {
            interpretationSegmenter.tick(nowNanos).forEach { interpretedUnit ->
                send(interpretedUnit)
            }
            interpretationSegmenter.shouldRequestRecognizerEndpoint(nowNanos)
        }

        val pcm = Channel<PcmAudioFrame>(
            // Roughly sub-second on typical Galaxy AudioRecord frame sizes. Backpressure reaches
            // the short capture hand-off instead of silently deleting the beginning of a phrase
            // or accumulating a multi-second interpretation delay.
            capacity = inputProfile.pcmBufferCapacity,
        )
        val feeder = launch(Dispatchers.Default) {
            try {
                frames.collect { frame ->
                    val activity = speechActivity.observe(
                        pcmS16Le = frame.bytes,
                        capturedAtNanos = frame.capturedAtElapsedRealtimeNanos,
                    )
                    segmenterMutex.withLock {
                        interpretationSegmenter.observeSpeechActivity(
                            isSpeech = activity.active,
                            capturedAtNanos = if (activity.active) {
                                activity.lastSpeechAtNanos
                                    ?: frame.capturedAtElapsedRealtimeNanos
                            } else {
                                frame.capturedAtElapsedRealtimeNanos
                            },
                        )
                    }
                    pcm.send(frame)
                }
            } finally {
                pcm.close()
            }
        }
        val deadlineTicker = launch(Dispatchers.Default) {
            while (currentCoroutineContext().isActive) {
                delay(SEGMENTER_TICK_MILLIS)
                val requestEndpoint = advanceSegmenter(SystemClock.elapsedRealtimeNanos())
                if (requestEndpoint) {
                    val attemptId = activeRecognitionAttempt.get()
                    if (attemptId != NO_ACTIVE_RECOGNITION_ATTEMPT) {
                        endpointRestartRequests.trySend(attemptId)
                    }
                }
            }
        }
        val preparedForThisLanguage = preparedLanguageTag?.let(::normalizeSourceLanguage) ==
            normalizedSourceLanguage
        var backend = preparedBackend.takeIf { preparedForThisLanguage }
            ?: if (!shouldUseMoonshineForSource(normalizedSourceLanguage)) {
                RecognitionBackend.ANDROID
            } else {
                preferredKoreanRecognitionBackend(
                    sdkInt = Build.VERSION.SDK_INT,
                    androidReady = androidReady,
                    moonshineReady = moonshineReady,
                ) ?: if (android.capability().available && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    RecognitionBackend.ANDROID
                } else {
                    RecognitionBackend.MOONSHINE
                }
            }
        check(shouldUseMoonshineForSource(normalizedSourceLanguage) || backend == RecognitionBackend.ANDROID) {
            "비한국어 원문은 Galaxy 온디바이스 외부 PCM 음성인식만 사용합니다."
        }
        var consecutiveFailures = 0
        var androidCaptureConflict = false

        try {
            // Always let one recognizer consume buffered PCM and EOF. A short finite source can
            // finish before this coroutine is scheduled; the former pre-check then skipped every
            // frame and produced an empty transcript.
            do {
                val attemptBackend = backend
                val engine: SpeechRecognitionEngine = when (attemptBackend) {
                    RecognitionBackend.ANDROID -> android
                    RecognitionBackend.MOONSHINE -> moonshine
                }
                val providerSequenceMap = RecognitionSequenceMapper(nextSourceSequence)
                val finalGate = ProviderFinalGate()
                val attemptId = nextRecognitionAttempt.getAndIncrement()
                activeRecognitionAttempt.set(attemptId)
                mutableStatus.value = GalaxySpeechLanguageStatus(
                    isReady = true,
                    message = if (attemptBackend == RecognitionBackend.ANDROID) {
                        "Galaxy ${config.sourceLanguageTag.sourceLanguageDisplayName()} " +
                            "오프라인 음성인식 사용 중 · 외부 PCM"
                    } else if (!moonshine.hasActivePreparedWorker(config.sourceLanguageTag)) {
                        "독립 PCM 한국어 음성인식 작업자를 안전하게 다시 준비하는 중"
                    } else if (inputProfile.sentenceCompletionMode) {
                        "독립 PCM 한국어 음성인식 사용 중 · 문장 완결 우선"
                    } else {
                        "독립 PCM 한국어 음성인식 사용 중"
                    },
                )

                val attemptOutcome = supervisorScope {
                    val completion = CompletableDeferred<RecognitionAttemptOutcome>()
                    val captureConflict = CompletableDeferred<RecognitionAttemptOutcome>()
                    val captureWatcher = if (attemptBackend == RecognitionBackend.ANDROID) launch {
                        observeSustainedCaptureSilencing(captureSilenced) {
                            captureConflict.complete(RecognitionAttemptOutcome.CaptureSilenced)
                        }
                    } else null
                    val collector: Job = launch {
                        try {
                            if (attemptBackend == RecognitionBackend.MOONSHINE) {
                                val restarted = ensureMoonshineWorkerForRecognition(
                                    languageTag = config.sourceLanguageTag,
                                    hasActiveWorker = {
                                        moonshine.hasActivePreparedWorker(config.sourceLanguageTag)
                                    },
                                    activeStatus = { moonshine.status.value },
                                    prepareNative = {
                                        moonshine.prepareNativeLanguage(config.sourceLanguageTag)
                                    },
                                    warmWithNativeAdmission =
                                        warmMoonshineForRestartWithNativeAdmission,
                                )
                                moonshineReady = restarted.isReady
                            }
                            // Snapshot only between recognition attempts. Never cancel a live
                            // utterance to apply edits; the current Tiny backend has no biasing API.
                            val attemptConfig = config.copy(
                                biasingPhrases = if (attemptBackend == RecognitionBackend.ANDROID) {
                                    runCatching { recognitionHints(config.sourceLanguageTag) }
                                        .getOrDefault(emptyList())
                                } else emptyList(),
                            )
                            engine.recognize(pcm.receiveAsFlow(), attemptConfig).collect { utterance ->
                                segmenterMutex.withLock {
                                    if (finalGate.shouldAccept(
                                            utterance.sequence,
                                            utterance.isFinal,
                                        )) {
                                        consecutiveFailures = 0
                                        val sequence = providerSequenceMap.map(utterance.sequence)
                                        interpretationSegmenter.accept(
                                            utterance.copy(sequence = sequence),
                                        ).forEach { interpretedUnit -> send(interpretedUnit) }
                                        if (utterance.isFinal) {
                                            providerSequenceMap.complete(utterance.sequence)
                                        }
                                    }
                                }
                            }
                            completion.complete(RecognitionAttemptOutcome.Completed)
                        } catch (cancelled: CancellationException) {
                            // Endpoint/owner cancellation already has a selected outcome. A
                            // provider-originated CancellationException while this child is still
                            // active is a failed attempt and must not leave the selector waiting.
                            currentCoroutineContext().ensureActive()
                            completion.complete(RecognitionAttemptOutcome.Failed(cancelled))
                        } catch (error: Throwable) {
                            completion.complete(RecognitionAttemptOutcome.Failed(error))
                        }
                    }

                    var selected: RecognitionAttemptOutcome
                    do {
                        selected = select {
                            completion.onAwait { it }
                            captureConflict.onAwait { it }
                            endpointRestartRequests.onReceive { requestedAttemptId ->
                                RecognitionAttemptOutcome.EndpointRestart(requestedAttemptId)
                            }
                        }
                    } while (
                        selected is RecognitionAttemptOutcome.EndpointRestart &&
                        selected.attemptId != attemptId
                    )

                    if (selected is RecognitionAttemptOutcome.EndpointRestart ||
                        selected is RecognitionAttemptOutcome.CaptureSilenced) {
                        // Wait for any provider final already inside the segmenter critical section.
                        // Otherwise cancellation could mutate final state but interrupt its send,
                        // and the following attempt seal could observe inconsistent provider state.
                        segmenterMutex.withLock { collector.cancel() }
                    }
                    collector.join()
                    captureWatcher?.cancel()
                    selected
                }
                activeRecognitionAttempt.compareAndSet(attemptId, NO_ACTIVE_RECOGNITION_ATTEMPT)

                // A provider session ending is not a semantic sentence boundary. Keep its immutable
                // lines in the bounded assembler across normal completion, endpoint restart, errors
                // and backend failover. Seal a missing line-final as provider stability only so it
                // cannot block later ordered lines. Actual input EOF below alone flushes a tail.
                emitSegmenterOutput {
                    interpretationSegmenter.sealProviderAttempt(
                        SystemClock.elapsedRealtimeNanos(),
                    )
                }
                providerSequenceMap.clear()

                when (attemptOutcome) {
                    RecognitionAttemptOutcome.CaptureSilenced -> {
                        // Release the Android recognizer before trying the already-prepared PCM
                        // engine. Never open a second microphone, bypass privacy, or bounce back
                        // into the conflicting backend during this recognition session.
                        androidCaptureConflict = true
                        check(shouldUseMoonshineForSource(normalizedSourceLanguage) && moonshineReady) {
                            "Android가 마이크 PCM을 차단했습니다. 다른 녹음·통화 앱 또는 마이크 접근 설정을 확인하세요."
                        }
                        backend = RecognitionBackend.MOONSHINE
                        preparedBackend = backend
                        mutableStatus.value = GalaxySpeechLanguageStatus(true,
                            "마이크 입력 충돌 감지 · 시스템 인식기를 종료하고 독립 PCM 인식으로 복구합니다.")
                    }
                    RecognitionAttemptOutcome.Completed,
                    is RecognitionAttemptOutcome.EndpointRestart,
                    -> {
                        if (!feeder.isCompleted) delay(RESTART_DELAY_MILLIS)
                    }

                    is RecognitionAttemptOutcome.Failed -> {
                        val error = attemptOutcome.error
                        val alternateReady = when (backend) {
                            RecognitionBackend.ANDROID ->
                                shouldUseMoonshineForSource(normalizedSourceLanguage) && moonshineReady
                            RecognitionBackend.MOONSHINE -> androidReady && !androidCaptureConflict
                        }
                        when (recognitionRecoveryAction(error, alternateReady)) {
                            RecognitionRecoveryAction.FAIL -> throw error
                            RecognitionRecoveryAction.SWITCH_BACKEND -> {
                                consecutiveFailures += 1
                                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw error
                                backend = when (backend) {
                                    RecognitionBackend.ANDROID -> RecognitionBackend.MOONSHINE
                                    RecognitionBackend.MOONSHINE -> RecognitionBackend.ANDROID
                                }
                            }

                            RecognitionRecoveryAction.RESTART_SESSION -> {
                                if (error is AndroidSpeechRecognitionException &&
                                    error.errorCode in setOf(
                                        SpeechRecognizer.ERROR_NO_MATCH,
                                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                                    )
                                ) {
                                    consecutiveFailures = 0
                                } else {
                                    consecutiveFailures += 1
                                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw error
                                }
                            }
                        }
                        preparedBackend = backend
                        preparedLanguageTag = config.sourceLanguageTag
                        mutableStatus.value = GalaxySpeechLanguageStatus(
                            isReady = true,
                            message = recognitionRecoveryStatusMessage(error),
                        )
                        delay(FAILURE_RETRY_DELAY_MILLIS)
                    }
                }
            } while (currentCoroutineContext().isActive && !feeder.isCompleted)
            if (feeder.isCompleted) {
                emitSegmenterOutput {
                    interpretationSegmenter.finish(SystemClock.elapsedRealtimeNanos())
                }
            }
        } finally {
            activeRecognitionAttempt.set(NO_ACTIVE_RECOGNITION_ATTEMPT)
            endpointRestartRequests.close()
            deadlineTicker.cancel()
            feeder.cancel()
            pcm.close()
        }
    }

    /**
     * Reclaims Moonshine's isolated native process without closing this reusable recognition
     * facade. Active preparation/recognition keeps ownership and returns `false`; a later cleanup
     * attempt can retry without interrupting subtitles or translated speech.
     */
    suspend fun releaseNativeResources(): Boolean = moonshine.releaseNativeResources()

    fun nativeResourceUseEpoch(): Long = moonshine.nativeResourceUseEpoch()

    suspend fun awaitNativeIdleOrUseChanged(
        expectedUseEpoch: Long,
    ): MoonshineSttNativeReleaseResult =
        moonshine.awaitNativeIdleOrUseChanged(expectedUseEpoch)

    suspend fun releaseNativeResourcesIfUnchanged(
        expectedUseEpoch: Long,
    ): MoonshineSttNativeReleaseResult =
        moonshine.releaseNativeResourcesIfUnchanged(expectedUseEpoch)

    override fun close() {
        moonshine.close()
    }

    private companion object {
        const val RESTART_DELAY_MILLIS = 150L
        const val FAILURE_RETRY_DELAY_MILLIS = 500L
        const val SEGMENTER_TICK_MILLIS = 100L
        const val NO_ACTIVE_RECOGNITION_ATTEMPT = -1L
        const val MAX_CONSECUTIVE_FAILURES = 3
    }
}

internal val GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS = setOf("ko", "en", "ja", "zh", "es", "ar")

internal fun normalizeSourceLanguage(languageTag: String): String =
    Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)

internal fun requireSupportedSourceLanguage(languageTag: String): String =
    normalizeSourceLanguage(languageTag).also { normalized ->
        require(normalized in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS) {
            "지원하지 않는 원문 언어입니다: $languageTag"
        }
    }

internal fun shouldUseMoonshineForSource(languageTag: String): Boolean =
    normalizeSourceLanguage(languageTag) == "ko"

internal fun String.sourceLanguageDisplayName(): String =
    Locale.forLanguageTag(this).getDisplayLanguage(Locale.KOREAN)
        .takeIf(String::isNotBlank)
        ?: substringBefore('-')

private fun Context.recognitionInputProfile(): RecognitionInputProfile {
    val activityManager = getSystemService(ActivityManager::class.java)
    return recognitionInputProfile(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
        isLowRamDevice = activityManager.isLowRamDevice,
    )
}
