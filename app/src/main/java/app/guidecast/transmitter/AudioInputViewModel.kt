package app.guidecast.transmitter

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputPreference
import app.guidecast.core.audio.AudioInputRepository
import app.guidecast.core.audio.AudioOutputDevice
import app.guidecast.core.audio.PlaybackCaptureDiagnostics
import app.guidecast.core.audio.PcmSignalStats
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.translation.LanguageModelStatus
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.core.stream.MAX_SIMULTANEOUS_TRANSLATED_CHANNELS
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelStatus
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.gemma.translation.GemmaCatalogStore
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import app.guidecast.provider.moonshine.tts.MoonshineTtsStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class AudioInputUiState(
    val availableDevices: List<AudioInputDevice> = emptyList(),
    val availableOutputDevices: List<AudioOutputDevice> = emptyList(),
    val preference: AudioInputPreference = AudioInputPreference.Automatic,
    val selectedDevice: AudioInputDevice? = null,
    val playbackCaptureDiagnostics: PlaybackCaptureDiagnostics = PlaybackCaptureDiagnostics(),
    val playbackTargetApps: List<PlaybackTargetApp> = emptyList(),
    val selectedPlaybackTarget: PlaybackTargetApp? = null,
    val playbackTargetRegistrationMessage: String? = null,
)

private data class PlaybackTargetUiState(
    val apps: List<PlaybackTargetApp>,
    val selected: PlaybackTargetApp?,
    val registrationMessage: String?,
)

private data class AudioDeviceUiState(
    val inputs: List<AudioInputDevice>,
    val outputs: List<AudioOutputDevice>,
)

data class TranslationLanguageOption(
    val languageTag: String,
    val label: String,
)

data class SourceLanguageOption(
    val languageTag: String,
    val label: String,
)

private data class TranslationLanguageSelectionUiState(
    val sourceLanguageTag: String,
    val targetLanguageTags: Set<String>,
)

data class TranslationModelUiState(
    val sourceOptions: List<SourceLanguageOption> = SOURCE_LANGUAGE_OPTIONS,
    val selectedSourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
    val options: List<TranslationLanguageOption> =
        translationTargetLanguageOptions(DEFAULT_SOURCE_LANGUAGE_TAG),
    val selectedLanguageTags: Set<String> = setOf("en"),
    val statuses: List<LanguageModelStatus> = emptyList(),
    val isBusy: Boolean = false,
    val operationLabel: String = "모델 상태 확인 중",
    val operationProgress: String? = null,
    val isCancelling: Boolean = false,
    val message: String? = null,
    val speechRecognitionAvailable: Boolean = false,
    val speechRecognitionReady: Boolean = false,
    val speechRecognitionReason: String? = null,
    val broadcastTranslationEnabled: Boolean = true,
    val useGemma: Boolean = true,
    val selectiveTranslationRefinement: Boolean = false,
    val gemmaReady: Boolean = false,
    val ttsStatuses: List<MoonshineTtsStatus> = emptyList(),
    val ttsFallbackLanguageTags: Set<String> = emptySet(),
    val ttsUnavailableLanguageReasons: Map<String, String> = emptyMap(),
    val voicePreferences: Map<String, SpeechVoicePreference> = emptyMap(),
    val resourceDiagnostics: MultiLanguageResourceDiagnostics? = null,
) {
    fun readiness(languageTag: String): ModelReadiness =
        statuses.firstOrNull { it.languageTag == languageTag }?.readiness
            ?: ModelReadiness.NOT_INSTALLED

    fun ttsReadiness(languageTag: String): MoonshineTtsReadiness =
        ttsStatuses.firstOrNull { it.languageTag == languageTag }?.readiness
            ?: MoonshineTtsReadiness.NOT_INSTALLED

    fun ttsFallbackReady(languageTag: String): Boolean =
        languageTag in ttsFallbackLanguageTags

    fun ttsUnavailableReason(languageTag: String): String? =
        ttsUnavailableLanguageReasons[languageTag]

    fun ttsReady(languageTag: String): Boolean =
        ttsUnavailableReason(languageTag) == null &&
            (ttsReadiness(languageTag) == MoonshineTtsReadiness.READY ||
                ttsFallbackReady(languageTag))

    val selectedTtsReady: Boolean
        get() = selectedLanguageTags.isNotEmpty() && selectedLanguageTags.all {
            ttsReady(it)
        }

    val selectedSourceLanguage: SourceLanguageOption
        get() = sourceOptions.firstOrNull { it.languageTag == selectedSourceLanguageTag }
            ?: SourceLanguageOption(
                selectedSourceLanguageTag,
                selectedSourceLanguageTag.sourceLanguageDisplayName(),
            )
}

private data class TranslationControlState(
    val message: String?,
    val translationEnabled: Boolean,
    val speechReady: Boolean,
    val speechMessage: String?,
    val useGemma: Boolean,
    val selectiveTranslationRefinement: Boolean,
    val gemmaReady: Boolean,
)

private data class SpeechSynthesisUiState(
    val statuses: List<MoonshineTtsStatus>,
    val fallbackLanguageTags: Set<String>,
    val unavailableLanguageReasons: Map<String, String>,
    val voicePreferences: Map<String, SpeechVoicePreference>,
)

