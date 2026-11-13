package app.guidecast.transmitter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioInputKind
import kotlinx.coroutines.flow.collect

internal fun workspaceDisplayLanguages(available: List<String>, selected: List<String>?): List<String> =
    available.distinct().let { tags -> selected?.filter { it in tags }?.distinct() ?: tags }

internal fun toggleWorkspaceDisplayLanguage(available: List<String>, selected: List<String>?, tag: String): List<String> {
    val current = workspaceDisplayLanguages(available, selected)
    if (tag !in available) return current
    return if (tag in current) current - tag else available.distinct().filter { it in current || it == tag }
}

internal fun workspaceUsesCompactControls(fontScale: Float, screenHeightDp: Int): Boolean =
    fontScale > 1.3f || screenHeightDp < 480

internal fun workspaceUsesSideAccess(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 540f && heightDp < 320f

internal data class WorkspaceStatus(val label: String, val issue: String? = null)

internal fun streamingWorkspaceStatus(broadcast: BroadcastSnapshot): WorkspaceStatus = when {
    broadcast.errorMessage != null || broadcast.phase == BroadcastPhase.FAILED ->
        WorkspaceStatus("오류", broadcast.errorMessage ?: "방송 종료 또는 연결 상태를 확인해 주세요.")
    broadcast.inputErrorMessage != null || broadcast.inputPhase == InputPhase.FAILED ->
        WorkspaceStatus("입력 확인", broadcast.inputErrorMessage ?: "마이크와 입력 설정을 확인해 주세요.")
    broadcast.recognitionErrorMessage != null -> WorkspaceStatus("인식 확인", broadcast.recognitionErrorMessage)
    broadcast.translationWarning != null -> WorkspaceStatus("통역 확인", broadcast.translationWarning)
    broadcast.translationChannels.any { it.lastError != null || it.lastSynthesisError != null ||
        it.translationState == BroadcastChannelWorkerState.DEGRADED || it.synthesisState == BroadcastChannelWorkerState.DEGRADED } ->
        WorkspaceStatus("언어 확인", "일부 언어의 번역·음성에 문제가 있습니다. 언어별 상태에서 확인해 주세요.")
    broadcast.phase == BroadcastPhase.STARTING -> WorkspaceStatus("방송 준비 중")
    broadcast.inputDraining -> WorkspaceStatus("마이크 꺼짐 · 남은 통역 처리 중")
    broadcast.inputStopping -> WorkspaceStatus("입력 종료 중")
    broadcast.inputPhase == InputPhase.STARTING -> WorkspaceStatus("입력 준비 중")
    broadcast.inputPhase == InputPhase.ACTIVE && broadcast.inputFrameCount == 0L -> WorkspaceStatus("입력 연결 중")
    broadcast.inputPhase == InputPhase.ACTIVE && broadcast.translationChannels.any {
        it.translationState == BroadcastChannelWorkerState.ACTIVE || it.synthesisState == BroadcastChannelWorkerState.ACTIVE
    } -> WorkspaceStatus("통역 처리 중")
    broadcast.inputPhase == InputPhase.ACTIVE -> WorkspaceStatus("입력 중 · 발화 대기")
    broadcast.phase == BroadcastPhase.PAUSED -> WorkspaceStatus("일시정지")
    broadcast.phase == BroadcastPhase.LIVE || broadcast.inputPhase == InputPhase.PAUSED -> WorkspaceStatus("입력 꺼짐")
    else -> WorkspaceStatus("시작 대기")
}

@Composable
internal fun ServiceWorkspaceDrawer(current: MCastService?, active: MCastService?,
    onSelect: (MCastService) -> Unit, onHome: () -> Unit, onCommonSettings: () -> Unit, onTest: () -> Unit) {
    ModalDrawerSheet {
        Text("MCastTalk", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            NavigationDrawerItem(label = { Text("전체 서비스") }, selected = current == null, onClick = onHome,
                modifier = Modifier.padding(horizontal = 12.dp))
            listOf(MCastService.MULTILINGUAL, MCastService.RELAY, MCastService.NOTES, MCastService.FILES, MCastService.HISTORY).forEach { item ->
                NavigationDrawerItem(label = { Text(item.title) }, selected = current == item, onClick = { onSelect(item) },
                    badge = if (active == item) ({ Text("진행 중") }) else null,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("service-drawer-${item.name.lowercase()}"))
            }
            HorizontalDivider(Modifier.padding(12.dp))
            NavigationDrawerItem(label = { Text("앱 공통 설정") }, selected = false, onClick = onCommonSettings,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("service-drawer-common-settings"))
            NavigationDrawerItem(label = { Text("방송 전 시험") }, selected = false, onClick = onTest,
                modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

@Composable
internal fun ServiceWorkspaceTopBar(title: String, broadcastingLabel: String?, listenerCount: Int?, status: WorkspaceStatus?,
    onOpenServices: () -> Unit, onOpenStatus: () -> Unit, onOpenSettings: () -> Unit, showSettingsButton: Boolean = true) {
    val connections = listenerCount?.let { "듣는 사람 ${it}명" } ?: "청취 연결 확인 중"
    val developerInfo = LocalDeveloperInfo.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        BoxWithConstraints(Modifier.fillMaxWidth().statusBarsPadding()) {
            val expandedText = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
            val shortScreen = LocalConfiguration.current.screenHeightDp < 480
            val statusOnSecondRow = expandedText && !shortScreen && WindowInsets.ime.getBottom(LocalDensity.current) == 0 && maxHeight >= 104.dp
            val statusContent: @Composable () -> Unit = {
                if (broadcastingLabel != null && status != null) TextButton(onClick = onOpenStatus,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("service-workspace-status").semantics {
                        contentDescription = "$title · $broadcastingLabel · ${listenerCount?.let { "청취 연결 ${it}개" } ?: "청취 연결 미확인"} · ${status.label}. 청취 주소·QR과 상태 열기"
                    }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (!statusOnSecondRow) Text(title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$broadcastingLabel · $connections" + if (developerInfo) " · ${status.label}" else "",
                            style = MaterialTheme.typography.labelSmall, maxLines = if (expandedText && !shortScreen) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (status.issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                } else Text(title, style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expandedText) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            Column {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenServices, modifier = Modifier.testTag("service-workspace-menu")
                        .semantics { contentDescription = "전체 서비스 메뉴 열기" }) { WorkspaceSymbol(WorkspaceSymbolKind.MENU) }
                    Box(Modifier.weight(1f)) {
                        if (statusOnSecondRow && broadcastingLabel != null) Text(title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis) else statusContent()
                    }
                    if (showSettingsButton) IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("service-workspace-settings")
                        .semantics { contentDescription = "$title 설정 열기" }) { WorkspaceSymbol(WorkspaceSymbolKind.SETTINGS) }
                    else Spacer(Modifier.width(48.dp))
                }
                if (statusOnSecondRow && broadcastingLabel != null) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { statusContent() }
            }
        }
    }
}

internal data class WorkspaceFooterPolicy(val broadcastEnabled: Boolean, val inputEnabled: Boolean,
    val inputStarting: Boolean, val canStopInput: Boolean)

internal enum class WorkspacePauseAction(val label: String) {
    PAUSE("방송 잠시 멈추기"), RESUME("방송 다시 시작")
}

internal fun workspacePauseAction(broadcast: BroadcastSnapshot, active: Boolean, otherActive: Boolean): WorkspacePauseAction? =
    if (!active || otherActive || broadcast.broadcastFinishing) null else when (broadcast.phase) {
        BroadcastPhase.LIVE -> WorkspacePauseAction.PAUSE
        BroadcastPhase.PAUSED -> WorkspacePauseAction.RESUME
        else -> null
    }

internal fun workspaceFooterPolicy(broadcast: BroadcastSnapshot, broadcastActive: Boolean,
    otherBroadcastActive: Boolean, inputRequestPending: Boolean,
    startBroadcastEnabled: Boolean = true, startInputEnabled: Boolean = true): WorkspaceFooterPolicy {
    if (broadcast.broadcastFinishing) return WorkspaceFooterPolicy(false, false, false, false)
    val starting = inputRequestPending || broadcast.inputPhase == InputPhase.STARTING
    val canStop = !broadcast.inputStopping && (starting || broadcast.inputPhase == InputPhase.ACTIVE)
    return WorkspaceFooterPolicy(broadcastActive || (!otherBroadcastActive && startBroadcastEnabled),
        !broadcast.inputStopping && (canStop || (!otherBroadcastActive && startInputEnabled)), starting, canStop)
}

@Composable
internal fun WorkspaceSettingsFooter(broadcast: BroadcastSnapshot, broadcastActive: Boolean,
    onBack: (() -> Unit)?, onStopBroadcast: () -> Unit, onStopInput: () -> Unit,
    stopBroadcastLabel: String = "방송 중지") {
    val inputRunning = broadcast.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE) || broadcast.inputStopping
    if (onBack == null && !broadcastActive && !inputRunning) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onBack != null) Button(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("settings-return-to-workspace")) { Text("방송 화면으로") }
            if (broadcastActive || inputRunning) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (broadcastActive) OutlinedButton(onClick = onStopBroadcast, enabled = !broadcast.broadcastFinishing,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings-stop-broadcast")) {
                    Text(if (broadcast.broadcastFinishing) "남은 통역 저장 중" else if (broadcast.phase == BroadcastPhase.FAILED) "종료 재시도" else stopBroadcastLabel)
                }
                if (inputRunning) OutlinedButton(onClick = onStopInput, enabled = !broadcast.inputStopping,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("settings-stop-input")) {
                    Text(if (broadcast.inputDraining) "남은 통역 처리 중" else if (broadcast.inputStopping) "입력 끄는 중" else "마이크·입력 끄기")
                }
            }
        }
    }
}

