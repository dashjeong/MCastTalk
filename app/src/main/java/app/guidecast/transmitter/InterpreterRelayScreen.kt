package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.MicrophoneNoiseMode
import app.guidecast.core.stream.normalizedRecordingTitle
import app.guidecast.core.stream.RECORDING_TITLE_MAX_CODE_POINTS

/** Native audio relay workspace. Streaming language/model choices remain in their own workspace. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InterpreterRelayScreen(app: GuideCastApplication, broadcast: BroadcastSnapshot,
    microphoneGranted: Boolean, onRequestMicrophone: () -> Unit, onStart: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, onBack: () -> Unit,
    onOpenHud: () -> Unit = {}, onStartMicrophone: () -> Unit = {}, onStopMicrophone: () -> Unit = {}, settingsRequest: Int = 0,
    onSettingsRequestHandled: () -> Unit = {}, onOpenFilteredHud: ((List<String>, Boolean) -> Unit)? = null,
    automaticExampleControls: (@Composable () -> Unit)? = null) {
    val api by app.translationApiSettings.state.collectAsState()
    LaunchedEffect(api.revision) { app.interpreterRelaySettings.rememberRelayApi(app.translationApiSettings.state.value) }
    var contextLibrary by rememberSaveable { mutableStateOf(false) }
    var librarySettingsRequest by rememberSaveable { mutableStateOf(0) }
    var professionalSettings by rememberSaveable { mutableStateOf(false) }
    var domainDraft by rememberSaveable { mutableStateOf(api.domainPrompt) }
    var instructionDraft by rememberSaveable { mutableStateOf(api.interpreterInstructions) }
    var lastSavedDomain by rememberSaveable { mutableStateOf(api.domainPrompt) }
    var lastSavedInstructions by rememberSaveable { mutableStateOf(api.interpreterInstructions) }
    var pendingDraftAction by rememberSaveable { mutableStateOf<String?>(null) }
    var draftError by remember { mutableStateOf<String?>(null) }
    val draftDirty = domainDraft != api.domainPrompt || instructionDraft != api.interpreterInstructions
    val relay by app.interpreterRelaySettings.state.collectAsState()
    val live by app.geminiLiveMonitor.state.collectAsState()
    val learning by app.nativeLearningMonitor.state.collectAsState()
    val devices by app.audioInputRepository.availableDevices.collectAsState()
    val selected by app.audioInputRepository.selectedDevice.collectAsState()
    val microphoneProfiles by app.microphoneNoiseSettings.profiles.collectAsState()
    val microphoneGroup = MicrophoneInputGroup.forKind(selected?.kind)
    val microphoneProfile = microphoneProfiles.getValue(microphoneGroup)
    val microphoneBusy = broadcast.inputStopping ||
        broadcast.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE)
    val captureDiagnostics by app.audioCaptureEngine.diagnostics.collectAsState()
    val levelTracker = remember(microphoneBusy) { InputSignalGuidanceTracker() }
    val levelGuidance = levelTracker.observe(broadcast.inputRms, broadcast.inputPeak, broadcast.inputFrameCount,
        android.os.SystemClock.elapsedRealtime(), inputActive = broadcast.inputPhase == InputPhase.ACTIVE,
        clientSilenced = captureDiagnostics.clientSilenced == true, systemMuted = captureDiagnostics.systemMicrophoneMuted == true)
    var captionDisplayTargets by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var captionDisplaySource by rememberSaveable { mutableStateOf(true) }
    var titleDialog by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var endingRecordingId by remember { mutableStateOf<String?>(null) }
    var endingCleanupRetry by remember { mutableStateOf(false) }
    var endingWasStarting by remember { mutableStateOf(false) }
    var endingInputEpoch by remember { mutableStateOf(-1L) }
    var endActionMessage by remember { mutableStateOf<String?>(null) }
    val menuBroadcast by app.menuBroadcast.state.collectAsState()
    val workspace = relayWorkspaceControlState(broadcast, menuBroadcast.isActive,
        app.webBroadcastOwnership.currentOwner == "streaming")
    val captionLanguages = remember(relay.targetLanguageTags, workspace.displayedBroadcast.transcripts) {
        (relay.targetLanguageTags + workspace.displayedBroadcast.transcripts.flatMap { it.translations.keys }).distinct()
    }
    val visibleCaptionTargets = workspaceDisplayLanguages(captionLanguages, captionDisplayTargets)
    val busy = broadcast.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
        (broadcast.phase == BroadcastPhase.FAILED && app.webBroadcastOwnership.currentOwner == "streaming")
    val comparisonChoice = nativeComparisonPresentation(api, relay.compareOffline, busy, broadcast.relayContext?.model,
        learning.nativeControlGeneration, app.interpreterRelaySettings.comparisonGeneration)
    val learningSupported = comparisonChoice.supported
    var sourcePicker by rememberSaveable { mutableStateOf(false) }
    var targetPicker by rememberSaveable { mutableStateOf(false) }
    var targetDraft by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var targetSelectionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var targetLanguageQuery by rememberSaveable { mutableStateOf("") }
    var sourceLanguageQuery by rememberSaveable { mutableStateOf("") }
    var showAllTargetLanguages by rememberSaveable { mutableStateOf(false) }
    var showAllSourceLanguages by rememberSaveable { mutableStateOf(false) }
    var monitorPicker by rememberSaveable { mutableStateOf(false) }
    var voicePicker by rememberSaveable { mutableStateOf(false) }
    var voiceSelectionError by remember { mutableStateOf<String?>(null) }
    var microphonePicker by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var pendingMicRecordingId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var setupFocus by rememberSaveable { mutableStateOf<RelaySetupItem?>(null) }
    var setupMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var showUsage by remember { mutableStateOf(false) }
    var inputIssue by remember { mutableStateOf<RelayInputIssue?>(null) }
    fun discardDraft() {
        val saved = app.translationApiSettings.state.value
        domainDraft = saved.domainPrompt; instructionDraft = saved.interpreterInstructions
        lastSavedDomain = saved.domainPrompt; lastSavedInstructions = saved.interpreterInstructions
        draftError = null
    }
    fun saveDraft(): Boolean {
        if (!app.translationApiSettings.setProfessionalRelayInstructions(domainDraft, instructionDraft)) {
            draftError = "저장하지 못했습니다. 지원 모델과 글자 수를 확인하고 API 키는 지침에 넣지 마세요."
            return false
        }
        app.interpreterRelaySettings.rememberRelayApi(app.translationApiSettings.state.value)
        discardDraft()
        return true
    }
    LaunchedEffect(api.domainPrompt, api.interpreterInstructions) {
        if (domainDraft == lastSavedDomain && instructionDraft == lastSavedInstructions) {
            domainDraft = api.domainPrompt; instructionDraft = api.interpreterInstructions
        }
        lastSavedDomain = api.domainPrompt; lastSavedInstructions = api.interpreterInstructions
    }
    fun openSetup(item: RelaySetupItem, message: String = item.message) {
        showSettings = true; setupFocus = item; setupMessage = message
    }
    fun startBroadcast() {
        val missing = relayBroadcastRequiredSetting(app.translationApiSettings.state.value,
            app.interpreterRelaySettings.state.value)
        if (missing != null) { openSetup(missing); return }
        onStart()
    }
    fun start(consented: Boolean = false, useSavedDraft: Boolean = false) {
        if (!relayMicrophoneRequestMatches(broadcast.recordingId, broadcast) ||
            (pendingMicRecordingId != null && pendingMicRecordingId != broadcast.recordingId)) {
            awaitingPermission = false; consent = false; pendingMicRecordingId = null
            return
        }
        pendingMicRecordingId = broadcast.recordingId
        if (!useSavedDraft && draftDirty) { pendingDraftAction = "START"; return }
        val currentApi = app.translationApiSettings.state.value
        val missing = relayRequiredSetting(currentApi, app.interpreterRelaySettings.state.value,
            app.audioInputRepository.selectedDevice.value?.kind)
        if (missing != null) { openSetup(missing); return }
        showSettings = false
        inputIssue = relayInputIssue(selected?.kind, true,
            runCatching { app.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute }.getOrNull())
        if (inputIssue != null) return
        if (!microphoneGranted) { awaitingPermission = true; onRequestMicrophone(); return }
        if (!consented && (!app.translationApiSettings.authorized(currentApi) || !currentApi.allowLiveAudio)) { consent = true; return }
        onStartMicrophone()
        pendingMicRecordingId = null
    }
    fun completeDraftAction(action: String) {
        when (action) {
            "START" -> start(useSavedDraft = true)
            "START_BROADCAST" -> startBroadcast()
            "LIBRARY" -> contextLibrary = true
            "BACK" -> onBack()
            "CLOSE_PROFESSIONAL" -> professionalSettings = false
            "CLOSE_SETTINGS" -> showSettings = false
            "RETURN_WORKSPACE" -> {
                contextLibrary = false; professionalSettings = false; showSettings = false
                setupFocus = null; setupMessage = null
            }
            "OPEN_SETTINGS" -> {
                professionalSettings = false; showSettings = true; setupFocus = null; setupMessage = null
            }
            "DISCARD" -> discardDraft()
        }
    }
    fun guardedAction(action: String) {
        if (draftDirty) { draftError = null; pendingDraftAction = action }
        else completeDraftAction(action)
    }
    fun requestEnd() {
        endActionMessage = null
        endingRecordingId = broadcast.recordingId; endingCleanupRetry = workspace.cleanupRetry
        endingWasStarting = broadcast.phase == BroadcastPhase.STARTING
        endingInputEpoch = app.broadcastRuntime.inputRequestEpoch; confirmEnd = true
    }
    val settingsFooter: @Composable () -> Unit = {
        WorkspaceSettingsFooter(workspace.displayedBroadcast, workspace.broadcastActive,
            onBack = { guardedAction("RETURN_WORKSPACE") }, onStopBroadcast = ::requestEnd,
            onStopInput = onStopMicrophone, stopBroadcastLabel = "방송 종료")
    }
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > 0) {
            if (contextLibrary) librarySettingsRequest += 1 else guardedAction("OPEN_SETTINGS")
            onSettingsRequestHandled()
        }
    }
    BackHandler(enabled = !contextLibrary && (professionalSettings || showSettings || (!busy && draftDirty))) {
        if (showSettings && setupFocus != null && !professionalSettings) { setupFocus = null; setupMessage = null }
        else guardedAction(if (professionalSettings) "CLOSE_PROFESSIONAL" else if (showSettings) "CLOSE_SETTINGS" else "BACK")
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false },
        title = { Text(if (endingCleanupRetry) "남아 있는 방송 종료 정리를 다시 시도할까요?" else "방송을 종료할까요?") },
        text = { Text(if (endingCleanupRetry) "남아 있는 방송 연결의 종료를 다시 확인합니다. 새 방송이나 마이크·AI 연결은 시작하지 않습니다."
            else "마이크와 통역 연결도 종료합니다. 음성과 스크립트는 방송 이력에서 확인할 수 있습니다. 다음 방송에는 새 접속 권한을 만듭니다.") },
        confirmButton = { TextButton(enabled = workspace.broadcastActive, onClick = {
            val current = app.broadcastRuntime.state.value
            confirmEnd = false
            if (relayEndRequestMatches(current, endingRecordingId, endingWasStarting, endingInputEpoch,
                    app.broadcastRuntime.inputRequestEpoch, endingCleanupRetry,
                    app.webBroadcastOwnership.currentOwner == "streaming")) onStop()
            else endActionMessage = "방송 상태가 바뀌었습니다. 현재 방송을 확인한 뒤 종료를 다시 눌러 주세요."
        }) { Text(if (endingCleanupRetry) "종료 정리 재시도" else "방송 종료") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("취소") } })
    if (pendingDraftAction != null) AlertDialog(
        onDismissRequest = { pendingDraftAction = null; draftError = null },
        title = { Text("입력 내용을 저장할까요?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("아직 저장하지 않은 전문 분야·통역 지침이 있습니다. 저장하면 다음 중계에 반영됩니다.")
            draftError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !microphoneBusy && serviceExperience(api).supportsDomainInstructions, onClick = {
            val action = pendingDraftAction
            if (action != null && saveDraft()) { pendingDraftAction = null; completeDraftAction(action) }
        }) { Text("저장하고 계속") } },
        dismissButton = { Column {
            TextButton(onClick = {
                val action = pendingDraftAction
                discardDraft(); pendingDraftAction = null
                if (action != null) completeDraftAction(action)
            }) { Text("입력 버리고 계속") }
            TextButton(onClick = { pendingDraftAction = null; draftError = null }) { Text("편집 계속") }
        } })
    if (contextLibrary) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            // The library owns navigation and unsaved-document confirmation.
            WorkspaceSettingsFooter(workspace.displayedBroadcast, workspace.broadcastActive,
                onBack = null, onStopBroadcast = ::requestEnd, onStopInput = onStopMicrophone,
                stopBroadcastLabel = "방송 종료")
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        RelayContextLibraryScreen(app, onBack = { contextLibrary = false },
            settingsRequest = librarySettingsRequest, onSettingsRequestHandled = { librarySettingsRequest = 0 },
            onOpenSettings = { contextLibrary = false; guardedAction("OPEN_SETTINGS") })
        }
        }
        return
    }
    if (professionalSettings) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = settingsFooter) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).semantics { paneTitle = "전문 분야·내 통역 지침" },
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { guardedAction("CLOSE_PROFESSIONAL") }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("중계 설정으로")
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                RelayProfessionalSettingsCard(app, broadcast, microphoneBusy, domainDraft, instructionDraft,
                    { domainDraft = it; draftError = null }, { instructionDraft = it; draftError = null },
                    ::saveDraft, { guardedAction("DISCARD") }, { guardedAction("LIBRARY") })
            }
        }
        }
        return
    }
    LaunchedEffect(broadcast.recordingId, broadcast.phase) {
        if (!relayMicrophoneRequestMatches(broadcast.recordingId, broadcast) ||
            (pendingMicRecordingId != null && pendingMicRecordingId != broadcast.recordingId)) {
            awaitingPermission = false; consent = false; pendingMicRecordingId = null
            if (pendingDraftAction == "START") pendingDraftAction = null
        }
    }
    LaunchedEffect(microphoneGranted) {
        if (microphoneGranted && awaitingPermission) {
            awaitingPermission = false; pendingMicRecordingId = null
            setupMessage = "마이크 권한이 허용됐습니다. 마이크 켜기를 눌러 통역을 시작하세요."
        }
    }
    inputIssue?.let { issue -> AlertDialog(onDismissRequest = { inputIssue = null },
        title = { Text("마이크 입력을 확인하세요") }, text = { Text(issue.message) },
        confirmButton = { TextButton(onClick = { inputIssue = null }) { Text("확인") } }) }
    if (consent) AlertDialog(onDismissRequest = { consent = false; pendingMicRecordingId = null }, title = { Text("온라인 음성 통역 이용 동의") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (serviceExperience(api).supportsDomainInstructions)
                "통역을 위해 마이크 음성과 선택한 분야·말투·사용자 지침을 선택한 AI 서비스로 전송합니다. 참고 자료 전송을 허용한 경우 짧은 발췌 최대 600자도 보냅니다. 처리·보관·데이터 활용은 제공자의 정책과 계정 설정을 따릅니다."
                else "통역을 위해 마이크 음성을 선택한 AI 서비스로 전송합니다. 이 모델은 분야·말투 지시를 지원하지 않습니다. 처리·보관·데이터 활용은 제공자의 정책과 계정 설정을 따릅니다.")
            Text("사용량에 따라 API 요금이 발생합니다. 예상 비용은 안내용이며 실제 청구는 제공자 기준입니다. 중지하거나 동의를 철회하면 음성 전송이 멈춥니다.")
            Text("선택한 통역 언어 ${relay.targetLanguageTags.size}개에 각각 AI 연결을 사용합니다. 언어 수가 늘면 API 요금도 늘 수 있습니다.")
            Text("동의 후 연결 준비가 끝나면 마이크 입력과 통역 음성 재생을 시작합니다.")
        } }, confirmButton = { TextButton(onClick = {
            app.translationApiSettings.consentToSelectedService(); consent = false; start(consented = true)
        }) { Text("동의하고 마이크 켜기") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("취소") } })
    fun closePickers() { sourcePicker = false; targetPicker = false; monitorPicker = false; microphonePicker = false; voicePicker = false }
    if (voicePicker) AlertDialog(onDismissRequest = { closePickers() },
        title = { Text("통역 음성") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("여성·남성 계열은 음색 분류입니다. 선택은 다음 중계 연결에 적용됩니다.")
            Text("변경 후 중계를 시작할 때 온라인 음성 전송 동의를 다시 확인합니다.", style = MaterialTheme.typography.bodySmall)
            ServiceExperienceChoices("음성 계열", relayVoiceChoices(api).map { choice ->
                ExperienceChoice(choice.name, choice.label, relayVoiceLabel(api.copy(liveVoice = choice)))
            }, api.liveVoice.name, !microphoneBusy && relayVoiceSupported(api)) { id ->
                val current = app.translationApiSettings.state.value
                val choice = RelayVoiceGender.valueOf(id)
                if (choice == current.liveVoice || app.translationApiSettings.configure(current.copy(liveVoice = choice))) {
                    voiceSelectionError = null; closePickers()
                } else voiceSelectionError = "음성 설정을 저장하지 못했습니다. 모델 설정을 확인해 주세요."
            }
            voiceSelectionError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } }, confirmButton = { TextButton(onClick = { closePickers() }) { Text("닫기") } })
    if (targetPicker) RelayPickerDialog(onDismissRequest = { closePickers() },
        title = "통역 언어 · 최대 5개",
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${targetDraft.size}/5개 선택 · 최소 1개를 선택해 주세요.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            OutlinedTextField(value = targetLanguageQuery, onValueChange = { targetLanguageQuery = it },
                label = { Text("언어 검색") }, placeholder = { Text("한국어·English·fr 등") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (targetLanguageQuery.isBlank()) TextButton(enabled = !busy, onClick = {
                targetDraft = recommendedNativeRelayLanguages(relay.source).toCollection(arrayListOf())
                targetSelectionMessage = null
            }) { Text("추천 5개 선택") }
            if (targetLanguageQuery.isBlank()) Text(if (showAllTargetLanguages) "전체 언어" else "기본 언어·현재 선택")
            val visibleTargets = visibleNativeRelayTargetLanguages(relay.source, targetLanguageQuery, showAllTargetLanguages, targetDraft)
            if (visibleTargets.isEmpty()) Text("검색 결과가 없습니다. 언어 이름이나 코드를 확인해 주세요.")
            visibleTargets.forEach { option ->
                val checked = option.languageTag in targetDraft
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = !busy,
                    role = Role.Checkbox, onValueChange = {
                        val result = toggleNativeRelayLanguageSelection(targetDraft.toSet(), option.languageTag, relay.source)
                        targetDraft = result.selectedLanguageTags.toCollection(arrayListOf())
                        targetSelectionMessage = result.message
                    }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                    Text(option.label, modifier = Modifier.weight(1f))
                }
            }
            if (targetLanguageQuery.isBlank()) TextButton(onClick = { showAllTargetLanguages = !showAllTargetLanguages }) {
                Text(if (showAllTargetLanguages) "기본 언어만 보기" else "더 많은 언어 보기")
            }
            targetSelectionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("언어마다 별도 AI 연결을 사용합니다. 선택한 언어 수에 따라 API 요금이 늘 수 있습니다.",
                style = MaterialTheme.typography.bodySmall)
            Text(nativeRelayLanguageSupportNotice(api.provider), style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(enabled = !busy && targetDraft.size in 1..5, onClick = {
            app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.withTargets(targetDraft.toList()))
            closePickers()
        }) { Text("적용") } }, dismissButton = { TextButton(onClick = { closePickers() }) { Text("취소") } })
    if (sourcePicker || monitorPicker || microphonePicker) RelayPickerDialog(onDismissRequest = { closePickers() },
        title = if (sourcePicker) "발화 언어" else if (monitorPicker) "기기에서 들을 통역 언어" else "마이크 선택",
        searchable = sourcePicker,
        text = { Column(if (sourcePicker) Modifier else Modifier.imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sourcePicker) {
                OutlinedTextField(value = sourceLanguageQuery, onValueChange = { sourceLanguageQuery = it },
                    label = { Text("언어 검색") }, placeholder = { Text("한국어·Français·fr 등") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (sourceLanguageQuery.isBlank()) Text(if (showAllSourceLanguages) "전체 언어" else "기본 언어·현재 선택")
            }
            val choices = if (sourcePicker) visibleNativeRelaySourceLanguages(sourceLanguageQuery, showAllSourceLanguages, relay.source)
                .map { ExperienceChoice(it.languageTag, it.label, "") }
                else if (monitorPicker) NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.filter { it.languageTag in relay.targetLanguageTags }
                    .map { ExperienceChoice(it.languageTag, it.label, "") }
                else devices.filter { it.kind !in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER) }
                    .map { ExperienceChoice(it.platformId.toString(), it.label, "") }
            if (sourcePicker && choices.isEmpty()) Text("검색 결과가 없습니다. 언어 이름이나 코드를 확인해 주세요.")
            ServiceExperienceChoices("선택", choices,
                if (sourcePicker) relay.source else if (monitorPicker) relay.target else selected?.platformId.toString(), if (sourcePicker) !busy else !microphoneBusy) { id ->
                if (sourcePicker) {
                    app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.withSource(id))
                } else if (monitorPicker) app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.copy(target = id))
                else app.audioInputRepository.selectDevice(id.toInt())
                closePickers()
            }
            if (sourcePicker && sourceLanguageQuery.isBlank()) TextButton(onClick = { showAllSourceLanguages = !showAllSourceLanguages }) {
                Text(if (showAllSourceLanguages) "기본 언어만 보기" else "더 많은 언어 보기")
            }
            if (sourcePicker) {
                Text("발화 언어와 같은 통역 언어는 제외됩니다. 나머지 선택은 유지합니다.", style = MaterialTheme.typography.bodySmall)
                Text(nativeRelayLanguageSupportNotice(api.provider), style = MaterialTheme.typography.bodySmall)
            }
        } }, confirmButton = { TextButton(onClick = { closePickers() }) { Text("닫기") } })
    if (showSettings) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = settingsFooter) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().semantics { paneTitle = "중계 설정" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onClick = {
                    if (setupFocus != null) { setupFocus = null; setupMessage = null }
                    else guardedAction("CLOSE_SETTINGS")
                }) { Text(if (setupFocus != null) "설정 메뉴로" else "운영 화면으로") }
                Text(setupFocus?.label ?: "중계 설정", style = MaterialTheme.typography.headlineSmall)
                setupMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                relaySettingsLockHint(setupFocus, busy, microphoneBusy, broadcast.inputStopping)?.let { Text(it) }
            }
            if (setupFocus == null) {
                RelaySetupItem.entries.filter { it !in setOf(RelaySetupItem.KEY, RelaySetupItem.MODEL) }.forEach { entry -> item(key = entry.name) {
                    OutlinedButton(onClick = { setupFocus = entry; setupMessage = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(entry.label)
                            Text(when (entry) {
                                RelaySetupItem.INPUT -> selected?.label ?: "선택 필요"
                                RelaySetupItem.LANGUAGES -> "${NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.find { it.languageTag == relay.source }?.label?.substringBefore(" · ") ?: relay.source} → " +
                                    relay.targetLanguageTags.joinToString(", ") { tag -> NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == tag }?.label?.substringBefore(" · ") ?: tag }
                                RelaySetupItem.OUTPUT -> "기기 재생 ${if (relay.localPlayback) "켬" else "끔"} · 웹오디오방송 ${if (relay.networkBroadcast) "켬" else "끔"}"
                                RelaySetupItem.VOICE -> relayVoiceLabel(api)
                                RelaySetupItem.SERVICE -> "${api.provider.label} · ${api.model} · ${if (api.hasKey) "키 준비됨" else "키 입력 필요"}"
                                RelaySetupItem.PROFESSIONAL -> api.domainPrompt.ifBlank { "선택 사항" }.take(40)
                                RelaySetupItem.COMPARISON -> comparisonChoice.status
                                else -> ""
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
            } else when (setupFocus) {
                RelaySetupItem.INPUT -> item {
                    OutlinedButton(onClick = { microphonePicker = true }, enabled = !microphoneBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("마이크 · ${selected?.label ?: "선택 필요"}")
                    }
                    if (!microphoneGranted) Button(onClick = onRequestMicrophone, enabled = !microphoneBusy) { Text("마이크 접근 허용") }
                    Text("음성 입력 처리 · 마이크 켜기/끄기는 운영 화면에서 따로 조작합니다.", style = MaterialTheme.typography.bodySmall)
                    if (broadcast.isInterpreterRelay) {
                        Text("마이크 · ${if (broadcast.inputDraining) "꺼짐 · 남은 통역 처리 중" else if (broadcast.inputStopping) "끄는 중" else when (broadcast.inputPhase) {
                            InputPhase.ACTIVE -> "입력 중"; InputPhase.STARTING -> "준비 중";
                            InputPhase.FAILED -> "입력 중단"; else -> "꺼짐" }}")
                        LinearProgressIndicator(progress = { broadcast.inputPeak.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("통역 · ${broadcast.relayPhase.shortLabel} · ${if (!relay.localPlayback) "기기 재생 끔"
                            else if (broadcast.relayPlayedBytes > 0) "기기 음성 출력 확인됨" else "기기 재생 대기"}")
                        broadcast.inputProcessingSummary?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (broadcast.inputPhase == InputPhase.ACTIVE) {
                            Text(levelGuidance.label, color = if (levelGuidance.attention) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface)
                            Text(levelGuidance.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        (broadcast.inputErrorMessage ?: broadcast.errorMessage ?: broadcast.translationWarning)?.let { ServiceConnectionFeedback(it) }
                        broadcast.recordingWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    MicrophoneNoiseOptions(microphoneProfile.noiseMode, !microphoneBusy,
                        { app.microphoneNoiseSettings.selectProfile(microphoneGroup, microphoneProfile.copy(noiseMode = it)) },
                        microphoneProfile.nearSpeakerFocus,
                        { app.microphoneNoiseSettings.selectProfile(microphoneGroup, microphoneProfile.copy(nearSpeakerFocus = it)) },
                        microphoneGroup.label)
                    if (relay.localPlayback && microphoneProfile.noiseMode != MicrophoneNoiseMode.OFF)
                        Text("중계에서는 기기 재생음의 재입력을 줄이도록 단말 에코 제거도 요청합니다. 실제 적용 여부는 입력 상태에 표시됩니다.", style = MaterialTheme.typography.bodySmall)
                }
                RelaySetupItem.LANGUAGES -> item {
                    Text("아래에서 실제 통역할 언어를 선택해 적용합니다. 운영 화면의 ‘자막 표시’ 버튼은 보이는 자막만 바꾸며 AI 출력 언어를 바꾸지 않습니다.",
                        style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { sourceLanguageQuery = ""; showAllSourceLanguages = false; sourcePicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("발화 · ${NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.find { it.languageTag == relay.source }?.label ?: relay.source}") }
            OutlinedButton(onClick = {
                targetDraft = relay.targetLanguageTags.toCollection(arrayListOf())
                targetSelectionMessage = null; targetLanguageQuery = ""; showAllTargetLanguages = false; targetPicker = true
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("통역 언어 · ${relay.targetLanguageTags.size}/5개 · ${relay.targetLanguageTags.joinToString(", ") { tag -> NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == tag }?.label?.substringBefore(" · ") ?: tag }}")
            }

                }
                RelaySetupItem.OUTPUT -> item {
            ServiceExperienceToggle("기기에서 통역 음성 듣기", "현재 연결된 스피커·이어폰·Bluetooth로 재생합니다. 마이크 재입력을 줄이려면 이어폰을 사용하세요.", relay.localPlayback, !busy) {
                app.interpreterRelaySettings.update(relay.copy(localPlayback = it)) }
            if (relay.localPlayback) {
                OutlinedButton(onClick = { monitorPicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("기기에서 들을 언어 · ${NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == relay.target }?.label ?: relay.target}")
                }
                Text("기기에서는 한 언어를 듣고, 스크립트에서는 선택한 모든 언어를 읽습니다. 웹오디오방송을 켜면 모든 통역 언어를 공유합니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
            ServiceExperienceToggle("웹오디오방송", "같은 Wi-Fi·핫스팟의 청취자에게 같은 음성을 공유합니다. 청취자마다 API를 다시 호출하지 않습니다.", relay.networkBroadcast, !busy) {
                app.interpreterRelaySettings.update(relay.copy(networkBroadcast = it)) }
                }
                RelaySetupItem.VOICE -> item {
            if (relayVoiceSupported(api)) {
                OutlinedButton(onClick = { voiceSelectionError = null; voicePicker = true }, enabled = !microphoneBusy,
                    modifier = Modifier.fillMaxWidth()) { Text("통역 음성 · ${relayVoiceLabel(api)}") }
                Text("${RelayVoiceGender.AUTO.label}·여성 계열·남성 계열을 선택합니다. 마이크를 끈 뒤 음성을 바꿀 수 있습니다. 다음 마이크 입력부터 적용됩니다.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (api.provider == TranslationApiProvider.GEMINI_LIVE && api.model == GEMINI_LIVE_TRANSLATE) {
                Text("원문 목소리의 처리는 Live Translate 모델이 결정합니다. 음성 계열 선택은 지원하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
            }

                }
                RelaySetupItem.PROFESSIONAL -> item {
                    OutlinedButton(onClick = { professionalSettings = true }, enabled = !microphoneBusy, modifier = Modifier.fillMaxWidth()) {
                        Text("전문 분야·내 통역 지침 열기")
                    }
                }
                RelaySetupItem.KEY, RelaySetupItem.MODEL, RelaySetupItem.SERVICE -> item {
                    if (draftDirty) Text("분야·지침을 저장하거나 입력을 취소한 뒤 AI 설정을 바꿀 수 있습니다.")
                    key(setupFocus) {
                        TranslationApiPanel(app.translationApiSettings, app.translationApiService, !microphoneBusy && !draftDirty,
                            liveMonitor = app.geminiLiveMonitor, nativeOnly = true,
                            openItem = when (setupFocus) { RelaySetupItem.KEY -> "key"; RelaySetupItem.MODEL -> "model";
                                else -> "service".takeIf { setupMessage != null } })
                    }
                }
                RelaySetupItem.COMPARISON -> item {
            Text("중계 결과 자동 비교 · ${learning.completed}건 비교 완료 (승인·적용 횟수 아님) · ${learning.incomplete}건 미완료 · ${learning.skipped}건 건너뜀")
            learning.lastPause?.let { Text(it) }
            LearningComparisonReview(if (app.translationApiSettings.authorized(api)) learning.last else null, app.domainCorpus,
                commitAdmission = app::nativeComparisonCommitAdmission, isComparisonCurrent = app::isNativeComparisonCurrent,
                showEntry = learning.last != null)
            ReviewedRelayComparisonUi(app, broadcast, onOpenExampleMaterials = { guardedAction("LIBRARY") })
            ServiceExperienceToggle("오프라인 결과와 비교 (선택)",
                "기기에서 들을 언어를 오프라인 번역과 비교합니다. 지원하지 않는 모델은 비교를 보류합니다. " + if (learningSupported)
                    "확정된 원문·통역 쌍과 준비된 Gemma 모델을 사용하며 추가 API 요청은 없습니다. 비교 결과는 직접 검수 대기로 남으며, 저장한 예문은 조건이 맞는 준비된 로컬 자료에서 재사용합니다."
                else comparisonChoice.detail,
                comparisonChoice.checked, learningSupported) {
                app.interpreterRelaySettings.update(relay.copy(compareOffline = it)) }
            if (automaticExampleControls != null) automaticExampleControls()
            else AutomaticExampleControls(app.automaticTranslationExamples, !busy && !microphoneBusy && !draftDirty, reviewRepository = app.domainCorpus)
            DeferredNativeLearningControls(app, !busy && !microphoneBusy && !draftDirty)
            if (!learningSupported) Text(NativeLearningPause.ALIGNMENT.label)
            else Text("오프라인 모델이 없거나 바쁘면 비교만 건너뜁니다. 검수 자료 재사용이며 모델 가중치 학습이 아닙니다.")
            if (learningSupported) Text("비교 선택을 바꾸면 다음 중계 시작부터 적용됩니다. 진행 중인 입력은 소급 비교하지 않습니다.")
            TextButton(onClick = { showUsage = !showUsage }) { Text(if (showUsage) "사용량 상세 접기" else "사용량·재생 상세") }
            if (showUsage) Text("연결 시도 ${live.connections}회 · 최근 제공자 보고 · 입력 음성 ${live.inputAudioTokens ?: "미확인"} · 출력 음성 ${live.outputAudioTokens ?: "미확인"} · 입력 문자 ${live.inputTextTokens ?: "미확인"} · 출력 문자 ${live.outputTextTokens ?: "미확인"} 토큰 · 기기 재생 ${broadcast.relayPlayedBytes} bytes")
                }
                null -> Unit
            }
            if (setupFocus != null) item {
                Button(onClick = { guardedAction("CLOSE_SETTINGS") }, modifier = Modifier.fillMaxWidth()) { Text("운영 화면으로") }
            }
        }
        }
        return
    }
    val validTitle = relay.broadcastTitle.isBlank() || normalizedRecordingTitle(relay.broadcastTitle) != null
    if (titleDialog) AlertDialog(onDismissRequest = { titleDialog = false }, title = { Text("방송 제목") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) Text(broadcast.broadcastTitle.ifBlank { "제목 없는 방송" })
            else OutlinedTextField(value = relay.broadcastTitle, onValueChange = {
                if (it.codePointCount(0, it.length) <= RECORDING_TITLE_MAX_CODE_POINTS)
                    app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.copy(broadcastTitle = it))
            }, label = { Text("방송 제목 (선택)") }, placeholder = { Text("예: 오늘의 한국어 강의") },
                singleLine = true, isError = !validTitle, modifier = Modifier.fillMaxWidth())
            Text(if (busy) "방송 제목은 방송 이력에서 바꿀 수 있습니다."
                else "비워 두어도 방송할 수 있습니다. 제목은 방송 이력에 저장됩니다.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { titleDialog = false }) { Text("닫기") } })
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        StreamingWorkspaceFooter(workspace.displayedBroadcast, workspace.broadcastActive, standalone = false,
            otherBroadcastActive = workspace.otherActive, inputKind = selected?.kind, inputRequestPending = false,
            onStartBroadcast = { guardedAction("START_BROADCAST") },
            onStopBroadcast = ::requestEnd,
            onStartInput = { start() }, onPauseInput = onStopMicrophone,
            onOpenHud = { onOpenFilteredHud?.invoke(visibleCaptionTargets, captionDisplaySource) ?: onOpenHud() },
            startBroadcastEnabled = validTitle, startInputEnabled = workspace.startInputEnabled,
            stopBroadcastLabel = "방송 종료")
    }) { padding ->
        StreamingTranscriptWorkspace(workspace.displayedBroadcast, captionLanguages, visibleCaptionTargets,
            showSource = captionDisplaySource,
            onToggleSource = { captionDisplaySource = !captionDisplaySource },
            onToggleTarget = { tag -> captionDisplayTargets = ArrayList(toggleWorkspaceDisplayLanguage(
                captionLanguages, captionDisplayTargets, tag)) },
            onOpenSettings = { showSettings = true; setupFocus = RelaySetupItem.LANGUAGES; setupMessage = null },
            onOpenStatus = {
                val destination = relayRecoverySetting(app.translationApiSettings.state.value,
                    app.interpreterRelaySettings.state.value, app.audioInputRepository.selectedDevice.value?.kind)
                openSetup(destination)
            },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).semantics { paneTitle = "통역 중계" },
            additionalIssue = if (workspace.otherActive) "다른 서비스에서 방송 또는 입력을 사용 중입니다. 해당 서비스에서 종료하세요."
                else broadcast.recordingWarning ?: endActionMessage ?: levelGuidance.label.takeIf {
                    broadcast.isInterpreterRelay && broadcast.inputPhase == InputPhase.ACTIVE && levelGuidance.attention },
            emptyMessage = "방송 시작을 누른 뒤 마이크를 켜세요. 인식한 원문과 선택한 통역 자막이 여기에 표시됩니다.",
            displaySelectionLabel = "자막 표시",
            operatingActions = {
                if (workspace.broadcastActive && workspace.displayedBroadcast.listenerUrl != null)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        RelayListenerAccessCard(workspace.displayedBroadcast.listenerUrl, true, compact = true)
                    }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { titleDialog = true }, enabled = !workspace.otherActive && !workspace.cleanupRetry,
                        modifier = Modifier.weight(1f)) {
                        Text(if (workspace.broadcastActive) broadcast.broadcastTitle.ifBlank { "방송 제목" }
                            else relay.broadcastTitle.ifBlank { "방송 제목 (선택)" }, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (workspace.broadcastActive && broadcast.phase == BroadcastPhase.LIVE)
                        TextButton(onClick = onPause) { Text("송출 일시정지") }
                    if (workspace.broadcastActive && broadcast.phase == BroadcastPhase.PAUSED)
                        TextButton(onClick = onResume) { Text("송출 재개") }
                }
            })
    }
}

internal data class RelayWorkspaceControlState(val displayedBroadcast: BroadcastSnapshot,
    val broadcastActive: Boolean, val otherActive: Boolean, val startInputEnabled: Boolean, val cleanupRetry: Boolean)

internal fun relayWorkspaceControlState(broadcast: BroadcastSnapshot, menuActive: Boolean,
    streamingOwnerPresent: Boolean): RelayWorkspaceControlState {
    val activePhase = broadcast.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED)
    val cleanupPending = broadcast.phase == BroadcastPhase.FAILED && streamingOwnerPresent
    val own = broadcast.isInterpreterRelay
    val other = menuActive || (!own && !cleanupPending && (activePhase || broadcast.inputStopping ||
        broadcast.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE, InputPhase.PAUSED)))
    val shown = when {
        own -> broadcast
        cleanupPending -> BroadcastSnapshot(phase = BroadcastPhase.FAILED, errorMessage = broadcast.errorMessage,
            inputStopping = broadcast.inputStopping)
        else -> BroadcastSnapshot()
    }
    return RelayWorkspaceControlState(shown, own && activePhase || cleanupPending, other,
        own && broadcast.phase in setOf(BroadcastPhase.LIVE, BroadcastPhase.PAUSED) && !other,
        cleanupPending)
}

internal fun relayEndRequestMatches(current: BroadcastSnapshot, expectedRecordingId: String?, wasStarting: Boolean,
    capturedInputEpoch: Long, currentInputEpoch: Long, cleanupRequested: Boolean, streamingOwnerPresent: Boolean): Boolean {
    if (cleanupRequested) return current.phase == BroadcastPhase.FAILED && streamingOwnerPresent
    if (!current.isInterpreterRelay || current.phase !in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
        current.recordingId != expectedRecordingId) return false
    return expectedRecordingId != null || (wasStarting && current.phase == BroadcastPhase.STARTING &&
        capturedInputEpoch == currentInputEpoch)
}

internal val InterpreterRelayPhase.label: String get() = when (this) {
    InterpreterRelayPhase.IDLE -> "대기"
    InterpreterRelayPhase.CONNECTING -> "연결 중 · 아직 음성을 보내지 않습니다"
    InterpreterRelayPhase.READY -> "연결 준비됨"
    InterpreterRelayPhase.RECEIVING -> "통역 수신 중"
    InterpreterRelayPhase.PAUSED -> "일시정지 · 전송·재생 중지됨"
    InterpreterRelayPhase.FAILED -> "연결 중단 · 중지 후 다시 시작하세요"
}

internal val InterpreterRelayPhase.shortLabel: String get() = when (this) {
    InterpreterRelayPhase.IDLE -> "대기"
    InterpreterRelayPhase.CONNECTING -> "연결 중"
    InterpreterRelayPhase.READY -> "준비됨"
    InterpreterRelayPhase.RECEIVING -> "수신 중"
    InterpreterRelayPhase.PAUSED -> "일시정지"
    InterpreterRelayPhase.FAILED -> "중단"
}

@Composable
private fun RelayPickerDialog(
    title: String,
    onDismissRequest: () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    searchable: Boolean = true,
) {
    if (!searchable) {
        AlertDialog(onDismissRequest = onDismissRequest, title = { Text(title) }, text = text,
            confirmButton = confirmButton, dismissButton = dismissButton)
        return
    }
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                Column(Modifier.semantics { paneTitle = title }) {
                    Text(title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        dismissButton()
                        confirmButton()
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                    }
                }
            }
        }
    }
}