data class GemmaUiState(
    val model: GemmaModelStatus = GemmaModelStatus(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val useForTranslation: Boolean = true,
    val selectiveTranslationRefinement: Boolean = false,
    val broadcastCapable: Boolean = true,
    val broadcastCapabilityMessage: String? = null,
    val appliedVariant: GemmaModelVariant = GemmaModelVariant.STANDARD,
    val availableModels: List<GemmaModelVariant> = GemmaModelVariant.entries,
)

private data class GemmaRuntimeTestResult(
    val translation: String,
    val rms: Float,
    val peak: Float,
    val ttsDegradedReason: String? = null,
)

class AudioInputViewModel(application: Application) : AndroidViewModel(application) {
    private val guideCastApplication = application as GuideCastApplication
    private val repository: AudioInputRepository = guideCastApplication.audioInputRepository
    private val modelManager = guideCastApplication.translationProvider.modelManager
    private val gemmaProvider = guideCastApplication.gemmaTranslationProvider
    private val gemmaBroadcastCapability = GemmaBroadcastCapability.detect(application)
    // Set remains part of the public UI contract, but every produced instance is insertion
    // ordered. The first selected output is the operator's quality-priority channel.
    private val selectedLanguageTags = MutableStateFlow<Set<String>>(linkedSetOf("en"))
    private val selectedSourceLanguageTag = MutableStateFlow(DEFAULT_SOURCE_LANGUAGE_TAG)
    private val modelBusy = MutableStateFlow(false)
    private data class ModelProgress(
        val label: String = "모델 상태 확인 중",
        val details: String? = null,
        val cancelling: Boolean = false,
    )
    private val modelProgress = MutableStateFlow(ModelProgress())
    private val modelMessage = MutableStateFlow<String?>(null)
    private val translationBroadcastEnabled = MutableStateFlow(true)
    private val useGemma = MutableStateFlow(true)
    private val selectiveTranslationRefinement = MutableStateFlow(false)
    private val gemmaBusy = MutableStateFlow(false)
    private val gemmaMessage = MutableStateFlow<String?>(null)
    private val speechRecognitionReady = MutableStateFlow(false)
    private val speechRecognitionMessage = MutableStateFlow(
        guideCastApplication.speechRecognitionEngine.capability(DEFAULT_SOURCE_LANGUAGE_TAG).reason,
    )
    private val playbackTargetApps = MutableStateFlow(application.playbackTargetApps())
    private val selectedPlaybackTarget = MutableStateFlow<PlaybackTargetApp?>(null)
    private val playbackTargetRegistrationMessage = MutableStateFlow<String?>(null)
    private val deviceMemorySnapshot = MutableStateFlow(
        DeviceMemorySnapshot.detect(application),
    )
    private val appProcessMemorySnapshot = MutableStateFlow(
        initialAppProcessMemorySnapshot(),
    )
    private var modelJob: Job? = null
    private var gemmaJob: Job? = null

    private val gemmaControl = combine(
        useGemma,
        selectiveTranslationRefinement,
        gemmaProvider.modelManager.status,
    ) { enabled, refinement, status ->
        Triple(enabled, refinement, status.readiness == GemmaModelReadiness.READY)
    }

    private val translationControls = combine(
        modelMessage,
        translationBroadcastEnabled,
        speechRecognitionReady,
        speechRecognitionMessage,
        gemmaControl,
    ) { message, enabled, speechReady, speechMessage, gemma ->
        TranslationControlState(
            message = message,
            translationEnabled = enabled,
            speechReady = speechReady,
            speechMessage = speechMessage,
            useGemma = gemma.first,
            selectiveTranslationRefinement = gemma.second,
            gemmaReady = gemma.third,
        )
    }

    private val speechSynthesisState = combine(
        guideCastApplication.speechSynthesisProvider.statuses,
        guideCastApplication.speechSynthesisProvider.fallbackLanguageTags,
        guideCastApplication.speechSynthesisProvider.unavailableLanguageReasons,
        guideCastApplication.speechSynthesisProvider.voicePreferences,
        ::SpeechSynthesisUiState,
    )

    private val playbackTargetState = combine(
        playbackTargetApps,
        selectedPlaybackTarget,
        playbackTargetRegistrationMessage,
        ::PlaybackTargetUiState,
    )

    private val languageSelection = combine(
        selectedSourceLanguageTag,
        selectedLanguageTags,
        ::TranslationLanguageSelectionUiState,
    )

    private val audioDeviceState = combine(
        repository.availableDevices,
        repository.availableOutputDevices,
        ::AudioDeviceUiState,
    )

    val uiState: StateFlow<AudioInputUiState> = combine(
        audioDeviceState,
        repository.preference,
        repository.selectedDevice,
        repository.playbackCaptureDiagnostics,
        playbackTargetState,
    ) { devices, preference, selected, playbackDiagnostics, target ->
        AudioInputUiState(
            availableDevices = devices.inputs,
            availableOutputDevices = devices.outputs,
            preference = preference,
            selectedDevice = selected,
            playbackCaptureDiagnostics = playbackDiagnostics,
            playbackTargetApps = target.apps,
            selectedPlaybackTarget = target.selected,
            playbackTargetRegistrationMessage = target.registrationMessage,
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AudioInputUiState(),
    )

    val broadcastState: StateFlow<BroadcastSnapshot> =
        guideCastApplication.broadcastRuntime.state

    val transcriptArchiveState: StateFlow<TranscriptArchiveSnapshot> =
        guideCastApplication.transcriptArchive.snapshot

    private val translationModelBaseState = combine(
        languageSelection,
        modelManager.statuses,
        combine(modelBusy, modelProgress) { busy, progress -> busy to progress },
        translationControls,
        speechSynthesisState,
    ) { selection, statuses, busy, controls, speechSynthesis ->
        val sourceCapability = guideCastApplication.speechRecognitionEngine.capability(
            selection.sourceLanguageTag,
        )
        TranslationModelUiState(
            selectedSourceLanguageTag = selection.sourceLanguageTag,
            options = translationTargetLanguageOptions(selection.sourceLanguageTag),
            selectedLanguageTags = selection.targetLanguageTags,
            statuses = statuses,
            isBusy = busy.first,
            operationLabel = busy.second.label,
            operationProgress = busy.second.details,
            isCancelling = busy.second.cancelling,
            message = controls.message,
            speechRecognitionAvailable = sourceCapability.available,
            speechRecognitionReady = controls.speechReady,
            speechRecognitionReason = controls.speechMessage,
            broadcastTranslationEnabled = controls.translationEnabled,
            useGemma = controls.useGemma,
            selectiveTranslationRefinement = controls.selectiveTranslationRefinement,
            gemmaReady = controls.gemmaReady,
            ttsStatuses = speechSynthesis.statuses,
            ttsFallbackLanguageTags = speechSynthesis.fallbackLanguageTags,
            ttsUnavailableLanguageReasons = speechSynthesis.unavailableLanguageReasons,
            voicePreferences = speechSynthesis.voicePreferences,
        )
    }

    val translationModelState: StateFlow<TranslationModelUiState> = combine(
        translationModelBaseState,
        deviceMemorySnapshot,
        appProcessMemorySnapshot,
    ) { state, memory, appProcessMemory ->
        val translationReady = state.statuses.asSequence()
            .filter { it.readiness == ModelReadiness.READY }
            .mapTo(linkedSetOf()) { it.languageTag }
        val synthesisReady = state.ttsStatuses.asSequence()
            .filter { it.readiness == MoonshineTtsReadiness.READY }
            .mapTo(linkedSetOf()) { it.languageTag }
        state.copy(
            resourceDiagnostics = MultiLanguageResourcePlanner.evaluate(
                memory = memory,
                appProcessMemory = appProcessMemory,
                input = MultiLanguageResourceInput(
                    selectedLanguageTags = state.selectedLanguageTags.toList(),
                    useGemma = state.useGemma,
                    gemmaModelReady = state.gemmaReady,
                    gemmaWorkerPrepared = gemmaProvider.hasActivePreparedWorker(),
                    speechRecognitionReady = state.speechRecognitionReady,
                    translationReadyLanguageTags = translationReady,
                    synthesisReadyLanguageTags = synthesisReady,
                    synthesisFallbackReadyLanguageTags = state.ttsFallbackLanguageTags,
                ),
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TranslationModelUiState(
            speechRecognitionAvailable = guideCastApplication.speechRecognitionEngine
                .capability(DEFAULT_SOURCE_LANGUAGE_TAG).available,
            speechRecognitionReady = false,
            speechRecognitionReason = guideCastApplication.speechRecognitionEngine
                .capability(DEFAULT_SOURCE_LANGUAGE_TAG).reason,
        ),
    )

    private val gemmaCatalogEntries = MutableStateFlow(
        runCatching { GemmaCatalogStore(getApplication()).entries() }.getOrDefault(GemmaModelVariant.entries),
    )
    private val gemmaModelControls = combine(
        gemmaProvider.modelManager.status,
        GemmaModelService.preparing,
        GemmaModelService.preparationStatus,
        GemmaModelService.preparationMessage,
    ) { applied, preparing, candidate, message ->
        GemmaUiState(model = candidate ?: applied, appliedVariant = gemmaProvider.modelManager.appliedVariant,
            isBusy = preparing, message = message)
    }
    val gemmaState: StateFlow<GemmaUiState> = combine(
        gemmaModelControls,
        gemmaBusy,
        gemmaMessage,
        combine(useGemma, selectiveTranslationRefinement) { enabled, refinement ->
            enabled to refinement
        },
        gemmaCatalogEntries,
    ) { controls, busy, message, gemmaPreferences, entries ->
        GemmaUiState(
            model = controls.model,
            appliedVariant = controls.appliedVariant,
            availableModels = entries,
            isBusy = busy || controls.isBusy,
            message = message ?: controls.message,
            useForTranslation = gemmaPreferences.first,
            selectiveTranslationRefinement = gemmaPreferences.second,
            broadcastCapable = gemmaBroadcastCapability.supported,
            broadcastCapabilityMessage = gemmaBroadcastCapability.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GemmaUiState(
            broadcastCapable = gemmaBroadcastCapability.supported,
            broadcastCapabilityMessage = gemmaBroadcastCapability.message,
        ),
    )

    init {
        repository.start()
        viewModelScope.launch {
            while (isActive) {
                val detected = DeviceMemorySnapshot.detect(getApplication())
                if (detected.materiallyDiffersFrom(deviceMemorySnapshot.value)) {
                    deviceMemorySnapshot.value = detected
                }
                delay(RESOURCE_DIAGNOSTIC_REFRESH_MILLIS)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val detected = AppProcessMemorySnapshot.detect(getApplication())
                if (detected.materiallyDiffersFrom(appProcessMemorySnapshot.value)) {
                    appProcessMemorySnapshot.value = detected
                }
                delay(APP_PROCESS_MEMORY_REFRESH_MILLIS)
            }
        }
        viewModelScope.launch {
            guideCastApplication.speechRecognitionEngine.status.collectLatest { status ->
                speechRecognitionReady.value = status.isReady
                speechRecognitionMessage.value = status.message
            }
        }
        runModelOperation(timeoutMillis = 30_000L) {
            val owner = guideCastApplication.beginSettingsPreparation(
                sourceLanguageTag = selectedSourceLanguageTag.value,
                targetLanguageTags = selectedLanguageTags.value,
            )
            try {
                guideCastApplication.withTranslationBackendUse {
                    if (!guideCastApplication.isPreparationCurrent(owner)) {
                        return@withTranslationBackendUse
                    }
                    guideCastApplication.selectTranslationSource(owner)
                    inspectModelStatus("번역 모델 목록") {
                        guideCastApplication.refreshTranslationModels(
                            translationTargetLanguageOptions(selectedSourceLanguageTag.value)
                                .map { it.languageTag }.toSet(), owner,
                        )
                    }
                    if (guideCastApplication.isPreparationCurrent(owner)) {
                        inspectModelStatus("음성인식") { refreshSpeechRecognitionStatus() }
                        inspectModelStatus("Gemma 파일") {
                            withContext(Dispatchers.IO) { gemmaProvider.modelManager.refresh() }
                        }
                    }
                }
            } finally {
                guideCastApplication.endPreparation(owner)
            }
        }
    }

    fun refresh() {
        repository.refresh()
        val current = selectedPlaybackTarget.value
        val refreshed = getApplication<Application>().playbackTargetApps()
        playbackTargetApps.value = refreshed
        selectedPlaybackTarget.value = current?.let { selected ->
            refreshed.firstOrNull { it.packageName == selected.packageName }
        }
    }

    fun useAutomaticSelection() = repository.useAutomaticSelection()

    fun selectDevice(platformId: Int) = repository.selectDevice(platformId)

    fun selectPlaybackTarget(packageName: String) {
        selectedPlaybackTarget.value = playbackTargetApps.value.firstOrNull {
            it.packageName == packageName
        }
    }

    fun registerPlaybackTarget(rawPackageName: String) {
        val context = getApplication<Application>()
        context.registerPlaybackTargetApp(rawPackageName)
            .onSuccess { target ->
                val refreshed = context.playbackTargetApps()
                playbackTargetApps.value = refreshed
                selectedPlaybackTarget.value = refreshed.firstOrNull {
                    it.packageName == target.packageName
                }
                playbackTargetRegistrationMessage.value = "등록됨: ${target.packageName}"
            }
            .onFailure { error ->
                playbackTargetRegistrationMessage.value =
                    error.message ?: "출력 대상 앱을 등록하지 못했습니다."
            }
    }

    fun startInput(mediaProjectionResultCode: Int? = null, mediaProjectionData: Intent? = null) {
        BroadcastService.startInput(
            getApplication(),
            mediaProjectionResultCode,
            mediaProjectionData,
            playbackTarget = selectedPlaybackTarget.value,
        )
    }

    fun pauseInput() = BroadcastService.pauseInput(getApplication())

    fun resumeInput() = BroadcastService.resumeInput(getApplication())

    fun stopInput() = BroadcastService.stopInput(getApplication())

    fun startTranslationTest(languageTag: String) {
        if (languageTag !in selectedLanguageTags.value) {
            modelMessage.value = "시험할 언어를 먼저 선택하세요."
            return
        }
        BroadcastService.startTranslationTest(
            getApplication(),
            languageTag = languageTag,
            sourceLanguageTag = selectedSourceLanguageTag.value,
            useGemma = useGemma.value,
            selectiveTranslationRefinement = selectiveTranslationRefinement.value,
        )
    }

    fun stopTranslationTest() = BroadcastService.stopTranslationTest(getApplication())

    fun clearTranscripts() {
        guideCastApplication.broadcastRuntime.update { current ->
            current.copy(transcripts = emptyList())
        }
    }

    fun deleteArchivedTranscripts(keys: Set<TranscriptArchiveKey>) =
        guideCastApplication.transcriptArchive.delete(keys)

    fun deleteArchivedTranscriptSession(sessionId: Long) =
        guideCastApplication.transcriptArchive.deleteSession(sessionId)

    fun reportProjectionDenied() {
        guideCastApplication.broadcastRuntime.update { current ->
            current.copy(
                inputPhase = InputPhase.IDLE,
                inputErrorMessage = "기기 내부 재생음 캡처 권한이 취소됐습니다.",
            )
        }
    }

    fun toggleTranslationLanguage(languageTag: String) {
        if (modelBusy.value) return
        val selection = toggleTranslationLanguageSelection(
            current = selectedLanguageTags.value,
            languageTag = languageTag,
            sourceLanguageTag = selectedSourceLanguageTag.value,
        )
        selectedLanguageTags.value = selection.selectedLanguageTags
        // Language preparation is independent from the operator's source/translation preference.
        modelMessage.value = selection.message
    }

    fun selectAllTranslationLanguages() {
        if (modelBusy.value) return
        selectedLanguageTags.value = recommendedTranslationLanguageSelection(
            sourceLanguageTag = selectedSourceLanguageTag.value,
        )
        modelMessage.value =
            "${selectedLanguageTags.value.size}개 언어를 선택했습니다. " +
                "선택한 송출 모드는 유지됩니다. 필요한 모델을 준비하세요."
    }

    fun clearTranslationLanguages() {
        if (modelBusy.value) return
        selectedLanguageTags.value = emptySet()
        modelMessage.value = "통역 언어 선택을 해제했습니다. 통역에는 언어를 하나 이상 선택하세요. 원음 방송도 선택할 수 있습니다."
    }

    fun selectSourceLanguage(languageTag: String) {
        if (modelJob?.isCompleted == false) return
        require(SOURCE_LANGUAGE_OPTIONS.any { it.languageTag == languageTag }) {
            "지원하지 않는 원문 언어입니다: $languageTag"
        }
        if (selectedSourceLanguageTag.value == languageTag) return
        selectedSourceLanguageTag.value = languageTag
        val allowedTargets = translationTargetLanguageOptions(languageTag)
            .mapTo(hashSetOf(), TranslationLanguageOption::languageTag)
        val retained = selectedLanguageTags.value.filterTo(linkedSetOf()) { it in allowedTargets }
        selectedLanguageTags.value = retained.ifEmpty {
            linkedSetOf(requireNotNull(translationTargetLanguageOptions(languageTag).firstOrNull()).languageTag)
        }
        speechRecognitionReady.value = false
        val capability = guideCastApplication.speechRecognitionEngine.capability(languageTag)
        speechRecognitionMessage.value = capability.reason
            ?: "${languageTag.sourceLanguageDisplayName()} 오프라인 음성인식을 확인하세요."
        runModelOperation {
            val owner = guideCastApplication.beginSettingsPreparation(
                languageTag,
                selectedLanguageTags.value,
            )
            try {
                guideCastApplication.withTranslationBackendUse {
                if (!guideCastApplication.isPreparationCurrent(owner)) {
                    modelMessage.value =
                        "방송 중에는 현재 원문 언어 작업 공간을 유지합니다. " +
                            "선택한 언어는 다음 모델 준비 때 적용됩니다."
                    return@withTranslationBackendUse
                }
                guideCastApplication.selectTranslationSource(owner)
                if (guideCastApplication.isPreparationCurrent(owner)) {
                    guideCastApplication.refreshTranslationModels(
                        translationTargetLanguageOptions(languageTag).map { it.languageTag }.toSet(),
                        owner,
                    )
                    refreshSpeechRecognitionStatus()
                    modelMessage.value =
                        "원문 언어를 ${languageTag.sourceLanguageDisplayName()}로 변경했습니다. " +
                        "선택한 출력 언어 모델을 준비하세요."
                }
                }
            } finally {
                guideCastApplication.endPreparation(owner)
            }
        }
    }

    fun selectSpeechVoice(languageTag: String, preference: SpeechVoicePreference) {
        val broadcast = broadcastState.value
        if (modelBusy.value || broadcast.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) || broadcast.translationTestActive) {
            modelMessage.value = "방송과 시험을 중지한 뒤 음성을 변경하세요."
            return
        }
        guideCastApplication.speechSynthesisProvider.setVoicePreference(languageTag, preference)
        modelMessage.value = "음성 선택을 저장했습니다. 준비 후 시험 탭에서 들어보세요."
    }

    fun prepareSelectedTranslationModels() {
        val selected = selectedLanguageTags.value
        if (selected.isEmpty()) {
            modelMessage.value = "다운로드할 언어를 하나 이상 선택하세요."
            return
        }
        runModelOperation(label = "통번역 준비 중") {
            val stageStates = linkedMapOf(
                "번역 모델" to "대기", "통역 음성" to "대기", "음성인식" to "대기",
            )
            fun progress(stage: String, status: String) {
                stageStates[stage] = status
                modelProgress.value = modelProgress.value.copy(
                    details = stageStates.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                )
                android.util.Log.i("GuideCastPreparation", "$stage: $status")
            }
            val sourceLanguage = selectedSourceLanguageTag.value
            val preparationOwner = guideCastApplication.beginSettingsPreparation(
                sourceLanguageTag = sourceLanguage,
                targetLanguageTags = selected,
            )
            try {
            guideCastApplication.withTranslationBackendUse(
                retainSettingsStandbyFor = preparationOwner,
            ) {
            if (!guideCastApplication.isPreparationCurrent(preparationOwner)) {
                modelMessage.value =
                    "방송 중에는 현재 통역 작업 공간을 유지합니다. " +
                        "방송을 중지한 뒤 선택 모델을 준비하세요."
                return@withTranslationBackendUse
            }
            guideCastApplication.selectTranslationSource(preparationOwner)
            // Always prepare the small offline translator as the safety path. Gemma may be
            // unavailable on a 6 GB phone or its native worker may be reclaimed during an event;
            // a prepared fallback keeps translated audio on air instead of leaving silent channels.
            supervisorScope {
            val translationTask = async {
                runIndependentPreparationStage("번역 모델", 8 * 60_000L, ::progress) {
                    prepareTranslationLanguageBatch(
                languageTags = selected,
                prepare = { languageTags ->
                    guideCastApplication.prepareTranslationModelsWithProcessAdmission(
                        languageTags = languageTags,
                        serializeWithAllColdLoads = languageTags.size > 1,
                        owner = preparationOwner,
                    )
                },
                statuses = { modelManager.statuses.value },
                    )
                }.getOrElse { error ->
                    translationPreparationReportFromStatuses(
                        selected, modelManager.statuses.value, error.message ?: "번역 모델 준비 실패",
                    )
                }.also { result ->
                    progress("번역 모델", translationPreparationSummary(result))
                }
            }
            // Translation model failures never skip voice/STT preparation. The operator can then
            // retry only the affected channel while every healthy language remains usable.
            val speechTask = async {
                runIndependentPreparationStage("통역 음성", 12 * 60_000L, ::progress) {
                prepareSpeechSynthesisWithProcessAdmission(
                    languageTags = selected,
                    settingsOwner = preparationOwner,
                )
                }.getOrElse { error ->
                GalaxySpeechPreparationReport(
                    moonshineLanguageTags = emptySet(),
                    androidFallbackLanguageTags = emptySet(),
                    fallbackReasons = emptyMap(),
                    unavailableLanguageReasons = selected.associateWith {
                        error.message ?: error.javaClass.simpleName
                    },
                )
                }.also { result ->
                    progress("통역 음성", if (result.unavailableLanguageReasons.isEmpty()) {
                        "준비 완료 · 언어별 엔진 상태를 확인하세요."
                    } else "확인 필요 · " + result.unavailableLanguageReasons.entries.joinToString {
                        "${it.key}: ${it.value}"
                    })
                }
            }
            val recognitionTask = async {
                runIndependentPreparationStage("음성인식", 10 * 60_000L, ::progress) {
                val status = if (
                guideCastApplication.speechRecognitionEngine.capability(sourceLanguage).available
            ) {
                guideCastApplication.prepareSpeechRecognitionWithProcessAdmission(
                    languageTag = sourceLanguage,
                    serializeWithAllColdLoads = selected.size > 1,
                    owner = preparationOwner,
                )
            } else {
                guideCastApplication.speechRecognitionEngine.languageStatus(sourceLanguage)
            }
                speechRecognitionReady.value = status.isReady
                speechRecognitionMessage.value = status.message
                status
                }.also { result ->
                    result.onFailure { error ->
                        speechRecognitionReady.value = false
                        speechRecognitionMessage.value = error.message ?: "음성인식 준비 실패"
                    }
                }
            }
            val translationPreparation = translationTask.await()
            val speechPreparation = speechTask.await()
            recognitionTask.await()
            val translationSummary = translationPreparationSummary(translationPreparation)
            val readyMessage = if (speechRecognitionReady.value) {
                val voiceLabel = if (speechPreparation.androidFallbackLanguageTags.isEmpty()) {
                    "고품질 통역 음성"
                } else {
                    "통역 음성"
                }
                if (useGemma.value) {
                    "Gemma 번역·$translationSummary·" +
                        "${sourceLanguage.sourceLanguageDisplayName()} 음성인식·$voiceLabel 준비 결과"
                } else {
                    "$translationSummary·${sourceLanguage.sourceLanguageDisplayName()} " +
                        "음성인식·$voiceLabel 준비 결과"
                }
            } else {
                "$translationSummary · ${speechRecognitionMessage.value}"
            }
            modelMessage.value = translationModelPreparationMessage(
                readyMessage = readyMessage,
                speechPreparation = speechPreparation,
            )
            }
            }
            } finally {
                guideCastApplication.endPreparation(preparationOwner)
            }
        }
    }

    fun removeTranslationModel(languageTag: String) {
        translationBroadcastEnabled.value = false
        runModelOperation(successMessage = "선택한 번역 모델을 삭제했습니다.") {
            modelManager.remove(languageTag)
            modelManager.refresh(
                translationTargetLanguageOptions(selectedSourceLanguageTag.value)
                    .map { it.languageTag }
                    .toSet(),
            )
        }
    }

    fun setTranslationBroadcastEnabled(enabled: Boolean) {
        if (!enabled) {
            translationBroadcastEnabled.value = false
            return
        }
        val selected = selectedLanguageTags.value
        val notReady = selected.filter { languageTag ->
            modelManager.statuses.value.none {
                it.languageTag == languageTag && it.readiness == ModelReadiness.READY
            }
        }
        val gemmaIsReady =
            gemmaProvider.modelManager.status.value.readiness == GemmaModelReadiness.READY
        val translationModelsReady = selected.all { languageTag ->
            val lightweightReady = languageTag !in notReady
            if (useGemma.value && GemmaTranslationProvider.supportsTranslation(selectedSourceLanguageTag.value, languageTag)) {
                gemmaIsReady || lightweightReady
            } else {
                lightweightReady
            }
        }
        val speechSynthesisReady = selected.all { languageTag ->
            guideCastApplication.speechSynthesisProvider.isReady(languageTag) ||
                guideCastApplication.speechSynthesisProvider.isFallbackReady(languageTag)
        }
        val selection = translationBroadcastSelectionResult(
            selectedLanguageTags = selected,
            speechRecognitionReady =
                guideCastApplication.speechRecognitionEngine
                    .capability(selectedSourceLanguageTag.value).available &&
                    speechRecognitionReady.value,
            translationModelsReady = translationModelsReady,
            speechSynthesisReady = speechSynthesisReady,
            useGemma = useGemma.value,
            sourceLanguageLabel = selectedSourceLanguageTag.value.sourceLanguageDisplayName(),
        )
        translationBroadcastEnabled.value = selection.enabled
        modelMessage.value = selection.message
    }

    fun startBroadcast(
        accessMode: OperatorAccessMode,
        pin: CharArray? = null,
        speakerPin: CharArray? = null,
    ) {
        val translationLanguages = if (translationBroadcastEnabled.value) {
            selectedLanguageTags.value.toTypedArray()
        } else {
            emptyArray()
        }
        BroadcastService.start(
            getApplication(),
            accessMode,
            pin,
            speakerPin,
            translationLanguages,
            sourceLanguageTag = selectedSourceLanguageTag.value,
            useGemma = useGemma.value,
            selectiveTranslationRefinement = selectiveTranslationRefinement.value,
        )
    }

    fun pauseBroadcast() = BroadcastService.pauseBroadcast(getApplication())

    fun resumeBroadcast() = BroadcastService.resumeBroadcast(getApplication())

    fun stopBroadcast() = BroadcastService.stopBroadcast(getApplication())

    fun playTestTone() = BroadcastService.playTestTone(getApplication())

    fun startLocalMonitor(channelId: String, outputDeviceId: Int) =
        BroadcastService.startLocalMonitor(getApplication(), channelId, outputDeviceId)

    fun pauseLocalMonitor() = BroadcastService.pauseLocalMonitor(getApplication())

    fun resumeLocalMonitor() = BroadcastService.resumeLocalMonitor(getApplication())

    fun stopLocalMonitor() = BroadcastService.stopLocalMonitor(getApplication())

    fun setLocalMonitorVolume(gain: Float) = BroadcastService.setLocalMonitorVolume(getApplication(), gain)

    fun downloadGemmaModel() {
        if (gemmaProvider.modelManager.status.value.readiness in ACTIVE_GEMMA_STATES) return
        gemmaMessage.value = "공개 모델 다운로드를 시작했습니다. 알림에서 진행률을 확인할 수 있습니다."
        runCatching { GemmaModelService.start(getApplication()) }
            .onFailure { error ->
                gemmaMessage.value =
                    "Android가 모델 준비 서비스를 시작하지 못했습니다. " +
                        "앱을 화면에 둔 상태에서 다시 시도하세요: " +
                        (error.message ?: error.javaClass.simpleName)
            }
    }

    fun selectGemmaModel(variant: GemmaModelVariant) {
        val broadcast = broadcastState.value
        if (broadcast.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
            broadcast.translationTestActive || gemmaJob?.isActive == true || GemmaModelService.preparing.value
        ) {
            gemmaMessage.value = "방송·통역 시험을 중지한 뒤 모델을 변경하세요."
            return
        }
        gemmaMessage.value = null
        runCatching { GemmaModelService.start(getApplication(), variant.id) }
            .onFailure { gemmaMessage.value = it.message ?: "모델 준비를 시작하지 못했습니다." }
    }

    fun importGemmaCatalog(uri: Uri) {
        if (gemmaJob?.isActive == true || GemmaModelService.preparing.value) return
        gemmaJob = viewModelScope.launch {
            gemmaBusy.value = true
            try {
                val entries = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        GemmaCatalogStore(getApplication()).importSigned(it)
                    } ?: error("모델 목록 파일을 열 수 없습니다.")
                }
                gemmaCatalogEntries.value = entries
                gemmaMessage.value = "서명 검증 완료 · 모델 ${entries.size}개. 사용할 모델을 선택하고 적용하세요."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                gemmaMessage.value = "목록 가져오기 실패 · 기존 목록 유지: ${error.message}"
            } finally {
                gemmaBusy.value = false
            }
        }
    }

    fun importGemmaModel(uri: Uri) {
        if (gemmaJob?.isActive == true) return
        gemmaJob = viewModelScope.launch {
            gemmaBusy.value = true
            gemmaMessage.value = null
            runCatching {
                gemmaProvider.modelManager.withSelectedModel {
                gemmaProvider.modelManager.importModel(uri)
                verifyGemmaRuntime()
                }
            }.onSuccess { result ->
                gemmaMessage.value =
                    "가져오기·추론·TTS 점검 통과 · 안녕하세요 → ${result.translation} · " +
                        "RMS ${"%.3f".format(result.rms)} / 피크 ${"%.3f".format(result.peak)}"
            }
                .onFailure { gemmaMessage.value = it.message ?: "선택한 모델을 가져오지 못했습니다." }
            gemmaBusy.value = false
        }
    }

    fun removeGemmaModel() {
        translationBroadcastEnabled.value = false
        useGemma.value = false
        selectiveTranslationRefinement.value = false
        if (gemmaJob?.isActive == true) return
        gemmaJob = viewModelScope.launch {
            gemmaBusy.value = true
            runCatching { gemmaProvider.modelManager.remove() }
                .onSuccess { gemmaMessage.value = "Gemma 모델을 삭제했습니다." }
                .onFailure { gemmaMessage.value = it.message }
            gemmaBusy.value = false
        }
    }

    fun setUseGemma(enabled: Boolean) {
        // Backend preference is independent from the operator's original/translation choice.
        if (enabled && !gemmaBroadcastCapability.supported) {
            useGemma.value = false
            selectiveTranslationRefinement.value = false
            gemmaMessage.value = gemmaBroadcastCapability.message
            return
        }
        if (enabled && gemmaProvider.modelManager.status.value.readiness != GemmaModelReadiness.READY) {
            gemmaMessage.value =
                "Gemma 모델이 아직 준비되지 않았습니다. 방송 서버 시작 여부는 운영자가 결정합니다."
        } else {
            gemmaMessage.value = null
        }
        useGemma.value = enabled
        if (!enabled) selectiveTranslationRefinement.value = false
    }

    fun setSelectiveTranslationRefinement(enabled: Boolean) {
        val runtime = broadcastState.value
        val inputActive = runtime.inputPhase == InputPhase.STARTING ||
            runtime.inputPhase == InputPhase.ACTIVE || runtime.inputPhase == InputPhase.PAUSED
        val broadcastActive = runtime.phase == BroadcastPhase.STARTING ||
            runtime.phase == BroadcastPhase.LIVE || runtime.phase == BroadcastPhase.PAUSED
        if (inputActive || broadcastActive || runtime.translationTestActive) return
        selectiveTranslationRefinement.value = enabled && useGemma.value
    }

    fun testGemma() {
        if (gemmaJob?.isActive == true) return
        gemmaJob = viewModelScope.launch {
            gemmaBusy.value = true
            gemmaMessage.value = "Gemma 번역 추론 및 TTS 음성을 검사하는 중입니다."
            runCatching { verifyGemmaRuntime() }.onSuccess { result ->
                gemmaMessage.value = if (result.ttsDegradedReason != null) {
                    "번역 점검 통과 · 안녕하세요 → ${result.translation} · " +
                        "TTS 상태: ${result.ttsDegradedReason}"
                } else {
                    "번역·TTS 점검 통과 · 안녕하세요 → ${result.translation} · " +
                        "RMS ${"%.3f".format(result.rms)} / 피크 ${"%.3f".format(result.peak)}"
                }
            }.onFailure {
                gemmaMessage.value = it.message ?: "Gemma 번역 점검에 실패했습니다."
            }
            gemmaBusy.value = false
        }
    }

    private suspend fun verifyGemmaRuntime(): GemmaRuntimeTestResult =
        gemmaProvider.modelManager.withSelectedModel { guideCastApplication.withTranslationBackendUse {
        val manager = gemmaProvider.modelManager
        var stats: PcmSignalStats? = null
        val outcome = executeGemmaVerification(
            markEngineTesting = { manager.markEngineTesting() },
            selfTest = {
                guideCastApplication.withProcessNativeColdLoadLease(
                    key = ProcessNativeColdLoadKeys.GEMMA_MODEL,
                    serializeWithAllColdLoads = true,
                ) {
                    gemmaProvider.selfTest()
                }
            },
            markRuntimeReady = { manager.markRuntimeReady() },
            markRuntimeFailure = { message -> manager.markRuntimeFailure(message) },
            probeTts = { output ->
                prepareSpeechSynthesisWithProcessAdmission(listOf("en"))
                stats = withTimeout(45_000) {
                    guideCastApplication.speechSynthesisProvider
                        .engineFor("en")
                        .synthesize(output, "en")
                        .map { it.bytes.pcmS16LeSignalStats() }
                        .first { it.sampleCount > 0 && it.peak > 0.002f }
                }
            },
        )
        if (outcome.readiness != GemmaModelReadiness.READY) {
            throw IllegalStateException(outcome.failureMessage ?: "Gemma Translator 추론 실패")
        }
        val ttsDegradedReason = outcome.ttsDegradedReason?.let {
            guideCastApplication.speechSynthesisProvider.unavailableReason("en") ?: it
        }
        GemmaRuntimeTestResult(
            translation = requireNotNull(outcome.translation),
            rms = stats?.rms ?: 0f,
            peak = stats?.peak ?: 0f,
            ttsDegradedReason = ttsDegradedReason,
        )
    } }

    private suspend fun prepareSpeechSynthesisWithProcessAdmission(
        languageTags: Collection<String>,
        settingsOwner: TranslationPreparationOwnerToken? = null,
    ): GalaxySpeechPreparationReport {
        val provider = guideCastApplication.speechSynthesisProvider
        val serializeWithAllColdLoads = languageTags.distinct().size > 1
        val isSettingsPreparationCurrent = {
            settingsOwner == null || guideCastApplication.isPreparationCurrent(settingsOwner)
        }
        val warmWithAdmission: suspend (String, suspend () -> Unit) -> Unit =
            { languageTag, warm ->
                if (!provider.hasActiveMoonshineWorker(languageTag)) {
                    runPreparationOwnedNativeOperation(
                        isOwnerCurrent = isSettingsPreparationCurrent,
                        ownerLostMessage =
                            "Broadcast activation replaced settings TTS preparation",
                        withAdmission = { operation ->
                            guideCastApplication.withProcessNativeColdLoadLease(
                                key = ProcessNativeColdLoadKeys.speech(languageTag),
                                serializeWithAllColdLoads = serializeWithAllColdLoads,
                            ) { operation() }
                        },
                    ) {
                        // A broadcast may have completed the same warm-up while settings waited.
                        if (!provider.hasActiveMoonshineWorker(languageTag)) warm()
                    }
                }
            }
        return if (settingsOwner != null) {
            provider.prepareForSettings(
                languageTags = languageTags,
                warmMoonshineWithNativeAdmission = warmWithAdmission,
                isAppPreparationCurrent = isSettingsPreparationCurrent,
            )
        } else {
            provider.prepare(
                languageTags = languageTags,
                warmMoonshineWithNativeAdmission = warmWithAdmission,
            )
        }
    }

    private fun runModelOperation(
        successMessage: String? = null,
        timeoutMillis: Long = 20L * 60 * 1_000,
        label: String = "모델 상태 확인 중",
        operation: suspend () -> Unit,
    ) {
        if (modelJob?.isCompleted == false) return
        modelJob = viewModelScope.launch {
            modelProgress.value = ModelProgress(label)
            modelMessage.value = null
            runBoundedModelOperation(
                timeoutMillis = timeoutMillis,
                busy = { modelBusy.value = it },
                failure = { error -> modelMessage.value = error },
            ) {
                operation()
                if (successMessage != null) modelMessage.value = successMessage
            }
        }
    }

    fun cancelModelPreparation() {
        val job = modelJob ?: return
        if (job.isCompleted) return
        modelProgress.value = modelProgress.value.copy(
            label = "준비 중지 중", cancelling = true,
        )
        modelMessage.value = "준비를 중지했습니다. 받은 모델 파일은 보존됩니다. 언어를 선택해 다시 준비할 수 있습니다."
        job.cancel(CancellationException("Operator stopped model preparation"))
    }

    private suspend fun inspectModelStatus(label: String, operation: suspend () -> Unit) {
        try {
            withTimeout(10_000L) { operation() }
        } catch (error: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val message = "$label 확인 응답 없음 또는 실패. 아래 준비 버튼으로 다시 시도하세요."
            android.util.Log.w("GuideCastModelCheck", message, error)
            modelMessage.value = listOfNotNull(modelMessage.value, message).joinToString("\n")
        }
    }

    private suspend fun refreshSpeechRecognitionStatus() {
        val status = guideCastApplication.speechRecognitionEngine.languageStatus(
            selectedSourceLanguageTag.value,
        )
        speechRecognitionReady.value = status.isReady
        speechRecognitionMessage.value = status.message
    }

    private companion object {
        const val RESOURCE_DIAGNOSTIC_REFRESH_MILLIS = 3_000L
        // PSS scans are substantially heavier than ActivityManager.MemoryInfo. Sample sparingly
        // off the UI thread; release/device gates use adb for high-frequency measurements.
        const val APP_PROCESS_MEMORY_REFRESH_MILLIS = 15_000L
    }
}

internal fun translationModelPreparationMessage(
    readyMessage: String,
    speechPreparation: GalaxySpeechPreparationReport,
): String = listOfNotNull(readyMessage, speechPreparation.warning).joinToString(" · ")

/** Avoids a synchronous getProcessMemoryInfo Binder call while the ViewModel is constructed. */
internal fun initialAppProcessMemorySnapshot(): AppProcessMemorySnapshot =
    AppProcessMemorySnapshot.unavailable()

internal data class TranslationLanguagePreparationReport(
    val readyLanguageTags: Set<String>,
    val failures: Map<String, String>,
)

internal suspend fun prepareTranslationLanguagesIndependently(
    languageTags: Collection<String>,
    timeoutMillis: Long = TRANSLATION_LANGUAGE_PREPARATION_TIMEOUT_MILLIS,
    prepare: suspend (String) -> Unit,
): TranslationLanguagePreparationReport {
    require(languageTags.isNotEmpty())
    require(timeoutMillis > 0L)
    val orderedLanguages = languageTags.distinct()
    val outcomes = supervisorScope {
        orderedLanguages.map { languageTag ->
            async {
                val failure = try {
                    val completed = withTimeoutOrNull(timeoutMillis) {
                        prepare(languageTag)
                        true
                    } ?: false
                    if (completed) null else "언어별 모델 준비 시간 초과"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    error.message ?: error.javaClass.simpleName
                }
                languageTag to failure
            }
        }.awaitAll()
    }
    val ready = linkedSetOf<String>()
    val failures = linkedMapOf<String, String>()
    outcomes.forEach { (languageTag, failure) ->
        if (failure == null) {
            ready += languageTag
        } else {
            failures[languageTag] = failure
        }
    }
    return TranslationLanguagePreparationReport(ready, failures)
}

internal suspend fun prepareTranslationLanguageBatch(
    languageTags: Set<String>,
    prepare: suspend (Set<String>) -> Unit,
    statuses: () -> List<LanguageModelStatus>,
): TranslationLanguagePreparationReport {
    require(
        languageTags.isNotEmpty() &&
            languageTags.size <= MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES,
    )
    val operationFailure = try {
        prepare(languageTags)
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        error.message ?: error.javaClass.simpleName
    }
    return translationPreparationReportFromStatuses(languageTags, statuses(), operationFailure)
}

internal fun translationPreparationReportFromStatuses(
    languageTags: Set<String>,
    statuses: List<LanguageModelStatus>,
    operationFailure: String?,
): TranslationLanguagePreparationReport {
    val statusByLanguage = statuses.associateBy(LanguageModelStatus::languageTag)
    val ready = languageTags.filterTo(linkedSetOf()) { languageTag ->
        statusByLanguage[languageTag]?.readiness == ModelReadiness.READY
    }
    val failures = languageTags.filterNot(ready::contains).associateWithTo(linkedMapOf()) {
            languageTag ->
        statusByLanguage[languageTag]?.errorMessage
            ?: operationFailure
            ?: "언어별 번역 모델이 준비 상태가 아닙니다."
    }
    return TranslationLanguagePreparationReport(ready, failures)
}

private const val TRANSLATION_LANGUAGE_PREPARATION_TIMEOUT_MILLIS = 10L * 60 * 1_000
internal fun translationPreparationSummary(
    report: TranslationLanguagePreparationReport,
): String {
    val total = report.readyLanguageTags.size + report.failures.size
    if (report.failures.isEmpty()) return "경량 대체 번역 $total/$total 준비"
    val failures = report.failures.entries.joinToString { (languageTag, reason) ->
        "$languageTag: $reason"
    }
    return "경량 대체 번역 ${report.readyLanguageTags.size}/$total 준비 · 실패 $failures"
}

internal data class TranslationBroadcastSelectionResult(
    val enabled: Boolean,
    val message: String?,
)

internal fun translationBroadcastSelectionResult(
    selectedLanguageTags: Set<String>,
    speechRecognitionReady: Boolean,
    translationModelsReady: Boolean,
    speechSynthesisReady: Boolean,
    useGemma: Boolean,
    sourceLanguageLabel: String = "한국어",
): TranslationBroadcastSelectionResult {
    if (selectedLanguageTags.isEmpty()) {
        return TranslationBroadcastSelectionResult(
            enabled = false,
            message = "통역 언어를 하나 이상 선택하세요.",
        )
    }
    val missing = buildList {
        if (!speechRecognitionReady) add("$sourceLanguageLabel 음성인식")
        if (!translationModelsReady) {
            add(if (useGemma) "Gemma 번역/언어별 번역 모델" else "번역 모델")
        }
        if (!speechSynthesisReady) add("통역 음성")
    }
    return TranslationBroadcastSelectionResult(
        // Readiness is advisory. The operator retains authority to start the broadcast.
        enabled = true,
        message = missing.takeIf { it.isNotEmpty() }?.let {
            "준비 확인 필요: ${it.joinToString()} · 방송 서버 시작 여부는 운영자가 결정합니다."
        },
    )
}

val PRIMARY_TRANSLATION_LANGUAGE_TAGS = listOf("en", "ja", "zh", "zh-TW", "vi")

val TRANSLATION_LANGUAGE_OPTIONS = listOf(
    TranslationLanguageOption("ko", "한국어 · Korean"),
    TranslationLanguageOption("en", "영어 · English"),
    TranslationLanguageOption("ja", "일본어 · 日本語"),
    TranslationLanguageOption("zh", "중국어(간체) · 中文(简体)"),
    TranslationLanguageOption("zh-TW", "중국어(번체·대만) · 繁體中文"),
    TranslationLanguageOption("vi", "베트남어 · Tiếng Việt"),
    TranslationLanguageOption("nl", "네덜란드어 · Nederlands"),
    TranslationLanguageOption("es", "스페인어 · Español"),
    TranslationLanguageOption("ar", "아랍어 · العربية"),
)

val SOURCE_LANGUAGE_OPTIONS = listOf(
    SourceLanguageOption("ko-KR", "한국어 · Korean"),
    SourceLanguageOption("en-US", "영어 · English"),
    SourceLanguageOption("ja-JP", "일본어 · 日本語"),
    SourceLanguageOption("zh-CN", "중국어 · 中文"),
    SourceLanguageOption("es-ES", "스페인어 · Español"),
    SourceLanguageOption("ar-SA", "아랍어 · العربية"),
)

const val DEFAULT_SOURCE_LANGUAGE_TAG = "ko-KR"

fun translationTargetLanguageOptions(
    sourceLanguageTag: String,
    options: List<TranslationLanguageOption> = TRANSLATION_LANGUAGE_OPTIONS,
): List<TranslationLanguageOption> {
    val source = normalizeSourceLanguage(sourceLanguageTag)
    require(source in GUIDECAST_SOURCE_LANGUAGE_BASE_TAGS) {
        "지원하지 않는 원문 언어입니다: $sourceLanguageTag"
    }
    return options.filterNot { normalizeSourceLanguage(it.languageTag) == source }
}

const val MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES = MAX_SIMULTANEOUS_TRANSLATED_CHANNELS

internal data class TranslationLanguageSelectionResult(
    val selectedLanguageTags: Set<String>,
    val message: String? = null,
)

/**
 * Returns a fresh insertion-ordered selection so channel order is stable from settings to QR.
 * A full selection stays unchanged when another language is requested; selected entries can
 * always be removed so the operator is never trapped at the limit.
 */
internal fun toggleTranslationLanguageSelection(
    current: Set<String>,
    languageTag: String,
    options: List<TranslationLanguageOption> = TRANSLATION_LANGUAGE_OPTIONS,
    maximum: Int = MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES,
    sourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
): TranslationLanguageSelectionResult {
    require(maximum > 0)
    val availableOptions = translationTargetLanguageOptions(sourceLanguageTag, options)
    require(availableOptions.any { it.languageTag == languageTag }) {
        "지원하지 않는 통역 언어입니다: $languageTag"
    }
    val ordered = current.toCollection(linkedSetOf())
    if (ordered.remove(languageTag)) {
        return TranslationLanguageSelectionResult(ordered)
    }
    if (ordered.size >= maximum) {
        return TranslationLanguageSelectionResult(
            selectedLanguageTags = ordered,
            message = "동시 통역은 최대 ${maximum}개 언어까지 선택할 수 있습니다.",
        )
    }
    ordered.add(languageTag)
    return TranslationLanguageSelectionResult(ordered)
}

internal fun recommendedTranslationLanguageSelection(
    options: List<TranslationLanguageOption> = TRANSLATION_LANGUAGE_OPTIONS,
    maximum: Int = PRIMARY_TRANSLATION_LANGUAGE_TAGS.size,
    sourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
): Set<String> {
    require(maximum > 0)
    return translationTargetLanguageOptions(sourceLanguageTag, options)
        .take(maximum)
        .mapTo(linkedSetOf()) { it.languageTag }
}

private val ACTIVE_GEMMA_STATES = setOf(
    GemmaModelReadiness.DOWNLOADING,
    GemmaModelReadiness.VERIFYING,
    GemmaModelReadiness.ENGINE_TESTING,
)
