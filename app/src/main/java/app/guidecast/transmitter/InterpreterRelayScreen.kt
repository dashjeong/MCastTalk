package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioInputKind

/** One native audio connection. Streaming language/model choices remain in their own workspace. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InterpreterRelayScreen(app: GuideCastApplication, broadcast: BroadcastSnapshot,
    microphoneGranted: Boolean, onRequestMicrophone: () -> Unit, onStart: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, onBack: () -> Unit) {
    val api by app.translationApiSettings.state.collectAsState()
    LaunchedEffect(api.revision) { app.interpreterRelaySettings.rememberRelayApi(app.translationApiSettings.state.value) }
    var contextLibrary by rememberSaveable { mutableStateOf(false) }
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
    val busy = broadcast.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED)
    val comparisonChoice = nativeComparisonPresentation(api, relay.compareOffline, busy, broadcast.relayContext?.model,
        learning.nativeControlGeneration, app.interpreterRelaySettings.comparisonGeneration)
    val learningSupported = comparisonChoice.supported
    var sourcePicker by remember { mutableStateOf(false) }
    var targetPicker by remember { mutableStateOf(false) }
    var microphonePicker by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(!busy) }
    var showUsage by remember { mutableStateOf(false) }
    var inputIssue by remember { mutableStateOf<RelayInputIssue?>(null) }
    LaunchedEffect(busy) { if (busy) showSettings = false }
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
    fun start(consented: Boolean = false, useSavedDraft: Boolean = false) {
        if (!useSavedDraft && draftDirty) { pendingDraftAction = "START"; return }
        inputIssue = relayInputIssue(selected?.kind, true,
            runCatching { app.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute }.getOrNull())
        if (inputIssue != null) return
        if (!microphoneGranted) { awaitingPermission = true; onRequestMicrophone(); return }
        val currentApi = app.translationApiSettings.state.value
        if (!consented && (!app.translationApiSettings.authorized(currentApi) || !currentApi.allowLiveAudio)) { consent = true; return }
        onStart()
    }
    fun completeDraftAction(action: String) {
        when (action) {
            "START" -> start(useSavedDraft = true)
            "LIBRARY" -> contextLibrary = true
            "BACK" -> onBack()
            "CLOSE_PROFESSIONAL" -> professionalSettings = false
            "CLOSE_SETTINGS" -> showSettings = false
            "DISCARD" -> discardDraft()
        }
    }
    fun guardedAction(action: String) {
        if (draftDirty) { draftError = null; pendingDraftAction = action }
        else completeDraftAction(action)
    }
    BackHandler(enabled = !contextLibrary && (professionalSettings || (!busy && draftDirty))) {
        guardedAction(if (professionalSettings) "CLOSE_PROFESSIONAL" else "BACK")
    }
    if (pendingDraftAction != null) AlertDialog(
        onDismissRequest = { pendingDraftAction = null; draftError = null },
        title = { Text("입력 내용을 저장할까요?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("아직 저장하지 않은 전문 분야·통역 지침이 있습니다. 저장하면 다음 중계에 반영됩니다.")
            draftError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy && serviceExperience(api).supportsDomainInstructions, onClick = {
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
    if (contextLibrary) { RelayContextLibraryScreen(app, onBack = { contextLibrary = false }); return }
    if (professionalSettings) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).semantics { paneTitle = "전문 분야·내 통역 지침" },
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { guardedAction("CLOSE_PROFESSIONAL") }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("On-통 Live(AI 통역)로")
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                RelayProfessionalSettingsCard(app, broadcast, busy, domainDraft, instructionDraft,
                    { domainDraft = it; draftError = null }, { instructionDraft = it; draftError = null },
                    ::saveDraft, { guardedAction("DISCARD") }, { guardedAction("LIBRARY") })
            }
        }
        return
    }
    LaunchedEffect(microphoneGranted) {
        if (microphoneGranted && awaitingPermission) { awaitingPermission = false; start() }
    }
    inputIssue?.let { issue -> AlertDialog(onDismissRequest = { inputIssue = null },
        title = { Text("마이크 입력을 확인하세요") }, text = { Text(issue.message) },
        confirmButton = { TextButton(onClick = { inputIssue = null }) { Text("확인") } }) }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text("온라인 음성 통역 이용 동의") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (serviceExperience(api).supportsDomainInstructions)
                "통역을 위해 마이크 음성과 선택한 분야·말투·사용자 지침을 선택한 AI 서비스로 전송합니다. 참고 자료 전송을 허용한 경우 짧은 발췌 최대 600자도 보냅니다. 처리·보관·데이터 활용은 제공자의 정책과 계정 설정을 따릅니다."
                else "통역을 위해 마이크 음성을 선택한 AI 서비스로 전송합니다. 이 모델은 분야·말투 지시를 지원하지 않습니다. 처리·보관·데이터 활용은 제공자의 정책과 계정 설정을 따릅니다.")
            Text("사용량에 따라 API 요금이 발생합니다. 예상 비용은 안내용이며 실제 청구는 제공자 기준입니다. 중지하거나 동의를 철회하면 음성 전송이 멈춥니다.")
            Text("동의 후 연결 준비가 끝나면 마이크 입력과 통역 음성 재생을 시작합니다.")
        } }, confirmButton = { TextButton(onClick = {
            app.translationApiSettings.consentToSelectedService(); consent = false; start(consented = true)
        }) { Text("동의하고 중계 시작") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("취소") } })
    fun closePickers() { sourcePicker = false; targetPicker = false; microphonePicker = false }
    if (sourcePicker || targetPicker || microphonePicker) AlertDialog(onDismissRequest = { closePickers() },
        title = { Text(if (sourcePicker) "발화 언어" else if (targetPicker) "통역 출력 언어 · 하나 선택" else "마이크 선택") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val choices = if (sourcePicker) SOURCE_LANGUAGE_OPTIONS.map { ExperienceChoice(it.languageTag, it.label, "") }
                else if (targetPicker) translationTargetLanguageOptions(relay.source).map { ExperienceChoice(it.languageTag, it.label, "") }
                else devices.filter { it.kind !in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER) }
                    .map { ExperienceChoice(it.platformId.toString(), it.label, "") }
            ServiceExperienceChoices("선택", choices,
                if (sourcePicker) relay.source else if (targetPicker) relay.target else selected?.platformId.toString(), !busy) { id ->
                if (sourcePicker) {
                    val targets = translationTargetLanguageOptions(id)
                    app.interpreterRelaySettings.update(relay.copy(source = id,
                        target = relay.target.takeIf { old -> targets.any { it.languageTag == old } } ?: targets.first().languageTag))
                } else if (targetPicker) app.interpreterRelaySettings.update(relay.copy(target = id))
                else app.audioInputRepository.selectDevice(id.toInt())
                closePickers()
            }
        } }, confirmButton = { TextButton(onClick = { closePickers() }) { Text("닫기") } })
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = "On-통 Live(AI 통역)" }, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TextButton(onClick = { guardedAction("BACK") }) { Text("서비스 목록") }
            Text("On-통 Live(AI 통역)", style = MaterialTheme.typography.headlineMedium)
            Text("마이크 → 선택한 Live API → 통역 음성·자막. 로컬 음성 모델이나 음성팩 준비 없이 사용합니다.")
            Text("원음·통역 음성과 스크립트는 이 기기에 저장됩니다. 방송 이력에서 다시 듣기·삭제·내려받기·공유할 수 있습니다.")
            broadcast.recordingWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (busy) stickyHeader {
            Surface(Modifier.fillMaxWidth()) {
                Column {
                    Text("On-통 Live(AI 통역) · ${broadcast.relayPhase.shortLabel}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (broadcast.phase == BroadcastPhase.PAUSED) Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("재개") }
                        else OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("일시정지") }
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("중지") }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("중계 상태 · ${if (busy) broadcast.relayPhase.shortLabel else broadcast.relayPhase.label}", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("입력 · ${if (broadcast.inputPhase == InputPhase.ACTIVE) "마이크 입력 중" else when (broadcast.inputPhase) { InputPhase.IDLE -> "대기"; InputPhase.STARTING -> "마이크 준비 중"; InputPhase.PAUSED -> "일시정지"; InputPhase.FAILED -> "입력 중단"; else -> "입력 중" }}")
                if (busy) broadcast.relayContext?.let { context ->
                    Text("연결 모델 · ${context.model} · 분야 ${context.domain.ifBlank { "설정 안 함" }}")
                    Text("참고 발췌 ${broadcast.relayReferenceCharacters}자 · 사용자 지침 ${context.instructionCharacters}자", style = MaterialTheme.typography.bodySmall)
                }
                Text(comparisonChoice.status, style = MaterialTheme.typography.labelLarge)
                if (!learningSupported || (busy && learning.nativeControlGeneration != null &&
                    learning.nativeControlGeneration != app.interpreterRelaySettings.comparisonGeneration))
                    Text(comparisonChoice.detail, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { broadcast.inputPeak.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("수신 · ${if (broadcast.relayPhase == InterpreterRelayPhase.RECEIVING) "API 음성 수신 중" else "대기"} · ${if (!relay.localPlayback) "기기 재생 끔" else if (broadcast.relayPlayedBytes > 0) "기기 음성 재생 중" else "기기 재생 대기"}")
                if (!busy) Text("기기 재생 기록과 청취자의 실제 청취는 별도입니다.", style = MaterialTheme.typography.bodySmall)
                (broadcast.inputErrorMessage ?: broadcast.errorMessage ?: broadcast.translationWarning.takeIf {
                    !busy || broadcast.relayPhase == InterpreterRelayPhase.FAILED
                })?.let { ServiceConnectionFeedback(it) }
                if (!busy) OutlinedButton(onClick = { professionalSettings = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("전문 분야·내 통역 지침")
                }
                if (!busy && draftDirty) Text("저장하지 않은 통역 지침이 있습니다. 시작 전에 저장 여부를 확인합니다.",
                    style = MaterialTheme.typography.bodySmall)
                if (!busy) Button(onClick = { start() }, enabled = api.hasKey && api.usesNativeLiveAudio &&
                    (relay.localPlayback || relay.networkBroadcast) && selected != null && selected?.kind !in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER), modifier = Modifier.fillMaxWidth()) { Text("중계 시작") }
                if (!relay.localPlayback && !relay.networkBroadcast) Text("음성을 들으려면 기기 재생 또는 LAN 방송을 켜 주세요.")
                if (!api.hasKey) Text(if (showSettings) "아래에서 API 키를 입력해 주세요." else "중계 설정을 펼쳐 API 키를 입력해 주세요.")
                if (selected == null || selected?.kind in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER)) Text("마이크를 선택해 주세요.")

            } }
        }
        if (relay.networkBroadcast) item {
            RelayListenerAccessCard(broadcast.listenerUrl, busy)
        }
        item { Text("원문 · 통역 자막", style = MaterialTheme.typography.titleLarge) }
        if (broadcast.transcripts.isEmpty()) item { Text("발화하면 원문과 통역 자막을 표시합니다. 자막 지연 중에도 음성 수신·재생은 이어집니다.") }
        broadcast.transcripts.takeLast(30).asReversed().forEach { line -> item(key = line.sequence) {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("원문 · ${line.sourceStatusLabel}", style = MaterialTheme.typography.labelLarge)
                Text(line.sourceText.ifBlank { "전사 대기" })
                Text("${TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == relay.target }?.label} · ${line.liveOutputState?.label ?: if (line.isFinal) "완료" else "처리 중"}", style = MaterialTheme.typography.labelLarge)
                Text(line.translations[relay.target].orEmpty().ifBlank { "전사 대기" })
                line.liveStatusLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        } }
        if (learningSupported && relay.compareOffline) item {
            Text("오프라인 비교 · ${learning.completed}건 완료 · ${learning.incomplete}건 미완료 · ${learning.skipped}건 건너뜀")
            learning.lastPause?.let { Text(it) }
            LearningComparisonReview(if (app.translationApiSettings.authorized(api)) learning.last else null, app.domainCorpus,
                commitAdmission = app::nativeComparisonCommitAdmission, isComparisonCurrent = app::isNativeComparisonCurrent)
        }
        item {
            OutlinedButton(onClick = { if (showSettings) guardedAction("CLOSE_SETTINGS") else showSettings = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showSettings) "중계 설정 접기" else "중계 설정 · ${if (busy) broadcast.relayContext?.model ?: "연결 준비 중" else api.model} · ${relay.target}")
            }
        }
        if (showSettings) {
        item {
            OutlinedButton(onClick = { microphonePicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("마이크 · ${selected?.label ?: "선택 필요"}") }
            OutlinedButton(onClick = { sourcePicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("발화 · ${SOURCE_LANGUAGE_OPTIONS.find { it.languageTag == relay.source }?.label ?: relay.source}") }
            OutlinedButton(onClick = { targetPicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("통역 · ${TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == relay.target }?.label} · 단일 언어") }
            ServiceExperienceToggle("기기에서 통역 음성 듣기", "현재 연결된 스피커·이어폰·Bluetooth로 재생합니다. 마이크 재입력을 줄이려면 이어폰을 사용하세요.", relay.localPlayback, !busy) {
                app.interpreterRelaySettings.update(relay.copy(localPlayback = it)) }
            ServiceExperienceToggle("LAN 청취자에게 방송", "같은 Wi-Fi·핫스팟의 청취자에게 같은 음성을 공유합니다. 청취자마다 API를 다시 호출하지 않습니다.", relay.networkBroadcast, !busy) {
                app.interpreterRelaySettings.update(relay.copy(networkBroadcast = it)) }
        }
        item {
            OutlinedButton(onClick = { professionalSettings = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("전문 통역 자료·지침")
            }
        }
        item {
            if (draftDirty) Text("분야·지침을 저장하거나 입력을 취소한 뒤 AI 설정을 바꿀 수 있습니다.")
            TranslationApiPanel(app.translationApiSettings, app.translationApiService, !busy && !draftDirty, liveMonitor = app.geminiLiveMonitor, nativeOnly = true)
        }
        item {
            ServiceExperienceToggle("오프라인 결과와 비교 (선택)", if (learningSupported)
                "확정된 원문·통역 쌍을 준비된 Gemma 모델과 비교합니다. 온라인 응답을 재사용하며 추가 API 요청은 없습니다. 검수·저장한 예문만 다음 오프라인 번역에 적용됩니다."
                else comparisonChoice.detail,
                comparisonChoice.checked, learningSupported) {
                app.interpreterRelaySettings.update(relay.copy(compareOffline = it)) }
            if (!learningSupported) Text(NativeLearningPause.ALIGNMENT.label)
            else Text("오프라인 모델이 없거나 바쁘면 비교만 건너뜁니다. 검수 자료 재사용이며 모델 가중치 학습이 아닙니다.")
            if (learningSupported) Text("비교 선택을 바꾸면 다음 중계 시작부터 적용됩니다. 진행 중인 입력은 소급 비교하지 않습니다.")
            TextButton(onClick = { showUsage = !showUsage }) { Text(if (showUsage) "사용량 상세 접기" else "사용량·재생 상세") }
            if (showUsage) Text("연결 시도 ${live.connections}회 · 최근 제공자 보고 · 입력 음성 ${live.inputAudioTokens ?: "미확인"} · 출력 음성 ${live.outputAudioTokens ?: "미확인"} · 입력 문자 ${live.inputTextTokens ?: "미확인"} · 출력 문자 ${live.outputTextTokens ?: "미확인"} 토큰 · 기기 재생 ${broadcast.relayPlayedBytes} bytes")
        }

        }
    }
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