@Composable
internal fun StreamingWorkspaceFooter(broadcast: BroadcastSnapshot, broadcastActive: Boolean, standalone: Boolean,
    otherBroadcastActive: Boolean, inputKind: AudioInputKind?, inputRequestPending: Boolean,
    onStartBroadcast: () -> Unit, onStopBroadcast: () -> Unit, onStartInput: () -> Unit, onPauseInput: () -> Unit,
    onPauseBroadcast: () -> Unit, onResumeBroadcast: () -> Unit,
    onOpenHud: () -> Unit, startBroadcastEnabled: Boolean = true, startInputEnabled: Boolean = true,
    stopBroadcastLabel: String? = null, onOpenQr: (() -> Unit)? = null) {
    val policy = workspaceFooterPolicy(broadcast, broadcastActive, otherBroadcastActive, inputRequestPending,
        startBroadcastEnabled, startInputEnabled)
    val inputName = if (inputKind in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER)) "입력" else "마이크"
    val pauseAction = workspacePauseAction(broadcast, broadcastActive, otherBroadcastActive)
    val starting = policy.inputStarting
    val canTurnOff = policy.canStopInput
    val micLabel = when {
        broadcast.inputDraining -> "통역 마치는 중"
        broadcast.inputStopping -> "$inputName 끄는 중"
        starting -> "$inputName 켜기 취소"
        canTurnOff -> "$inputName 끄기"
        else -> "$inputName 켜기"
    }
    val broadcastLabel = when {
        broadcast.broadcastFinishing -> "통역 후 종료"
        broadcastActive && broadcast.phase == BroadcastPhase.FAILED -> "종료 재시도"
        broadcastActive -> "방송 끝내기"
        else -> "방송 시작"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = if (canTurnOff) onPauseInput else onStartInput,
                enabled = policy.inputEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("streaming-input-toggle"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                WorkspaceSymbol(WorkspaceSymbolKind.MICROPHONE)
                Text(micLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
            if (broadcastActive) OutlinedButton(
                onClick = { when (pauseAction) {
                    WorkspacePauseAction.PAUSE -> onPauseBroadcast()
                    WorkspacePauseAction.RESUME -> onResumeBroadcast()
                    null -> Unit
                } }, enabled = pauseAction != null,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("streaming-broadcast-pause"),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                WorkspaceSymbol(if (pauseAction == WorkspacePauseAction.RESUME) WorkspaceSymbolKind.PLAY else WorkspaceSymbolKind.PAUSE)
                Text(pauseAction?.label ?: "방송 잠시 멈추기", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (onOpenQr != null) OutlinedButton(onClick = onOpenQr,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("streaming-qr-open"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Text("방송접속 QR", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(onClick = onOpenHud,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("streaming-hud-open"),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                WorkspaceSymbol(WorkspaceSymbolKind.FULLSCREEN)
                Text("큰 자막", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
            Button(onClick = if (broadcastActive) onStopBroadcast else onStartBroadcast,
                enabled = policy.broadcastEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("streaming-broadcast-toggle"),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(broadcastLabel, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
          }
        }
    }
}

@Composable
internal fun StreamingTranscriptWorkspace(broadcast: BroadcastSnapshot, availableTargets: List<String>, selectedTargets: List<String>,
    showSource: Boolean, onToggleSource: () -> Unit, onToggleTarget: (String) -> Unit,
    onOpenSettings: () -> Unit, onOpenStatus: () -> Unit,
    sourceActions: @Composable (TranslationTranscriptLine) -> Unit = {}, modifier: Modifier = Modifier,
    operatingActions: @Composable () -> Unit = {}, additionalIssue: String? = null, emptyMessage: String? = null,
    originalAudioOnly: Boolean = false, displaySelectionLabel: String? = null,
    captionOnlyLanguages: Set<String> = emptySet()) {
    val developerInfo = LocalDeveloperInfo.current
    var follow by rememberSaveable { mutableStateOf(true) }
    val list = rememberLazyListState()
    var followingScroll by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && !followingScroll }.collect { if (it) follow = false }
    }
    val groups = remember(broadcast.transcripts, originalAudioOnly) {
        if (originalAudioOnly) emptyList() else relayCaptionPresentation(broadcast.transcripts)
    }
    val rows = remember(groups, selectedTargets, showSource) {
        groups.filter { group -> showSource || selectedTargets.any { group.translations.containsKey(it) || group.segmentFor(it) != null } }
    }
    LaunchedEffect(rows.firstOrNull()?.id, rows.firstOrNull(), follow) {
        if (follow && rows.isNotEmpty()) {
            followingScroll = true
            try { list.scrollToItem(0) } finally { followingScroll = false }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val shortBody = maxHeight < 320.dp
        val sideAccess = broadcast.listenerUrl != null && workspaceUsesSideAccess(maxWidth.value, maxHeight.value)
        val accessWidth = (maxWidth * .32f).coerceIn(160.dp, 220.dp)
        val content: @Composable ColumnScope.() -> Unit = {
        if (!originalAudioOnly) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            displaySelectionLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            FilterChip(selected = showSource, onClick = onToggleSource, label = { Text("원문") },
                modifier = Modifier.heightIn(min = 48.dp).testTag("streaming-display-source"))
            availableTargets.forEach { tag ->
                FilterChip(selected = tag in selectedTargets, onClick = { onToggleTarget(tag) },
                    label = { Text(relayCaptionLanguageLabel(tag)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("streaming-display-$tag"))
            }
            if (developerInfo) selectedTargets.forEach { tag ->
                val dropped = broadcast.translationChannels.firstOrNull { it.channelId == tag }?.droppedUtterances ?: 0L
                AssistChip(
                    onClick = {},
                    label = { Text("${relayCaptionLanguageLabel(tag)} 누락 $dropped") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (dropped > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = if (dropped > 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.heightIn(min = 40.dp)
                )
            }
            if (availableTargets.isEmpty()) TextButton(onClick = onOpenSettings) { Text("통역 언어 설정") }
            if (shortBody) TextButton(onClick = { follow = !follow }, modifier = Modifier.testTag("streaming-follow-toggle")) {
                Text(if (follow) "따라가기 중지" else "실시간으로")
            }
        }
        if (!sideAccess) {
            BroadcastListenerAccess(broadcast)
            operatingActions()
        }
        if (!developerInfo) BroadcastLanguageStatusList(broadcast.translationChannels, captionOnlyLanguages)
        val issue = streamingWorkspaceStatus(broadcast).issue ?: additionalIssue
        if (issue != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (developerInfo) issue else "소리나 통역 상태를 확인해 주세요", modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall, maxLines = if (shortBody) 1 else 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onOpenStatus) { Text("확인·복구") }
            }
        }
        if (!shortBody) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (originalAudioOnly) "원음 방송" else "실시간 자막", modifier = Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            if (originalAudioOnly) TextButton(onClick = onOpenSettings) { Text("통번역 설정") }
            else TextButton(onClick = { follow = !follow }, modifier = Modifier.testTag("streaming-follow-toggle")) {
                Text(if (follow) "따라가기 중지" else "실시간으로")
            }
        }
        }
        if (rows.isEmpty()) Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shortBody) {
                Text(if (originalAudioOnly) "원음 방송" else "실시간 자막", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
            }
            Text(when {
                originalAudioOnly -> "현재는 마이크 소리만 청취자에게 전달합니다. 자막과 통역 음성이 필요하면 통번역 설정에서 번역 방송을 켜세요."
                !showSource && selectedTargets.isEmpty() -> "위에서 표시할 원문이나 통역 언어를 선택하세요. 방송 언어는 바뀌지 않습니다."
                broadcast.inputPhase == InputPhase.ACTIVE -> "말씀해 주세요. 말한 내용과 통역문이 여기에 나와요."
                else -> emptyMessage ?: "마이크를 켜면 말한 내용과 통역문을 볼 수 있어요. 청취자를 초대하려면 방송접속 QR을 보여 주세요."
            }, style = MaterialTheme.typography.bodyLarge)
            if (shortBody && originalAudioOnly) TextButton(onClick = onOpenSettings) { Text("통번역 설정") }
        } else LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxWidth().weight(1f)
            .testTag("streaming-transcript-list"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(rows, key = { it.id }) { group ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showSource) {
                        Text("원문 · ${group.sourceStatusLabel}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { Text(group.sourceText.ifBlank { "원문 자막 대기" }, style = MaterialTheme.typography.titleLarge) }
                        group.segments.firstOrNull { it.isFinal && it.liveSegmentLanguage == null }?.let { sourceActions(it) }
                    }
                    selectedTargets.forEach { tag ->
                        val segment = group.segmentFor(tag)
                        val translated = group.translations[tag]
                        val translationError = group.segments.firstNotNullOfOrNull { it.translationErrors[tag] }
                            ?.takeIf { it.isNotBlank() }
                        val speechNotice = group.segments.firstNotNullOfOrNull { it.speechNotices[tag] }
                            ?.takeIf { it.isNotBlank() }
                        val isDropped = segment?.liveOutputState in setOf(LiveOutputState.INCOMPLETE, LiveOutputState.CANCELLED)
                        if (group.alignment == RelayCaptionAlignment.SHARED_UTTERANCE || segment != null || tag in group.translations) {
                            val stateLabel = when {
                                translationError != null -> " · 번역 실패"
                                translated != null && translated.isNotBlank() -> segment?.liveOutputState?.label?.let { " · $it" } ?: ""
                                isDropped -> " · 번역 지연 · 원문 참고"
                                segment?.liveOutputState != null -> " · ${segment.liveOutputState.label}"
                                else -> ""
                            }
                            Text(relayCaptionLanguageLabel(tag) + stateLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(translated?.takeIf(String::isNotBlank)
                                ?: translationError?.let { if (developerInfo) it else "이 문장을 통역하지 못했어요" } ?: if (isDropped) "통역이 늦어지고 있어요 · 말한 내용을 확인해 주세요" else (segment?.nativeMissingCaptionLabel(tag) ?: "통역 자막 대기"), style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary) }
                            if (developerInfo && translationError != null && !translated.isNullOrBlank()) {
                                Text(translationError, color = MaterialTheme.colorScheme.primary)
                            }
                            if (developerInfo) speechNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    if (developerInfo) group.alignmentNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    HorizontalDivider()
                }
            }
        }
        }
        if (sideAccess) Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(accessWidth).fillMaxHeight()
                .verticalScroll(rememberScrollState())) { BroadcastListenerAccess(broadcast); operatingActions() }
            Column(Modifier.weight(1f).fillMaxHeight(), content = content)
        } else Column(Modifier.fillMaxSize(), content = content)
    }
}


@Composable
internal fun WorkspaceNativeLanguageStatus(channels: List<BroadcastChannelSnapshot>) {
    if (!LocalDeveloperInfo.current) { BroadcastLanguageStatusList(channels); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("언어별 통역 음성·청취 주소", style = MaterialTheme.typography.titleMedium)
        if (channels.isEmpty()) Text("방송을 시작하면 제공 중인 언어와 청취 주소를 확인할 수 있습니다.")
        channels.forEach { channel ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(relayCaptionLanguageLabel(channel.languageTag), style = MaterialTheme.typography.titleMedium)
                    Text("청취 연결 ${channel.listenerCount}개 · " + when {
                        channel.lastError != null -> "확인 필요"
                        channel.publishedFrameCount > 0 -> "음성 게시됨 · 기기 청취 미확인"
                        else -> "통역 음성 대기"
                    }, style = MaterialTheme.typography.bodySmall)
                    channel.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    channel.listenerUrl?.let { RelayListenerAccessCard(it, broadcasting = true, compact = true) }
                }
            }
        }
    }
}

private enum class WorkspaceSymbolKind { MENU, SETTINGS, MICROPHONE, FULLSCREEN, PAUSE, PLAY }

@Composable
private fun WorkspaceSymbol(kind: WorkspaceSymbolKind) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 2.dp.toPx()
        when (kind) {
            WorkspaceSymbolKind.PAUSE -> {
                drawRect(color, Offset(w * .25f, h * .2f), Size(w * .16f, h * .6f))
                drawRect(color, Offset(w * .59f, h * .2f), Size(w * .16f, h * .6f))
            }
            WorkspaceSymbolKind.PLAY -> drawPath(androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .3f, h * .15f); lineTo(w * .8f, h * .5f); lineTo(w * .3f, h * .85f); close()
            }, color)
            WorkspaceSymbolKind.MENU -> listOf(.25f, .5f, .75f).forEach { y ->
                drawLine(color, Offset(w * .15f, h * y), Offset(w * .85f, h * y), strokeWidth = stroke, cap = StrokeCap.Round)
            }
            WorkspaceSymbolKind.MICROPHONE -> {
                drawRoundRect(color, Offset(w * .37f, h * .08f), Size(w * .26f, h * .52f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .13f), style = Stroke(stroke))
                drawArc(color, 0f, 180f, false, Offset(w * .23f, h * .28f), Size(w * .54f, h * .45f), style = Stroke(stroke))
                drawLine(color, Offset(w * .5f, h * .73f), Offset(w * .5f, h * .9f), stroke)
                drawLine(color, Offset(w * .34f, h * .9f), Offset(w * .66f, h * .9f), stroke)
            }
            WorkspaceSymbolKind.FULLSCREEN -> listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (x, y) ->
                val point = Offset(w * (.15f + .7f * x), h * (.15f + .7f * y))
                drawLine(color, point, Offset(point.x + w * .22f * (if (x == 0f) 1 else -1), point.y), stroke)
                drawLine(color, point, Offset(point.x, point.y + h * .22f * (if (y == 0f) 1 else -1)), stroke)
            }
            WorkspaceSymbolKind.SETTINGS -> {
                drawCircle(color, w * .27f, style = Stroke(stroke))
                drawCircle(color, w * .09f, style = Stroke(stroke))
                repeat(8) { i ->
                    val a = Math.PI * i / 4
                    val dx = kotlin.math.cos(a).toFloat()
                    val dy = kotlin.math.sin(a).toFloat()
                    drawLine(color, Offset(w * .5f + w * .3f * dx, h * .5f + h * .3f * dy),
                        Offset(w * .5f + w * .4f * dx, h * .5f + h * .4f * dy), stroke)
                }
            }
        }
    }
}
