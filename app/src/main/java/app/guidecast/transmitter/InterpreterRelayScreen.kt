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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioInputKind

/** Native audio relay workspace. Streaming language/model choices remain in their own workspace. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InterpreterRelayScreen(app: GuideCastApplication, broadcast: BroadcastSnapshot,
    microphoneGranted: Boolean, onRequestMicrophone: () -> Unit, onStart: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit, onBack: () -> Unit,
    onOpenHud: () -> Unit = {}, settingsRequest: Int = 0, onSettingsRequestHandled: () -> Unit = {}) {
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
    var sourcePicker by rememberSaveable { mutableStateOf(false) }
    var targetPicker by rememberSaveable { mutableStateOf(false) }
    var targetDraft by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var targetSelectionMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var monitorPicker by rememberSaveable { mutableStateOf(false) }
    var voicePicker by rememberSaveable { mutableStateOf(false) }
    var voiceSelectionError by remember { mutableStateOf<String?>(null) }
    var microphonePicker by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var awaitingPermission by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var setupFocus by rememberSaveable { mutableStateOf<RelaySetupItem?>(null) }
    var setupMessage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > 0) { showSettings = true; setupFocus = null; setupMessage = null; onSettingsRequestHandled() }
    }
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
    fun start(consented: Boolean = false, useSavedDraft: Boolean = false) {
        if (!useSavedDraft && draftDirty) { pendingDraftAction = "START"; return }
        val currentApi = app.translationApiSettings.state.value
        val missing = relayRequiredSetting(currentApi, app.interpreterRelaySettings.state.value,
            app.audioInputRepository.selectedDevice.value?.kind)
        if (missing != null) { showSettings = true; setupFocus = missing; setupMessage = missing.message; return }
        showSettings = false
        inputIssue = relayInputIssue(selected?.kind, true,
            runCatching { app.getSystemService(android.media.AudioManager::class.java).isMicrophoneMute }.getOrNull())
        if (inputIssue != null) return
        if (!microphoneGranted) { awaitingPermission = true; onRequestMicrophone(); return }
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
    BackHandler(enabled = !contextLibrary && (professionalSettings || showSettings || (!busy && draftDirty))) {
        if (showSettings && setupFocus != null && !professionalSettings) { setupFocus = null; setupMessage = null }
        else guardedAction(if (professionalSettings) "CLOSE_PROFESSIONAL" else if (showSettings) "CLOSE_SETTINGS" else "BACK")
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
                Text("중계 설정으로")
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
            Text("선택한 통역 언어 ${relay.targetLanguageTags.size}개에 각각 AI 연결을 사용합니다. 언어 수가 늘면 API 요금도 늘 수 있습니다.")
            Text("동의 후 연결 준비가 끝나면 마이크 입력과 통역 음성 재생을 시작합니다.")
        } }, confirmButton = { TextButton(onClick = {
            app.translationApiSettings.consentToSelectedService(); consent = false; start(consented = true)
        }) { Text("동의하고 중계 시작") } }, dismissButton = { TextButton(onClick = { consent = false }) { Text("취소") } })
    fun closePickers() { sourcePicker = false; targetPicker = false; monitorPicker = false; microphonePicker = false; voicePicker = false }
    if (voicePicker) AlertDialog(onDismissRequest = { closePickers() },
        title = { Text("통역 음성") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("여성·남성 계열은 음색 분류입니다. 선택은 다음 중계 연결에 적용됩니다.")
            Text("변경 후 중계를 시작할 때 온라인 음성 전송 동의를 다시 확인합니다.", style = MaterialTheme.typography.bodySmall)
            ServiceExperienceChoices("음성 계열", relayVoiceChoices(api).map { choice ->
                ExperienceChoice(choice.name, choice.label, relayVoiceLabel(api.copy(liveVoice = choice)))
            }, api.liveVoice.name, !busy && relayVoiceSupported(api)) { id ->
                val current = app.translationApiSettings.state.value
                val choice = RelayVoiceGender.valueOf(id)
                if (choice == current.liveVoice || app.translationApiSettings.configure(current.copy(liveVoice = choice))) {
                    voiceSelectionError = null; closePickers()
                } else voiceSelectionError = "음성 설정을 저장하지 못했습니다. 모델 설정을 확인해 주세요."
            }
            voiceSelectionError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } }, confirmButton = { TextButton(onClick = { closePickers() }) { Text("닫기") } })
    if (targetPicker) AlertDialog(onDismissRequest = { closePickers() },
        title = { Text("통역 언어 · 최대 5개") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${targetDraft.size}/5개 선택 · 최소 1개를 선택해 주세요.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text("언어마다 별도 AI 연결을 사용합니다. 선택한 언어 수에 따라 API 요금이 늘 수 있습니다.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = {
                targetDraft = recommendedTranslationLanguageSelection(sourceLanguageTag = relay.source).toCollection(arrayListOf())
                targetSelectionMessage = null
            }) { Text("추천 5개 선택") }
            translationTargetLanguageOptions(relay.source).forEach { option ->
                val checked = option.languageTag in targetDraft
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = !busy,
                    role = Role.Checkbox, onValueChange = {
                        val result = toggleTranslationLanguageSelection(targetDraft.toSet(), option.languageTag,
                            sourceLanguageTag = relay.source)
                        targetDraft = result.selectedLanguageTags.toCollection(arrayListOf())
                        targetSelectionMessage = result.message
                    }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = !busy)
                    Text(option.label, modifier = Modifier.weight(1f))
                }
            }
            targetSelectionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } }, confirmButton = { TextButton(enabled = !busy && targetDraft.size in 1..5, onClick = {
            app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.withTargets(targetDraft.toList()))
            closePickers()
        }) { Text("적용") } }, dismissButton = { TextButton(onClick = { closePickers() }) { Text("취소") } })
    if (sourcePicker || monitorPicker || microphonePicker) AlertDialog(onDismissRequest = { closePickers() },
        title = { Text(if (sourcePicker) "발화 언어" else if (monitorPicker) "기기에서 들을 통역 언어" else "마이크 선택") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            if (sourcePicker) Text("발화 언어와 같은 통역 언어는 제외됩니다. 나머지 선택은 유지합니다.",
                style = MaterialTheme.typography.bodySmall)
            val choices = if (sourcePicker) SOURCE_LANGUAGE_OPTIONS.map { ExperienceChoice(it.languageTag, it.label, "") }
                else if (monitorPicker) TRANSLATION_LANGUAGE_OPTIONS.filter { it.languageTag in relay.targetLanguageTags }
                    .map { ExperienceChoice(it.languageTag, it.label, "") }
                else devices.filter { it.kind !in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER) }
                    .map { ExperienceChoice(it.platformId.toString(), it.label, "") }
            ServiceExperienceChoices("선택", choices,
                if (sourcePicker) relay.source else if (monitorPicker) relay.target else selected?.platformId.toString(), !busy) { id ->
                if (sourcePicker) {
                    app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.withSource(id))
                } else if (monitorPicker) app.interpreterRelaySettings.update(app.interpreterRelaySettings.state.value.copy(target = id))
                else app.audioInputRepository.selectDevice(id.toInt())
                closePickers()
            }
        } }, confirmButton = { TextButton(onClick = { closePickers() }) { Text("닫기") } })
    if (showSettings) {
        LazyColumn(Modifier.fillMaxSize().imePadding().semantics { paneTitle = "중계 설정" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                TextButton(onClick = {
                    if (setupFocus != null) { setupFocus = null; setupMessage = null }
                    else guardedAction("CLOSE_SETTINGS")
                }) { Text(if (setupFocus != null) "설정 메뉴로" else "운영 화면으로") }
                Text(setupFocus?.label ?: "중계 설정", style = MaterialTheme.typography.headlineSmall)
                setupMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (busy) Text("중계를 중지한 뒤 설정을 바꿀 수 있습니다.")
            }
            if (setupFocus == null) {
                RelaySetupItem.entries.filter { it !in setOf(RelaySetupItem.KEY, RelaySetupItem.MODEL) }.forEach { entry -> item(key = entry.name) {
                    OutlinedButton(onClick = { setupFocus = entry; setupMessage = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(entry.label)
                            Text(when (entry) {
                                RelaySetupItem.INPUT -> selected?.label ?: "선택 필요"
                                RelaySetupItem.LANGUAGES -> "${SOURCE_LANGUAGE_OPTIONS.find { it.languageTag == relay.source }?.label?.substringBefore(" · ") ?: relay.source} → " +
                                    relay.targetLanguageTags.joinToString(", ") { tag -> TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == tag }?.label?.substringBefore(" · ") ?: tag }
                                RelaySetupItem.OUTPUT -> "기기 재생 ${if (relay.localPlayback) "켬" else "끔"} · LAN ${if (relay.networkBroadcast) "켬" else "끔"}"
                                RelaySetupItem.VOICE -> relayVoiceLabel(api)
                                RelaySetupItem.SERVICE -> "${api.model} · ${if (api.hasKey) "키 준비됨" else "키 입력 필요"}"
                                RelaySetupItem.PROFESSIONAL -> api.domainPrompt.ifBlank { "선택 사항" }.take(40)
                                RelaySetupItem.COMPARISON -> if (relay.compareOffline) "비교 켬" else "비교 끔"
                                else -> ""
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
            } else when (setupFocus) {
                RelaySetupItem.INPUT -> item {
                    OutlinedButton(onClick = { microphonePicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("마이크 · ${selected?.label ?: "선택 필요"}")
                    }
                    if (!microphoneGranted) Button(onClick = onRequestMicrophone, enabled = !busy) { Text("마이크 접근 허용") }
                }
                RelaySetupItem.LANGUAGES -> item {
            OutlinedButton(onClick = { sourcePicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("발화 · ${SOURCE_LANGUAGE_OPTIONS.find { it.languageTag == relay.source }?.label ?: relay.source}") }
            OutlinedButton(onClick = {
                targetDraft = relay.targetLanguageTags.toCollection(arrayListOf())
                targetSelectionMessage = null; targetPicker = true
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("통역 언어 · ${relay.targetLanguageTags.size}/5개 · ${relay.targetLanguageTags.joinToString(", ") { tag -> TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == tag }?.label?.substringBefore(" · ") ?: tag }}")
            }

                }
                RelaySetupItem.OUTPUT -> item {
            ServiceExperienceToggle("기기에서 통역 음성 듣기", "현재 연결된 스피커·이어폰·Bluetooth로 재생합니다. 마이크 재입력을 줄이려면 이어폰을 사용하세요.", relay.localPlayback, !busy) {
                app.interpreterRelaySettings.update(relay.copy(localPlayback = it)) }
            if (relay.localPlayback) {
                OutlinedButton(onClick = { monitorPicker = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("기기에서 들을 언어 · ${TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == relay.target }?.label ?: relay.target}")
                }
                Text("기기에서는 한 언어를 듣고, 스크립트에서는 선택한 모든 언어를 읽습니다. LAN 방송을 켜면 모든 통역 언어를 공유합니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
            ServiceExperienceToggle("LAN 청취자에게 방송", "같은 Wi-Fi·핫스팟의 청취자에게 같은 음성을 공유합니다. 청취자마다 API를 다시 호출하지 않습니다.", relay.networkBroadcast, !busy) {
                app.interpreterRelaySettings.update(relay.copy(networkBroadcast = it)) }
                }
                RelaySetupItem.VOICE -> item {
            if (relayVoiceSupported(api)) {
                OutlinedButton(onClick = { voiceSelectionError = null; voicePicker = true }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth()) { Text("통역 음성 · ${relayVoiceLabel(api)}") }
                Text("${RelayVoiceGender.AUTO.label}·여성 계열·남성 계열을 선택합니다. 중계 중에는 음성을 바꿀 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall)
            } else if (api.provider == TranslationApiProvider.GEMINI_LIVE && api.model == GEMINI_LIVE_TRANSLATE) {
                Text("원문 목소리의 처리는 Live Translate 모델이 결정합니다. 음성 계열 선택은 지원하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
            }

                }
                RelaySetupItem.PROFESSIONAL -> item {
                    OutlinedButton(onClick = { professionalSettings = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("전문 분야·내 통역 지침 열기")
                    }
                }
                RelaySetupItem.KEY, RelaySetupItem.MODEL, RelaySetupItem.SERVICE -> item {
                    if (draftDirty) Text("분야·지침을 저장하거나 입력을 취소한 뒤 AI 설정을 바꿀 수 있습니다.")
                    key(setupFocus) {
                        TranslationApiPanel(app.translationApiSettings, app.translationApiService, !busy && !draftDirty,
                            liveMonitor = app.geminiLiveMonitor, nativeOnly = true,
                            openItem = when (setupFocus) { RelaySetupItem.KEY -> "key"; RelaySetupItem.MODEL -> "model";
                                else -> "service".takeIf { setupMessage != null } })
                    }
                }
                RelaySetupItem.COMPARISON -> item {
            Text("오프라인 비교 · ${learning.completed}건 완료 · ${learning.incomplete}건 미완료 · ${learning.skipped}건 건너뜀")
            learning.lastPause?.let { Text(it) }
            LearningComparisonReview(if (app.translationApiSettings.authorized(api)) learning.last else null, app.domainCorpus,
                commitAdmission = app::nativeComparisonCommitAdmission, isComparisonCurrent = app::isNativeComparisonCurrent)
            ServiceExperienceToggle("오프라인 결과와 비교 (선택)",
                "기기에서 들을 언어를 오프라인 번역과 비교합니다. 지원하지 않는 모델은 비교를 보류합니다. " + if (learningSupported)
                    "확정된 원문·통역 쌍과 준비된 Gemma 모델을 사용하며 추가 API 요청은 없습니다. 검수·저장한 예문만 다음 오프라인 번역에 적용됩니다."
                else comparisonChoice.detail,
                comparisonChoice.checked, learningSupported) {
                app.interpreterRelaySettings.update(relay.copy(compareOffline = it)) }
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
        return
    }
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = "통역 중계" }, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { guardedAction("BACK") }) { Text("서비스 목록") }
                TextButton(onClick = { showSettings = true; setupFocus = null; setupMessage = null }) { Text("중계 설정") }
            }
            Text("통역 중계", style = MaterialTheme.typography.headlineMedium)
        }
        stickyHeader {
            Surface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${broadcast.relayPhase.shortLabel} · ${relay.targetLanguageTags.size}개 통역 언어",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (!busy) Button(onClick = { start() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("중계 시작") }
                    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (broadcast.phase == BroadcastPhase.PAUSED) Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("재개") }
                        else OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text("일시정지") }
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("중지") }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("마이크 · ${when (broadcast.inputPhase) { InputPhase.ACTIVE -> "입력 중"; InputPhase.STARTING -> "준비 중"; InputPhase.PAUSED -> "일시정지"; InputPhase.FAILED -> "입력 중단"; else -> "대기" }}")
                LinearProgressIndicator(progress = { broadcast.inputPeak.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("통역 · ${if (broadcast.relayPhase == InterpreterRelayPhase.RECEIVING) "수신 중" else "대기"} · ${if (!relay.localPlayback) "기기 재생 끔" else if (broadcast.relayPlayedBytes > 0) "기기 음성 출력 확인됨" else "기기 재생 대기"}")
                (broadcast.inputErrorMessage ?: broadcast.errorMessage ?: broadcast.translationWarning.takeIf {
                    broadcast.relayPhase == InterpreterRelayPhase.FAILED || broadcast.translationChannels.any { channel ->
                        channel.translationState == BroadcastChannelWorkerState.DEGRADED || channel.synthesisState == BroadcastChannelWorkerState.DEGRADED }
                })?.let { ServiceConnectionFeedback(it) }
                broadcast.recordingWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }
            OutlinedButton(onClick = onOpenHud, modifier = Modifier.fillMaxWidth()) { Text("실시간 스크립트 HUD") }
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
                val captionLanguages = if (line.liveSegmentLanguage != null)
                    (listOfNotNull(line.liveSegmentLanguage) + line.translations.keys).distinct()
                    else (relay.targetLanguageTags + line.translations.keys).distinct()
                captionLanguages.forEach { tag ->
                    Text("${TRANSLATION_LANGUAGE_OPTIONS.find { it.languageTag == tag }?.label ?: tag} · ${line.liveOutputState?.label ?: if (line.isFinal) "완료" else "처리 중"}",
                        style = MaterialTheme.typography.labelLarge)
                    Text(line.translations[tag].orEmpty().ifBlank { "전사 대기" })
                }
                if (line.liveSegmentLanguage != null && relay.targetLanguageTags.size > 1)
                    Text("언어별 독립 구간 · 같은 발화와의 대응 미확인", style = MaterialTheme.typography.bodySmall)
                line.liveStatusLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } }
        } }
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
