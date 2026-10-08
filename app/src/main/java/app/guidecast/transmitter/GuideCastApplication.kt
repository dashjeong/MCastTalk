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
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
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
                provider.modelManager.status.map(::gemmaModelDiagnostic)
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
            recognitionHints = { language ->
                (speechCorrections.recognitionHints(language) + sentenceTranslationMemory.recognitionHints(language))
                    .distinct().take(32)
            },
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
    internal val webBroadcastOwnership = WebBroadcastOwnership()
    internal val menuBroadcast by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MenuBroadcastController(this) }
    internal val recordings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { BroadcastRecordingRepository(this) }
    internal val recordedPlayback = RecordedPlaybackOwnership()
    internal val translationWorkspaceActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val transcriptArchiveDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BroadcastTranscriptArchive(this)
    }
    val transcriptArchive by transcriptArchiveDelegate
    val translationDiagnostics by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TranslationSessionDiagnostics(this)
    }
    val glossary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TranslationGlossaryRepository(this) }
    val speechCorrections by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SpeechCorrectionRepository(this) }
    val uiDisplaySettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { UiDisplaySettings(this) }
    val operatorSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OperatorSettings(this) }
    internal val interpreterRelaySettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { InterpreterRelaySettings(this) }
    internal val nativeLearningMonitor = NativeLearningMonitor()
    private val nativeLearningDispatcherDelegate = lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run() },
                "mcasttalk-native-comparison").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
    private val nativeLearningScopeDelegate = lazy {
        CoroutineScope(SupervisorJob() + nativeLearningDispatcherDelegate.value)
    }
    val developerLabSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DeveloperLabSettings(this) }
    internal val geminiLiveMonitor = GeminiLiveMonitor()
    val translationApiSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TranslationApiSettings(this) }
    internal val commonServiceApiSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TranslationApiSettings(this, "translation_api_common_defaults", TranslationApiOptions())
    }
    internal val serviceDefaults by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ServiceDefaults(this) }
    internal val commonServiceApiService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TranslationApiService(commonServiceApiSettings, shadowAllowed = { false }, comparisonResources = { false })
    }
    internal val serviceMenuProfiles by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ServiceMenuProfiles(this, commonServiceApiSettings)
    }
    internal val automaticTranslationExamples: AutomaticTranslationExamples by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val preferences = getSharedPreferences("automatic_translation_examples", MODE_PRIVATE)
        AutomaticTranslationExamples(CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
            AutomaticExampleFile(java.io.File(filesDir, "automatic_translation_examples.json")),
            idle = {
                val runtime = broadcastRuntime.state.value
                !webBroadcastOwnership.isOwned && runtime.phase == BroadcastPhase.IDLE &&
                    runtime.inputPhase == InputPhase.IDLE && !runtime.inputStopping &&
                    !localFileWorkActive.value && !localVoiceNoteWorkActive.value && !localModelWorkActive.value
            }, currentDomain = domainCorpus::automaticExampleDomain,
            initialEnabled = preferences.getBoolean("enabled", false),
            persistEnabled = { preferences.edit().putBoolean("enabled", it).commit() },
            onDisabled = {
                if (translationApiSettings.deferredTeacher.value != null) deferredNativeTeacher.stop()
            })
    }
    val translationApiService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { TranslationApiService(translationApiSettings, shadowAllowed = { target ->
        learningResourcesAvailable() && translationProvider.hasActivePreparedWorker(target)
    }, comparisonResources = ::learningResourcesAvailable, automaticExamples = automaticTranslationExamples,
        exampleDomain = domainCorpus::automaticExampleDomain, comparisonLifetime = ::comparisonWorkLifetime) }
    internal fun comparisonWorkLifetime(): String = listOf(broadcastRuntime.learningActivityEpoch,
        fileWorkOwners.generation, voiceNoteWorkOwners.generation, preparationOwners.currentGeneration).joinToString(":")
    val sentenceTranslationMemory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SentenceTranslationMemory(this) }
    val domainCorpus by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DomainCorpusRepository(this) }
    val cloudTranslationReviewer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CloudTranslationReviewer(developerLabSettings, uiDisplaySettings, sentenceTranslationMemory) {
            // Paid comparison uses the selected TranslationApiService with its shared budget.
            // Legacy reviewer credentials cannot create an unmetered second provider request.
            false
        }
    }
    internal fun learningResourcesAvailable(): Boolean {
        val power = getSystemService(android.os.PowerManager::class.java) ?: return false
        if (power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE) return false
        val manager = getSystemService(android.app.ActivityManager::class.java) ?: return false
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return !info.lowMemory && info.availMem > maxOf(info.threshold, 512L * 1024 * 1024)
    }

    internal val reviewedRelayComparison by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ReviewedRelayComparisonController(nativeLearningScopeDelegate.value,
            environment = { source, target -> reviewedRelayComparisonEnvironment(source, target) },
            compare = { request, expected, current ->
                check(current())
                val lease = tryAcquireReviewedRelayBackendUse(current)
                if (lease == null) null else try {
                    check(current())
                    app.guidecast.core.translation.requireProtectedTranslationMeaning(
                        request.original, request.translation, request.source, request.target)
                    val captured = DomainCorpusTranslationEngine(gemmaTranslationProvider.preparedEngineFor(request.target), domainCorpus)
                        .capture(request.original, request.source, request.target, request.style)
                    check(current() && captured.capturedRevision == expected.corpusRevision)
                    val offline = withContext(app.guidecast.core.translation.TranslationStyleContext(request.style)) {
                        captured.translateWithContext(request.original, null, request.source, request.target)
                    }
                    check(current())
                    ShadowComparison(request.original, null, request.source, request.target,
                        expected.corpusRevision, request.translation, offline, request.style,
                        offlineModel = if (captured.capturedExactMatch) "검수한 번역 예문 재사용" else expected.modelId)
                } finally { withContext(NonCancellable) { lease.close() } }
            }, commitLocks = listOf(interpreterRelaySettings, translationApiSettings, preparationOwners.reviewLock))
    }

    private fun reviewedRelayComparisonEnvironment(source: String, target: String): ReviewedRelayComparisonEnvironment {
        val prepared = gemmaTranslationProviderDelegate.isInitialized() && gemmaTranslationProvider.hasActivePreparedWorker()
        val broadcast = broadcastRuntime.state.value
        return ReviewedRelayComparisonEnvironment(domainCorpus.revision.value,
            if (prepared) gemmaTranslationProvider.modelManager.selectedVariant.id else null,
            preparationOwners.currentGeneration, broadcastRuntime.inputRequestEpoch,
            translationApiSettings.state.value.revision, interpreterRelaySettings.comparisonGeneration,
            prepared, GemmaTranslationProvider.supportsTranslation(source, target),
            webBroadcastOwnership.isOwned || broadcast.phase != BroadcastPhase.IDLE ||
                broadcast.inputPhase != InputPhase.IDLE || broadcast.inputStopping,
            localFileWorkActive.value || localVoiceNoteWorkActive.value || localModelWorkActive.value,
            learningResourcesAvailable())
    }

    /** Optional local comparison never waits for or replaces a native backend owner. */
    private fun tryAcquireReviewedRelayBackendUse(current: () -> Boolean): ReviewedRelayBackendUseLease? {
        if (!nativeEngineLifecycleMutex.tryLock()) return null
        return try {
            val standbyOwners = if (settingsStandbyLease != null) 1 else 0
            if (!current() || !backendUseState.mayClaimReviewedComparison(standbyOwners)) null else {
                pendingBackendCleanup?.cancel()
                pendingBackendCleanup = null
                backendUseState.claim()
                ReviewedRelayBackendUseLease(::releaseTranslationBackendUseAndAwait)
            }
        } finally { nativeEngineLifecycleMutex.unlock() }
    }

    private val mutableDeferredTeacherUsage = kotlinx.coroutines.flow.MutableStateFlow(DeferredTeacherRequestUsage())
    internal val deferredTeacherRequestUsage = mutableDeferredTeacherUsage.asStateFlow()
    @Synchronized private fun updateDeferredTeacherUsage(attempted: Boolean, raw: GeminiBatchUsage,
        knownUsd: String, heldUsd: String) {
        val before = mutableDeferredTeacherUsage.value
        mutableDeferredTeacherUsage.value = before.copy(dispatchedRequests = before.dispatchedRequests + if (attempted) 1 else 0,
            lastPrompt = raw.prompt, lastCandidates = raw.candidates, lastThoughts = raw.thoughts,
            lastTotal = raw.total, lastTotalsMatch = raw.totalsMatch, budgetKnownUsd = knownUsd, budgetHeldUsd = heldUsd)
    }
    private val mutableDeferredTeacherComparison = kotlinx.coroutines.flow.MutableStateFlow(DeferredTeacherStatus())
    internal val deferredTeacherComparisonStatus = mutableDeferredTeacherComparison.asStateFlow()
    private fun deferredTeacherEnvironment(): DeferredTeacherFence {
        val permit = translationApiSettings.deferredTeacher.value
        val runtime = broadcastRuntime.state.value
        val prepared = automaticTranslationExamples.state.value.enabled && automaticTranslationExamples.state.value.ready &&
            gemmaTranslationProviderDelegate.isInitialized() && gemmaTranslationProvider.hasActivePreparedWorker() &&
            permit?.targets?.all { GemmaTranslationProvider.supportsTranslation("ko", it) } == true
        return DeferredTeacherFence(translationApiSettings.state.value.revision, domainCorpus.revision.value,
            preparationOwners.currentGeneration, broadcastRuntime.inputRequestEpoch, permit?.generation ?: -1,
            !webBroadcastOwnership.isOwned && runtime.phase == BroadcastPhase.IDLE && runtime.inputPhase == InputPhase.IDLE &&
                !runtime.inputStopping && !localFileWorkActive.value && !localVoiceNoteWorkActive.value && !localModelWorkActive.value,
            prepared, learningResourcesAvailable(), comparisonWorkLifetime())
    }
    private val deferredTeacherComparison: DeferredNativeTeacherComparison by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DeferredNativeTeacherComparison(nativeLearningScopeDelegate.value, ::deferredTeacherEnvironment,
            grantCurrent = { grant -> automaticTranslationExamples.state.value.enabled &&
                automaticTranslationExamples.state.value.ready && translationApiSettings.deferredTeacher.value?.let { permit ->
                permit.generation == grant.generation && permit.targets == grant.targets &&
                    translationApiSettings.deferredTeacherAuthorized(permit) } == true },
            sourceTextValid = ::reviewedRelayExampleTextValid,
            targetValid = { GemmaTranslationProvider.supportsTranslation("ko", it) },
            teacher = { source, grant, allowed ->
                val permit = requireNotNull(translationApiSettings.deferredTeacher.value)
                requestDeferredGoogleTeacher(translationApiSettings, permit, source, grant, allowed,
                    usageObserved = ::updateDeferredTeacherUsage)
            }, offline = { source, target, allowed ->
                check(allowed())
                // The workflow owns one idle backend lease across ASR, teacher request and this inference.
                val permit = requireNotNull(translationApiSettings.deferredTeacher.value)
                val captured = DomainCorpusTranslationEngine(gemmaTranslationProvider.preparedEngineFor(target), domainCorpus)
                    .capture(source.original, source.source, target, permit.textOptions.tone)
                check(allowed())
                withContext(app.guidecast.core.translation.TranslationStyleContext(permit.textOptions.tone)) {
                    captured.translateWithContext(source.original, null, source.source, target)
                }.also { check(allowed()) }
            }, protectedPairAccepted = { original, offline, reference, target ->
                conservativeReviewAccepted(original, offline, reference, target)
            }, offerForReview = { source, teacher, local, allowed ->
                val permit = requireNotNull(translationApiSettings.deferredTeacher.value)
                val domain = domainCorpus.automaticExampleDomain()
                check(allowed())
                teacher.targets.forEach { target ->
                    check(allowed())
                    automaticTranslationExamples.offer(ShadowComparison(source.original, null, source.source, target,
                        domain.first, teacher.translations.getValue(target), local.getValue(target), permit.textOptions.tone,
                        nativeIdentity = null, offlineModel = "기기 내 준비된 오프라인 모델"),
                        domain.second, automaticExampleInstructions(permit.textOptions), allowed)
                }
            }, statusChanged = { mutableDeferredTeacherComparison.value = it }, externallyHeldLocalLease = true)
    }
    /** Opt-in idle work may rebind only an already installed, runtime-verified selected model. */
    private suspend fun prepareDeferredExistingGemma(targets: List<String>, allowed: () -> Boolean): Boolean {
        if (!allowed() || !gemmaTranslationProviderDelegate.isInitialized() || targets.isEmpty()) return false
        val provider = gemmaTranslationProvider
        return try {
            kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                provider.modelManager.withSelectedModel {
                    val manager = provider.modelManager
                    if (!allowed() || !manager.hasRuntimeVerifiedModelFile() ||
                        targets.any { !GemmaTranslationProvider.supportsTranslation("ko", it) }) return@withSelectedModel false
                    if (!provider.hasActivePreparedWorker()) {
                        withProcessNativeColdLoadLease(ProcessNativeColdLoadKeys.GEMMA_MODEL,
                            serializeWithAllColdLoads = true) {
                            check(allowed() && manager.hasRuntimeVerifiedModelFile())
                            provider.warmup(targetLanguageTag = targets.first(), sourceLanguageTag = "ko",
                                timeoutMillis = 120_000L)
                            check(allowed())
                        }
                    }
                    allowed() && manager.hasRuntimeVerifiedModelFile() && provider.hasActivePreparedWorker()
                }
            } == true
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    internal val deferredNativeTeacher: DeferredNativeTeacherWorkflow by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DeferredNativeTeacherWorkflow(this, nativeLearningScopeDelegate.value, translationApiSettings,
            ::deferredTeacherEnvironment, recordings.audio::snapshot, recordings::flush,
            localLease = { allowed -> tryAcquireReviewedRelayBackendUse(allowed)?.let { lease ->
                object : DeferredTeacherLocalLease { override suspend fun close() { lease.close() } }
            } }, comparisons = deferredTeacherComparison,
            prepareExistingModel = ::prepareDeferredExistingGemma)
    }
    /** Separate session opt-in. This does not replace the live provider or start input/broadcast/model preparation. */
    internal fun beginDeferredNativeTeacher(selectedText: TranslationApiOptions, targets: List<String>,
        agreeToTextAndCost: Boolean): Boolean {
        if (!automaticTranslationExamples.state.value.enabled || !automaticTranslationExamples.state.value.ready) return false
        val permit = translationApiSettings.beginDeferredTeacher(selectedText, targets, agreeToTextAndCost) ?: return false
        if (deferredNativeTeacher.begin(permit)) {
            mutableDeferredTeacherUsage.value = DeferredTeacherRequestUsage()
            return true
        }
        translationApiSettings.endDeferredTeacher()
        return false
    }

    internal fun createNativeLearningSession(sessionId: Long, options: TranslationApiOptions,
        source: String, target: String, captureStartsAfterAdmission: Boolean, isCurrent: () -> Boolean): NativeLearningSession? =
        nativeLearningSessionFor(options, sessionId, interpreterRelaySettings.state.value.compareOffline, nativeLearningMonitor) {
        fun ready(): NativeLearningPause? = when {
            localFileWorkActive.value || localVoiceNoteWorkActive.value || localModelWorkActive.value -> NativeLearningPause.BUSY
            !gemmaTranslationProviderDelegate.isInitialized() || !gemmaTranslationProvider.hasActivePreparedWorker() ||
                !GemmaTranslationProvider.supportsTranslation(source, target) -> NativeLearningPause.PREPARATION
            !learningResourcesAvailable() -> NativeLearningPause.RESOURCES
            else -> null
        }
        fun allowed() = isCurrent() && interpreterRelaySettings.state.value.compareOffline &&
            translationApiSettings.authorized(options) && options.allowLiveAudio
        NativeLearningSession(nativeLearningScopeDelegate.value, sessionId, nativeLearningMonitor,
            enabled = { interpreterRelaySettings.state.value.compareOffline },
            authorized = { translationApiSettings.authorized(options) && options.allowLiveAudio },
            isCurrent = isCurrent, controlGeneration = { interpreterRelaySettings.comparisonGeneration },
            corpusRevision = { domainCorpus.revision.value }, readiness = ::ready,
            captureStartsAfterAdmission = captureStartsAfterAdmission,
            compare = { pair ->
                check(allowed() && ready() == null)
                val lease = requireNotNull(acquireTranslationBackendUseIf(::allowed))
                try {
                    check(allowed() && ready() == null)
                    app.guidecast.core.translation.requireProtectedTranslationMeaning(pair.original, pair.translation, source, target)
                    val model = gemmaTranslationProvider.modelManager.selectedVariant.id
                    val captured = DomainCorpusTranslationEngine(gemmaTranslationProvider.preparedEngineFor(target), domainCorpus)
                        .capture(pair.original, source, target, options.tone)
                    check(captured.capturedRevision == pair.corpusRevision)
                    val offline = withContext(app.guidecast.core.translation.TranslationStyleContext(options.tone)) {
                        captured.translateWithContext(pair.original, null, source, target)
                    }
                    check(gemmaTranslationProvider.modelManager.selectedVariant.id == model)
                    ShadowComparison(pair.original, null, source, target, pair.corpusRevision, pair.translation, offline,
                        options.tone, NativeComparisonIdentity(sessionId, pair.inputId, pair.responseId, pair.sequence,
                            options.revision, options.provider, options.model, pair.controlGeneration),
                        if (captured.capturedExactMatch) "검수한 번역 예문 재사용" else model)
                } finally { lease.close() }
            })
    }
    internal fun isNativeComparisonCurrent(candidate: ShadowComparison): Boolean {
        val api = translationApiSettings.state.value
        return nativeComparisonIsCurrent(candidate, nativeLearningMonitor.ownsCandidate(candidate),
            interpreterRelaySettings.state.value, interpreterRelaySettings.comparisonGeneration, api,
            translationApiSettings.authorized(api), domainCorpus.revision.value)
    }
    internal fun nativeComparisonCommitAdmission(candidate: ShadowComparison) = NativeComparisonCommitAdmission(
        listOf(nativeLearningMonitor.reviewAdmissionLock, interpreterRelaySettings, translationApiSettings)) { isNativeComparisonCurrent(candidate) }
    val microphoneNoiseSettings by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { MicrophoneNoiseSettings(this) }
    val diagnosticExportState = kotlinx.coroutines.flow.MutableStateFlow(DiagnosticExportState())
    internal val fileWorkOwners = LocalWorkOwners()
    val localFileWorkActive = fileWorkOwners.active
    internal val voiceNoteWorkOwners = LocalWorkOwners()
    val localVoiceNoteWorkActive = voiceNoteWorkOwners.active
    val localModelWorkActive = kotlinx.coroutines.flow.MutableStateFlow(false)

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
        nativeEngineScope.launch { releaseTranslationBackendUseAndAwait() }
    }

    private suspend fun releaseTranslationBackendUseAndAwait() {
        nativeEngineLifecycleMutex.withLock {
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
            nativeEngineScope.launch(Dispatchers.IO) { runCatching { recordedShareStore(this@GuideCastApplication).cleanup() } }
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
                audioCaptureEngine.diagnostics.collect { snapshot ->
                    RuntimeDiagnosticLog.record("capture_format", snapshot.toString())
                }
            }
            nativeEngineScope.launch {
                // Per-sentence worker transitions otherwise rotate hours of diagnostic history
                // away. The periodic session heartbeat carries their numeric progress/failures.
                broadcastRuntime.state.map(::broadcastControlDiagnostic)
                    .distinctUntilChanged().collect { RuntimeDiagnosticLog.record("broadcast_state", it) }
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
        if (nativeLearningScopeDelegate.isInitialized()) nativeLearningScopeDelegate.value.cancel()
        if (nativeLearningDispatcherDelegate.isInitialized()) nativeLearningDispatcherDelegate.value.close()
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

/** Reviewed work waits for its use counter to release before the next optional comparison. */
private class ReviewedRelayBackendUseLease(private val release: suspend () -> Unit) {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    suspend fun close() {
        if (closed.compareAndSet(false, true)) release()
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

    /** A retained warm settings lease owns memory, not an active inference. */
    fun mayClaimReviewedComparison(standbyOwners: Int): Boolean =
        standbyOwners in 0..1 && activeOwners == standbyOwners

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
    internal val currentGeneration: Long get() = synchronized(lock) { generation }
    internal val reviewLock: Any get() = lock
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
