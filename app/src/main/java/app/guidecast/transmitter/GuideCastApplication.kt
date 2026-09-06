package app.guidecast.transmitter

import android.app.Application
import android.util.Log
import app.guidecast.core.audio.AndroidAudioInputRepository
import app.guidecast.core.audio.AudioCaptureEngine
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.MAX_SIMULTANEOUS_AUDIO_CHANNELS
import app.guidecast.core.translation.NativeColdLoadTicket
import app.guidecast.core.translation.NativeColdLoadTicketContext
import app.guidecast.core.translation.currentNativeColdLoadTicket
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import app.guidecast.provider.moonshine.stt.MoonshineSttNativeReleaseResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class GuideCastApplication : Application() {
    private val nativeEngineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.e(LOG_TAG, "Translation backend lifecycle task failed", error)
            RuntimeDiagnosticLog.failure("backend_lifecycle", error)
        },
    )
    private val nativeEngineLifecycleMutex = Mutex()
    private val backendUseState = TranslationBackendUseState()
    private val preparationOwners = TranslationPreparationOwnerState()
    private var pendingBackendCleanup: Job? = null
    private var settingsStandbyLease: TranslationBackendUseLease? = null
    private var settingsStandbyExpiry: Job? = null
    /** One admission owner for every settings, test and broadcast cold load in this process. */
    private val nativeColdLoadCoordinator =
        NativeColdLoadCoordinator(MAX_STANDARD_NATIVE_COLD_LOADS)
    val audioInputRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAudioInputRepository(this)
    }
    val audioCaptureEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioCaptureEngine(this)
    }
    val audioStreams by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AudioStreamRegistry(
            maxChannels = MAX_SIMULTANEOUS_AUDIO_CHANNELS,
            maxListeners = 50,
        )
    }
    private val translationProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MlKitTranslationProvider(
            this,
            sourceLanguageTag = DEFAULT_SOURCE_LANGUAGE_TAG,
            requireWifiForModels = false,
        ).also { provider ->
            nativeEngineScope.launch {
                provider.modelManager.statuses.map { states -> states.joinToString { "${it.languageTag}:${it.readiness}:${it.downloadedBytes / 1048576}MiB:error=${it.errorMessage != null}" } }
                    .distinctUntilChanged().collect { RuntimeDiagnosticLog.record("ml_models", it) }
            }
        }
    }
    val translationProvider by translationProviderDelegate
    private val gemmaTranslationProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GemmaTranslationProvider(this).also { provider ->
            nativeEngineScope.launch {
                provider.modelManager.status.map { "${it.variant}:${it.readiness}:${it.downloadedBytes / 1048576}MiB:error=${it.errorMessage != null}" }
                    .distinctUntilChanged().collect { RuntimeDiagnosticLog.record("gemma_model", it) }
            }
        }
    }
    val gemmaTranslationProvider by gemmaTranslationProviderDelegate
    private val speechSynthesisProviderDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GalaxySpeechSynthesisProvider(this).also { provider ->
            nativeEngineScope.launch {
                provider.statuses.map { states -> states.joinToString { "${it.languageTag}:${it.readiness}:error=${it.errorMessage != null}" } }
                    .distinctUntilChanged().collect { RuntimeDiagnosticLog.record("tts_models", it) }
            }
        }
    }
    val speechSynthesisProvider by speechSynthesisProviderDelegate
    private val speechRecognitionEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GalaxySpeechRecognitionEngine(
            context = this,
            captureSilenced = audioCaptureEngine.clientSilenced,
            recognitionHints = { language -> speechCorrections.recognitionHints(language) },
            warmMoonshineForRestartWithNativeAdmission = { languageTag, warm ->
                withProcessNativeColdLoadLease(
                    key = ProcessNativeColdLoadKeys.speechRecognition(languageTag),
                    // A reclaimed STT worker has no resident native state to reuse. Keep its
                    // replacement load from overlapping another uncertain large native mapping.
                    serializeWithAllColdLoads = true,
                ) {
                    warm()
                }
            },
        )
    }
    val speechRecognitionEngine by speechRecognitionEngineDelegate
    val broadcastRuntime = BroadcastRuntime()
    private val transcriptArchiveDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BroadcastTranscriptArchive(this)
    }
    val transcriptArchive by transcriptArchiveDelegate
    val translationDiagnostics by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TranslationSessionDiagnostics(this)
    }
    val glossary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TranslationGlossaryRepository(this) }
    val speechCorrections by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SpeechCorrectionRepository(this) }
    val microphoneNoiseSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MicrophoneNoiseSettings(this) }
    val diagnosticExportState = kotlinx.coroutines.flow.MutableStateFlow(DiagnosticExportState())

    /** Owns the short export independently of screen recreation; keeps only application context. */
    fun exportDiagnosticsTo(uri: android.net.Uri) {
        val current = diagnosticExportState.value
        if (current.busy || !diagnosticExportState.compareAndSet(current, DiagnosticExportState(busy = true))) return
        nativeEngineScope.launch(Dispatchers.IO) {
            val message = try {
                requireNotNull(contentResolver.openOutputStream(uri)).use { output ->
                    RuntimeDiagnosticLog.export(File(filesDir, "diagnostics"), output)
                }
                "진단 로그를 저장했습니다. 음성·통역 문장·PIN은 포함하지 않습니다."
            } catch (error: Exception) { "로그 저장 실패: ${error.javaClass.simpleName}" }
            diagnosticExportState.value = DiagnosticExportState(message = message)
        }
    }

    internal suspend fun acquireProcessNativeColdLoadLease(
        key: String,
        serializeWithAllColdLoads: Boolean,
    ): NativeColdLoadTicket = nativeColdLoadCoordinator.acquire(
        key = key,
        serializeWithAllColdLoads = serializeWithAllColdLoads,
        currentAdmission = NativeSupportMemoryAdmission.detect(this),
        onPressureDetected = { pressure ->
            Log.w(
                LOG_TAG,
                "Native cold load serialized under pressure: ${pressure.operatorMessage}",
            )
        },
    )

    /**
     * Carries the process lease only across the actual native warm-up/inference boundary.
     * Callers must finish downloads, file verification and fallback preparation before entering.
     */
    internal suspend fun <T> withProcessNativeColdLoadLease(
        key: String,
        serializeWithAllColdLoads: Boolean,
        operation: suspend () -> T,
    ): T {
        check(currentNativeColdLoadTicket() == null) {
            "A native cold-load operation cannot acquire a nested process lease"
        }
        val ticket = acquireProcessNativeColdLoadLease(key, serializeWithAllColdLoads)
        return try {
            withContext(NativeColdLoadTicketContext(ticket)) { operation() }
        } finally {
            ticket.close()
        }
    }

    internal suspend fun prepareTranslationModelsWithProcessAdmission(
        languageTags: Set<String>,
        serializeWithAllColdLoads: Boolean,
        owner: TranslationPreparationOwnerToken,
    ) {
        val activated = preparationOwners.runIfCurrent(owner) {
            translationProvider.activatePreparationGeneration(owner.generation)
        }
        if (!activated) throw supersededPreparation()
        translationProvider.reconcileTargets(languageTags, owner.generation)
        translationProvider.prepareModels(
            languageTags = languageTags,
            requestedSourceLanguageTag = owner.sourceLanguageTag,
            nativeOwnerGeneration = owner.generation,
            isNativeOwnerCurrent = { preparationOwners.isCurrent(owner) },
            reconcileNativeTargets = false,
        ) { languageTag, initialize ->
            if (!preparationOwners.isCurrent(owner)) return@prepareModels
            if (!translationProvider.hasActivePreparedWorker(languageTag)) {
                withProcessNativeColdLoadLease(
                    key = ProcessNativeColdLoadKeys.mlKitTranslation(languageTag),
                    serializeWithAllColdLoads = serializeWithAllColdLoads,
                ) {
                    // A settings/test/broadcast caller may have completed the exact worker while
                    // this request waited for the shared process permit.
                    if (preparationOwners.isCurrent(owner) &&
                        !translationProvider.hasActivePreparedWorker(languageTag)
                    ) {
                        initialize()
                    }
                }
            }
        }
        if (!preparationOwners.isCurrent(owner)) throw supersededPreparation()
    }

    internal suspend fun refreshTranslationModels(
        languageTags: Set<String>,
        owner: TranslationPreparationOwnerToken,
    ) {
        val activated = preparationOwners.runIfCurrent(owner) {
            translationProvider.activatePreparationGeneration(owner.generation)
        }
        if (!activated) throw supersededPreparation()
        translationProvider.refreshModels(
            languageTags = languageTags,
            nativeOwnerGeneration = owner.generation,
            isNativeOwnerCurrent = { preparationOwners.isCurrent(owner) },
        )
        if (!preparationOwners.isCurrent(owner)) throw supersededPreparation()
    }

    internal suspend fun prepareSpeechRecognitionWithProcessAdmission(
        languageTag: String,
        serializeWithAllColdLoads: Boolean,
        owner: TranslationPreparationOwnerToken,
    ): GalaxySpeechLanguageStatus {
        return speechRecognitionEngine.prepareLanguage(
        languageTag = languageTag,
        isPreparationCurrent = { preparationOwners.isCurrent(owner) },
        commitIfPreparationCurrent = { mutation ->
            preparationOwners.runIfCurrent(owner, mutation)
        },
        warmMoonshineWithNativeAdmission = { target, warm ->
            if (speechRecognitionEngine.hasActiveMoonshineWorker(target)) {
                speechRecognitionEngine.activeMoonshineStatus(target)
            } else {
                runPreparationOwnedNativeOperation(
                    isOwnerCurrent = { preparationOwners.isCurrent(owner) },
                    ownerLostMessage =
                        "A newer translation preparation owns speech recognition",
                    withAdmission = { operation ->
                        withProcessNativeColdLoadLease(
                            key = ProcessNativeColdLoadKeys.speechRecognition(target),
                            serializeWithAllColdLoads = serializeWithAllColdLoads,
                        ) { operation() }
                    },
                ) {
                    if (speechRecognitionEngine.hasActiveMoonshineWorker(target)) {
                        speechRecognitionEngine.activeMoonshineStatus(target)
                    } else {
                        warm().also {
                            if (!preparationOwners.isCurrent(owner)) {
                                if (!preparationOwners.currentSourceMatches(target)) {
                                    val useEpoch = speechRecognitionEngine.nativeResourceUseEpoch()
                                    speechRecognitionEngine.releaseNativeResourcesIfUnchanged(useEpoch)
                                }
                                throw kotlinx.coroutines.CancellationException(
                                    "A newer source-language preparation replaced this warm-up",
                                )
                            }
                        }
                    }
                }
            }
        },
    )
    }

    internal suspend fun beginSettingsPreparation(
        sourceLanguageTag: String,
        targetLanguageTags: Set<String>,
    ): TranslationPreparationOwnerToken = preparationOwners.beginSettings(
        sourceLanguageTag,
        targetLanguageTags,
    ).also(::activatePreparedProviderIfCurrent)

    internal suspend fun beginBroadcastPreparation(
        sourceLanguageTag: String,
        targetLanguageTags: Set<String>,
    ): TranslationPreparationOwnerToken = preparationOwners.beginBroadcast(
        sourceLanguageTag,
        targetLanguageTags,
    ).also(::activatePreparedProviderIfCurrent)

    private fun activatePreparedProviderIfCurrent(owner: TranslationPreparationOwnerToken) {
        if (!translationProviderDelegate.isInitialized()) return
        preparationOwners.runIfCurrent(owner) {
            translationProvider.activatePreparationGeneration(owner.generation)
        }
    }

    internal fun endPreparation(owner: TranslationPreparationOwnerToken) {
        preparationOwners.end(owner)
    }

    internal fun releaseSpeechSynthesisBroadcastOwnerIfInitialized(
        owner: TranslationPreparationOwnerToken,
    ) {
        if (owner.kind != TranslationPreparationOwnerKind.BROADCAST ||
            !speechSynthesisProviderDelegate.isInitialized()
        ) {
            return
        }
        speechSynthesisProvider.releaseBroadcastLanguages(owner.generation)
    }

    internal fun isPreparationCurrent(owner: TranslationPreparationOwnerToken): Boolean =
        preparationOwners.isCurrent(owner)

    internal suspend fun selectTranslationSource(owner: TranslationPreparationOwnerToken) {
        val activated = preparationOwners.runIfCurrent(owner) {
            translationProvider.activatePreparationGeneration(owner.generation)
        }
        if (!activated) throw supersededPreparation()
        translationProvider.selectSourceLanguage(owner.sourceLanguageTag) {
            preparationOwners.isCurrent(owner)
        }
    }

    private fun supersededPreparation() = kotlinx.coroutines.CancellationException(
        "A newer translation preparation owns native workers",
    )

    internal suspend fun acquireTranslationBackendUseIf(
        isOwnerCurrent: () -> Boolean,
        supersedeSettingsStandby: Boolean = false,
    ): TranslationBackendUseLease? {
        var previousStandby: TranslationBackendUseLease? = null
        val lease = nativeEngineLifecycleMutex.withLock {
            if (!isOwnerCurrent()) return@withLock null
            pendingBackendCleanup?.cancel()
            pendingBackendCleanup = null
            if (supersedeSettingsStandby) {
                settingsStandbyExpiry?.cancel()
                settingsStandbyExpiry = null
                previousStandby = settingsStandbyLease
                settingsStandbyLease = null
            }
            backendUseState.claim()
            TranslationBackendUseLease(::releaseTranslationBackendUse)
        }
        // Claim the replacement before closing standby, so there is never a zero-owner cleanup
        // window between settings preparation and broadcast takeover.
        previousStandby?.close()
        return lease
    }

    internal suspend fun <T> withTranslationBackendUse(
        retainSettingsStandbyFor: TranslationPreparationOwnerToken? = null,
        operation: suspend () -> T,
    ): T {
        val lease = requireNotNull(
            acquireTranslationBackendUseIf(
                isOwnerCurrent = { true },
                supersedeSettingsStandby = retainSettingsStandbyFor != null,
            ),
        )
        var succeeded = false
        return try {
            operation().also { succeeded = true }
        } finally {
            if (succeeded && retainSettingsStandbyFor != null) {
                transferTranslationBackendLeaseToStandby(lease) { retainedLease ->
                    retainSettingsStandby(retainedLease, retainSettingsStandbyFor)
                }
            } else {
                lease.close()
            }
        }
    }

    private suspend fun retainSettingsStandby(
        lease: TranslationBackendUseLease,
        owner: TranslationPreparationOwnerToken,
    ) {
        var replaced: TranslationBackendUseLease? = null
        val retained = nativeEngineLifecycleMutex.withLock {
            if (!preparationOwners.isCurrent(owner)) return@withLock false
            replaced = settingsStandbyLease
            settingsStandbyLease = lease
            settingsStandbyExpiry?.cancel()
            settingsStandbyExpiry = nativeEngineScope.launch {
                delay(SETTINGS_PREPARED_STANDBY_MILLIS)
                releaseSettingsStandbyIfSame(lease)
            }
            true
        }
        replaced?.takeIf { it !== lease }?.close()
        if (!retained) lease.close()
    }

    private suspend fun releaseSettingsStandbyIfSame(expected: TranslationBackendUseLease) {
        val released = nativeEngineLifecycleMutex.withLock {
            if (settingsStandbyLease !== expected) return@withLock null
            settingsStandbyLease = null
            settingsStandbyExpiry = null
            expected
        }
        released?.close()
    }

    private suspend fun releaseCurrentSettingsStandby() {
        val released = nativeEngineLifecycleMutex.withLock {
            settingsStandbyExpiry?.cancel()
            settingsStandbyExpiry = null
            settingsStandbyLease.also { settingsStandbyLease = null }
        }
        released?.close()
    }

    private fun releaseTranslationBackendUse() {
        nativeEngineScope.launch {
            val cleanupEpoch = nativeEngineLifecycleMutex.withLock {
                backendUseState.release()?.also { epoch ->
                    pendingBackendCleanup?.cancel()
                    pendingBackendCleanup = nativeEngineScope.launch {
                        delay(BACKEND_IDLE_RELEASE_GRACE_MILLIS)
                        try {
                            releaseIdleTranslationBackends(epoch)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Log.e(LOG_TAG, "Idle translation backend cleanup failed", error)
                        }
                    }
                }
            }
            // A sibling owner still exists, so no cleanup was scheduled.
            if (cleanupEpoch == null) return@launch
        }
    }

    private suspend fun releaseIdleTranslationBackends(cleanupEpoch: Long) {
        var sharedBackendsReleased = false
        var sharedBackendCleanupAttempts = 0
        while (true) {
            var sttUseEpoch: Long? = null
            val releaseResult = nativeEngineLifecycleMutex.withLock {
                if (!backendUseState.mayCleanup(cleanupEpoch)) {
                    return
                }
                if (!sharedBackendsReleased) {
                    val cleanups = buildList {
                        if (translationProviderDelegate.isInitialized()) add(
                            NativeBackendCleanup("ML Kit translation") {
                                translationProvider.releaseNativeResources()
                            },
                        )
                        if (gemmaTranslationProviderDelegate.isInitialized()) add(
                            NativeBackendCleanup("Gemma translation") {
                                gemmaTranslationProvider.resetEngineSafely()
                            },
                        )
                        if (speechSynthesisProviderDelegate.isInitialized()) add(
                            NativeBackendCleanup("speech synthesis") {
                                speechSynthesisProvider.releaseNativeResources()
                            },
                        )
                    }
                    val failures = runIsolatedNativeBackendCleanups(cleanups) { label, error ->
                        Log.e(LOG_TAG, "$label cleanup failed", error)
                    }
                    sharedBackendCleanupAttempts += 1
                    // Retry one transient provider close without turning a persistent failure into
                    // an idle busy-loop. A later owner/idle cycle gets another bounded attempt.
                    sharedBackendsReleased = failures.isEmpty() ||
                        sharedBackendCleanupAttempts >= MAX_BACKEND_CLEANUP_ATTEMPTS
                }
                if (!speechRecognitionEngineDelegate.isInitialized()) {
                    MoonshineSttNativeReleaseResult.RELEASED
                } else {
                    speechRecognitionEngine.nativeResourceUseEpoch().also { sttUseEpoch = it }
                    try {
                        speechRecognitionEngine.releaseNativeResourcesIfUnchanged(
                            requireNotNull(sttUseEpoch),
                        )
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) {
                            coroutineContext.ensureActive()
                        }
                        Log.e(LOG_TAG, "Speech recognition cleanup failed", error)
                        return
                    }
                }
            }
            if (releaseResult == MoonshineSttNativeReleaseResult.RELEASED) {
                if (sharedBackendsReleased) return
                continue
            }
            if (releaseResult == MoonshineSttNativeReleaseResult.SUPERSEDED) continue

            // No bounded polling: the provider resumes this coroutine only when a preparation or
            // recognition owner ends (or a newer use epoch supersedes it). App termination cancels
            // nativeEngineScope, so a wedged remote task causes no periodic battery wakeups.
            val idleResult = speechRecognitionEngine.awaitNativeIdleOrUseChanged(
                requireNotNull(sttUseEpoch),
            )
            if (idleResult == MoonshineSttNativeReleaseResult.SUPERSEDED) continue
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { RuntimeDiagnosticLog.initialize(File(filesDir, "diagnostics"), getProcessName()) }
        RuntimeDiagnosticLog.record("app_start", "version=${BuildConfig.VERSION_NAME} sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL} pid=${android.os.Process.myPid()}")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler != null) Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { runCatching { RuntimeDiagnosticLog.failure("uncaught", error) } }
            finally { previousHandler.uncaughtException(thread, error) }
        }
        if (getProcessName() == packageName) {
            nativeEngineScope.launch(Dispatchers.IO) {
                // Optional personalization must never block application/STT startup.
                runCatching { speechCorrections.refresh() }
                    .onFailure { RuntimeDiagnosticLog.record("stt_corrections", "load_failed") }
            }
            nativeEngineScope.launch {
                if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
                    getSystemService(android.app.ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(packageName, 0, 16).forEach {
                            RuntimeDiagnosticLog.record("previous_exit", "process=${it.processName} time=${it.timestamp} reason=${it.reason} status=${it.status} pssKiB=${it.pss} rssKiB=${it.rss}")
                        }
                }
            }
            nativeEngineScope.launch {
                broadcastRuntime.state.map { state ->
                    "broadcast=${state.phase} input=${state.inputPhase} inputError=${state.inputErrorMessage != null} error=${state.errorMessage != null} " +
                        state.translationChannels.joinToString { "${it.languageTag}:translate=${it.translationState}:tts=${it.synthesisState}:tfail=${it.translationFailures}:vfail=${it.synthesisFailures}" }
                }.distinctUntilChanged().collect { RuntimeDiagnosticLog.record("broadcast_state", it) }
            }
            translationDiagnostics.consumePreviousInterruptedSession()?.let { message ->
                broadcastRuntime.update { current ->
                    current.copy(translationTestMessage = message)
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        RuntimeDiagnosticLog.record("memory_trim", "level=$level heapBytes=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()}")
        if (level >= TRIM_MEMORY_BACKGROUND ||
            level == TRIM_MEMORY_RUNNING_LOW ||
            level == TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            // Selection and detach happen under the lifecycle mutex. Reading the field here could
            // race a replacement and make trim release neither the old nor the current standby.
            nativeEngineScope.launch { releaseCurrentSettingsStandby() }
        }
    }

    /** Resets a failed Gemma worker only while the owning broadcast session is still current. */
    suspend fun resetGemmaAfterFailureIf(isOwningSessionCurrent: () -> Boolean): Boolean =
        nativeEngineLifecycleMutex.withLock {
            if (!isOwningSessionCurrent()) return@withLock false
            if (gemmaTranslationProviderDelegate.isInitialized()) {
                return@withLock gemmaTranslationProvider.resetAfterLatestFailureSafely()
            }
            true
        }

    override fun onTerminate() {
        nativeEngineScope.cancel()
        audioInputRepository.close()
        if (translationProviderDelegate.isInitialized()) translationProvider.close()
        if (gemmaTranslationProviderDelegate.isInitialized()) gemmaTranslationProvider.close()
        if (speechSynthesisProviderDelegate.isInitialized()) speechSynthesisProvider.close()
        if (speechRecognitionEngineDelegate.isInitialized()) speechRecognitionEngine.close()
        if (transcriptArchiveDelegate.isInitialized()) transcriptArchive.close()
        super.onTerminate()
    }

    private companion object {
        const val LOG_TAG = "GuideCastApplication"
        const val MAX_STANDARD_NATIVE_COLD_LOADS = 2
        const val MAX_BACKEND_CLEANUP_ATTEMPTS = 2
        const val BACKEND_IDLE_RELEASE_GRACE_MILLIS = 1_500L
        const val SETTINGS_PREPARED_STANDBY_MILLIS = 5L * 60L * 1_000L
    }
}

internal class TranslationBackendUseLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/** Cancellation cannot strand the lease between a successful operation and standby ownership. */
internal suspend fun transferTranslationBackendLeaseToStandby(
    lease: TranslationBackendUseLease,
    retain: suspend (TranslationBackendUseLease) -> Unit,
) {
    try {
        withContext(NonCancellable) { retain(lease) }
    } catch (error: Throwable) {
        lease.close()
        throw error
    }
}

internal data class NativeBackendCleanup(
    val label: String,
    val cleanup: suspend () -> Unit,
)

/** One faulty provider is evidence for that provider only; siblings still release their memory. */
internal suspend fun runIsolatedNativeBackendCleanups(
    cleanups: List<NativeBackendCleanup>,
    report: (label: String, error: Throwable) -> Unit,
): Set<String> {
    val failures = linkedSetOf<String>()
    cleanups.forEach { candidate ->
        try {
            candidate.cleanup()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            coroutineContext.ensureActive()
            failures += candidate.label
            report(candidate.label, cancelled)
        } catch (error: Throwable) {
            failures += candidate.label
            report(candidate.label, error)
        }
    }
    return failures
}

/** Rechecks logical ownership after a potentially long shared-admission wait. */
internal suspend fun <T> runPreparationOwnedNativeOperation(
    isOwnerCurrent: () -> Boolean,
    ownerLostMessage: String,
    withAdmission: suspend (operation: suspend () -> T) -> T,
    operation: suspend () -> T,
): T {
    if (!isOwnerCurrent()) throw kotlinx.coroutines.CancellationException(ownerLostMessage)
    return withAdmission {
        if (!isOwnerCurrent()) throw kotlinx.coroutines.CancellationException(ownerLostMessage)
        operation()
    }
}

/** Small state machine; every method is called under GuideCastApplication's lifecycle mutex. */
internal class TranslationBackendUseState {
    private var epoch = 0L
    private var activeOwners = 0

    fun claim(): Long {
        activeOwners += 1
        epoch = epoch.nextEpoch()
        return epoch
    }

    /** Returns a cleanup generation only when the last owner releases. */
    fun release(): Long? {
        check(activeOwners > 0) { "Translation backend use lease underflow" }
        activeOwners -= 1
        if (activeOwners != 0) return null
        epoch = epoch.nextEpoch()
        return epoch
    }

    fun mayCleanup(cleanupEpoch: Long): Boolean =
        activeOwners == 0 && epoch == cleanupEpoch

    fun activeOwnerCount(): Int = activeOwners

    private fun Long.nextEpoch(): Long = if (this == Long.MAX_VALUE) 1L else this + 1L
}

internal enum class TranslationPreparationOwnerKind { SETTINGS, BROADCAST }

internal data class TranslationPreparationOwnerToken(
    val generation: Long,
    val kind: TranslationPreparationOwnerKind,
    val sourceLanguageTag: String,
    val targetLanguageTags: Set<String>,
)

/** Broadcast takeover permanently invalidates every older/in-flight settings preparation. */
internal class TranslationPreparationOwnerState {
    private val lock = Any()
    private var generation = 0L
    private var settingsOwner: TranslationPreparationOwnerToken? = null
    private var broadcastOwner: TranslationPreparationOwnerToken? = null

    fun beginSettings(
        sourceLanguageTag: String,
        targetLanguageTags: Set<String>,
    ): TranslationPreparationOwnerToken = synchronized(lock) {
        val token = newToken(
            TranslationPreparationOwnerKind.SETTINGS,
            sourceLanguageTag,
            targetLanguageTags,
        )
        // Settings opened during a live broadcast may download assets, but it never becomes a
        // native owner later merely because that broadcast stops.
        if (broadcastOwner == null) settingsOwner = token
        token
    }

    fun beginBroadcast(
        sourceLanguageTag: String,
        targetLanguageTags: Set<String>,
    ): TranslationPreparationOwnerToken = synchronized(lock) {
        settingsOwner = null
        newToken(
            TranslationPreparationOwnerKind.BROADCAST,
            sourceLanguageTag,
            targetLanguageTags,
        ).also { broadcastOwner = it }
    }

    fun isCurrent(token: TranslationPreparationOwnerToken): Boolean = synchronized(lock) {
        isCurrentLocked(token)
    }

    /** Atomically couples a short provider commit with the owner generation that authorized it. */
    fun runIfCurrent(
        token: TranslationPreparationOwnerToken,
        mutation: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!isCurrentLocked(token)) return@synchronized false
        mutation()
        true
    }

    fun currentSourceMatches(languageTag: String): Boolean = synchronized(lock) {
        val current = broadcastOwner ?: settingsOwner ?: return@synchronized false
        current.sourceLanguageTag.substringBefore('-').equals(
            languageTag.substringBefore('-'),
            ignoreCase = true,
        )
    }

    fun retainedTargetLanguages(): Set<String> = synchronized(lock) {
        (broadcastOwner ?: settingsOwner)?.targetLanguageTags.orEmpty()
    }

    fun end(token: TranslationPreparationOwnerToken) = synchronized(lock) {
        when (token.kind) {
            TranslationPreparationOwnerKind.SETTINGS -> if (settingsOwner == token) {
                settingsOwner = null
            }
            TranslationPreparationOwnerKind.BROADCAST -> if (broadcastOwner == token) {
                broadcastOwner = null
            }
        }
    }

    private fun isCurrentLocked(token: TranslationPreparationOwnerToken): Boolean =
        when (token.kind) {
            TranslationPreparationOwnerKind.SETTINGS ->
                broadcastOwner == null && settingsOwner == token
            TranslationPreparationOwnerKind.BROADCAST -> broadcastOwner == token
        }

    private fun newToken(
        kind: TranslationPreparationOwnerKind,
        sourceLanguageTag: String,
        targetLanguageTags: Set<String>,
    ): TranslationPreparationOwnerToken {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
        return TranslationPreparationOwnerToken(
            generation = generation,
            kind = kind,
            sourceLanguageTag = sourceLanguageTag,
            targetLanguageTags = targetLanguageTags.toSet(),
        )
    }
}
