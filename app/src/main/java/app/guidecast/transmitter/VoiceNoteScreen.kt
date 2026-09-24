package app.guidecast.transmitter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun VoiceNoteLifecycle(model: VoiceNoteViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(view, state.recording, state.busy) {
        val previous = view.keepScreenOn
        if (state.recording || state.busy) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.pauseForBackground()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun VoiceNoteRoute(model: VoiceNoteViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val defaultSourceTag = remember(context) {
        val tag = (context.applicationContext as? GuideCastApplication)?.operatorSettings?.state?.value?.sourceLanguageTag
        if (tag != null && VOICE_NOTE_LANGUAGES.containsKey(tag)) tag else "ko-KR"
    }
    var title by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<String?>(defaultSourceTag) }
    var target by rememberSaveable { mutableStateOf("ko-KR") }
    var transcribePermission by rememberSaveable { mutableStateOf(false) }
    var liveTranscription by rememberSaveable { mutableStateOf(true) }
    var showDetailedView by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showTranslation by rememberSaveable { mutableStateOf(false) }
    var optionsExpanded by remember { mutableStateOf(false) }
    var userScrolledUp by rememberSaveable { mutableStateOf(false) }
    var userDragging by remember { mutableStateOf(false) }
    var userDragHappened by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var deleteNote by remember { mutableStateOf<VoiceNote?>(null) }
    var speakerIndex by model.editorDraft.speakerIndex
    var speaker by model.editorDraft.speaker
    var leaveDialog by remember { mutableStateOf(false) }
    var replaceDialog by remember { mutableStateOf(false) }
    var translateDialog by remember { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var transferVisible by rememberSaveable { mutableStateOf(false) }
    var libraryQuery by rememberSaveable { mutableStateOf("") }
    var transcriptQuery by rememberSaveable { mutableStateOf("") }
    var renameTitle by model.editorDraft.renameTitle
    var editIndex by model.editorDraft.editIndex
    var editOriginal by model.editorDraft.editOriginal
    var editTranslation by model.editorDraft.editTranslation
    var translationEdited by model.editorDraft.translationEdited
    var editAttempted by model.editorDraft.editAttempted
    var renameAttempted by model.editorDraft.renameAttempted
    var speakerAttempted by model.editorDraft.speakerAttempted
    val note = state.selected
    val transcriptionUnavailable = if (note != null) model.transcriptionUnavailableReason(source) else null
    val listState = rememberLazyListState()
    val toolsListState = rememberLazyListState()
    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward }
    }
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    userDragging = true
                    userDragHappened = true
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    userDragging = false
                    userScrolledUp = !isAtBottom
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress && userDragHappened) {
                userScrolledUp = !isAtBottom
                userDragHappened = false
            }
        }
    }
    LaunchedEffect(state.recording) {
        if (state.recording) {
            userScrolledUp = false
            userDragging = false
            userDragHappened = false
        }
    }
    LaunchedEffect(state.liveLines.size, state.partialTranscript) {
        if (state.recording && !userScrolledUp && !userDragging && !userDragHappened) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) listState.animateScrollToItem(total - 1)
        }
    }
    val matchingLines = remember(note?.lines, transcriptQuery, showDetailedView, showTools) {
        voiceNoteMatchingLines(note?.lines.orEmpty(), if (showDetailedView || showTools) transcriptQuery else "")
    }
    val library = remember(state.library, libraryQuery) { state.library.filter { it.title.contains(libraryQuery.trim(), ignoreCase = true) } }
    LaunchedEffect(note?.id) {
        showTools = false
        model.editorDraft.select(note?.id)
        transcriptQuery = ""
        if (note != null) { source = note.sourceLanguage; target = note.targetLanguage }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.permissionDenied()
        else if (transcribePermission) model.transcribe(source, target) else model.record(title, source, target, liveTranscription)
    }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { model.exportPrepared(it, "txt") }
    val srt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { model.exportPrepared(it, "srt") }
    val md = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { model.exportPrepared(it, "md") }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { model.exportPrepared(it, "json") }
    val wav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { model.exportPrepared(it, "wav") }
    val exportDocument: (String, VoiceNoteExportOptions) -> Unit = { kind, exportOptions ->
        if (model.prepareExport(kind, exportOptions)) {
            val name = "MCastTalk-${note?.id}.$kind"
            when (kind) { "txt" -> txt.launch(name); "srt" -> srt.launch(name); "md" -> md.launch(name); "json" -> json.launch(name) }
        }
    }
    val request: (Boolean) -> Unit = { transcribe ->
        transcribePermission = transcribe
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (transcribe) model.transcribe(source, target) else model.record(title, source, target, liveTranscription)
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val back = {
        if (showTools) showTools = false
        else if (showLibrary) showLibrary = false
        else if (state.recording || state.busy) leaveDialog = true
        else { model.leave(); onBack() }
    }
    val enabled = !state.busy && !state.recording && !state.unavailable
    if (transferVisible) {
        DataTransferPanel(onBack = { transferVisible = false; model.refreshLibrary() },
            unavailableReason = if (enabled && !state.playback.isPlaying) null else "진행 중인 음성 작업을 마친 뒤 백업·가져오기를 실행하세요.")
        return
    }
    BackHandler(onBack = back)
    Surface(Modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = MCastService.NOTES.title }) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    IconButton(
                        onClick = back,
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "뒤로" }
                    ) {
                        Text("←", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (state.recording) "● 녹음 중" else if (showTools) "노트 도구" else (note?.title ?: MCastService.NOTES.title),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!state.recording) {
                        TextButton(
                            onClick = { showLibrary = !showLibrary; showTools = false },
                            enabled = !state.busy,
                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        ) {
                            Text(if (showLibrary) "노트 작업" else "보관함 (${state.library.size})")
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { optionsExpanded = true },
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .semantics { contentDescription = "옵션" }
                        ) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(
                            expanded = optionsExpanded,
                            onDismissRequest = { optionsExpanded = false }
                        ) {
                            if (note != null) {
                                DropdownMenuItem(
                                    text = { Text(if (showTools) "문장 보기" else "노트 도구") },
                                    onClick = {
                                        showTools = !showTools
                                        showLibrary = false
                                        optionsExpanded = false
                                    },
                                    enabled = enabled
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if (showDetailedView) "기본 보기" else "상세보기") },
                                onClick = {
                                    showDetailedView = !showDetailedView
                                    showTools = false
                                    optionsExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (showTranslation) "번역문 숨기기" else "번역문 표시") },
                                onClick = {
                                    showTranslation = !showTranslation
                                    optionsExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("노트 백업·가져오기") },
                                onClick = {
                                    transferVisible = true
                                    optionsExpanded = false
                                },
                                enabled = enabled && !state.playback.isPlaying
                            )
                            if (note != null) {
                                DropdownMenuItem(
                                    text = { Text("제목 변경") },
                                    onClick = {
                                        renameTitle = note.title
                                        renameAttempted = false
                                        optionsExpanded = false
                                    },
                                    enabled = enabled
                                )
                                if (note.durationMs > 0) {
                                    DropdownMenuItem(
                                        text = { Text("녹음 내려받기 (WAV)") },
                                        onClick = {
                                            if (model.prepareExport("wav")) wav.launch("MCastTalk-${note.id}.wav")
                                            optionsExpanded = false
                                        },
                                        enabled = enabled
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("새 음성노트") },
                                    onClick = {
                                        model.leave()
                                        title = ""
                                        showTools = false
                                        optionsExpanded = false
                                    },
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = if (showTools) toolsListState else listState,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().imePadding()
                ) {
                    if (state.unavailable) item {
                        Text("방송·파일 작업·모델 준비를 마친 뒤 시작하세요.", color = MaterialTheme.colorScheme.error)
                    }
                    items(voiceNoteVisibleMessages(listOf(state.message, note?.notice,
                        state.recognitionMessage.takeIf { state.recording }), showDetailedView)) { message ->
                        Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    state.playback.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }

                    if (!showLibrary) {
                        if (state.recording) {
                            if (showDetailedView) {
                                item {
                                    Text("남은 녹음 시간 ${voiceNoteTime((3_600_000 - state.elapsedMs).coerceAtLeast(0))} · 60분이 되면 자동 저장합니다.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (liveTranscription) {
                                if (showDetailedView) {
                                    item {
                                        Text("실시간 문장 · ${state.liveLines.size}개 저장", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                if (state.liveLines.isEmpty() && state.partialTranscript.isBlank()) {
                                    item { Text("말하면 문장이 여기에 나타납니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                items(state.liveLines.size, key = { "live-line-$it" }) { index ->
                                    val line = state.liveLines[index]
                                    SelectionContainer {
                                        Text(line.original, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                                    }
                                }
                                if (state.partialTranscript.isNotBlank()) {
                                    item {
                                        Column(Modifier.padding(vertical = 4.dp)) {
                                            Text("인식 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                            SelectionContainer {
                                                Text(state.partialTranscript, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (note == null) {
                            item {
                                OutlinedTextField(title, { title = it.take(120) }, label = { Text("노트 제목") },
                                    enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            if (showDetailedView) {
                                item {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(liveTranscription, onClick = { liveTranscription = true }, enabled = enabled,
                                            label = { Text("녹음·실시간 받아쓰기") })
                                        FilterChip(!liveTranscription, onClick = { liveTranscription = false }, enabled = enabled,
                                            label = { Text("녹음만") })
                                    }
                                    VoiceNoteLanguagePicker("말하는 언어", source, enabled,
                                        Build.VERSION.SDK_INT >= 34 && !liveTranscription) { source = it }
                                    VoiceNoteLanguagePicker("괄호 안에 표시할 번역 언어", target, enabled, false) { target = requireNotNull(it) }
                                    Text(if (liveTranscription) "말하는 언어를 선택하세요. 시작을 누르면 저장된 인식 모델을 확인하고 녹음과 받아쓰기를 함께 시작합니다. 최초 준비에는 인터넷이 필요할 수 있습니다."
                                        else "녹음 후 자동 언어 감지는 Android 14 이상과 기기의 오프라인 언어팩 지원이 필요합니다.", style = MaterialTheme.typography.bodySmall)
                                    if (liveTranscription && source == null) Text("실시간 받아쓰기는 말하는 언어를 직접 선택해 주세요.", color = MaterialTheme.colorScheme.error)
                                    Text(if (liveTranscription) "녹음 중 확정 문장은 자동 저장됩니다. 화면을 벗어나거나 잠그면 녹음과 마지막 문장을 저장하고 중지합니다. 최대 60분."
                                        else "원음만 녹음하며 실시간 문장을 만들지 않습니다. 화면을 벗어나거나 잠그면 녹음을 저장하고 중지합니다. 최대 60분.", style = MaterialTheme.typography.bodySmall)
                                    Text("음성과 문장은 기기 안에서 처리합니다. 번역 모델의 최초 준비에는 인터넷이 필요할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { transferVisible = true }, enabled = enabled && !state.playback.isPlaying,
                                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("노트 백업·가져오기") }
                                }
                            } else {
                                if (liveTranscription && source == null) {
                                    item {
                                        Text("실시간 받아쓰기는 말하는 언어를 점3개 > 상세보기에서 직접 선택해 주세요.", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        } else {
                            if (showTools) {
                                item {
                                    Text("${formatArchiveSessionTime(note.createdAt)} · ${voiceNoteTime(note.durationMs)}", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { renameTitle = note.title; renameAttempted = false }, enabled = enabled,
                                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("제목 변경") }
                                }
                                if (state.playback.prepared) {
                                    item {
                                        VoiceNotePlaybackControls(state.playback, enabled, model::seek, model::skipPlayback, model::setPlaybackSpeed)
                                    }
                                }
                                item {
                                    Button(onClick = { if (note.lines.isNotEmpty()) replaceDialog = true else request(true) },
                                        enabled = enabled && transcriptionUnavailable == null && note.durationMs > 0, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                                        Text(if (note.lines.isEmpty()) "받아쓰기·번역 시작" else "선택한 언어로 다시 받아쓰기")
                                    }
                                    transcriptionUnavailable?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    if (note.lines.isNotEmpty()) {
                                        val missing = note.lines.count { it.translation.isBlank() }
                                        OutlinedButton(onClick = {
                                             if (target != note.targetLanguage && note.lines.any { it.translation.isNotBlank() }) translateDialog = true
                                            else model.translateRemaining(target)
                                        }, enabled = enabled && (missing > 0 || target != note.targetLanguage), modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
                                            Text(if (target != note.targetLanguage) "선택한 언어로 번역" else "남은 구간 번역 ($missing)")
                                        }
                                        Text("번역 ${note.lines.size - missing} / ${note.lines.size}개 구간 · Google Translate 기기 내 번역", style = MaterialTheme.typography.bodySmall)
                                    }
                                    VoiceNoteLanguagePicker("말하는 언어", source, enabled, Build.VERSION.SDK_INT >= 34) { source = it }
                                    VoiceNoteLanguagePicker("괄호 안에 표시할 번역 언어", target, enabled, false) { target = requireNotNull(it) }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { if (model.prepareExport("wav")) wav.launch("MCastTalk-${note.id}.wav") },
                                            enabled = enabled && note.durationMs > 0, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                                            Text("녹음 내려받기")
                                        }
                                        TextButton(onClick = { transferVisible = true }, enabled = enabled && !state.playback.isPlaying,
                                            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) {
                                            Text("노트 백업·가져오기")
                                        }
                                    }
                                    VoiceNoteExportPanel(note, transcriptQuery, enabled, exportDocument)
                                    Text("원문 (번역문) · 화자 이름은 직접 지정할 수 있습니다. 자동 화자 분리는 제공하지 않습니다. 구간 시각은 추정값일 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                                    OutlinedTextField(transcriptQuery, { transcriptQuery = it.take(200) }, label = { Text("이 노트에서 문장·화자 찾기") },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                    if (transcriptQuery.isNotBlank()) {
                                        Text("${matchingLines.size}개 구간 검색됨 · 내려받기 옵션에서 저장 범위를 선택하세요.")
                                        TextButton(onClick = { transcriptQuery = "" }, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("검색 지우기") }
                                    }
                                }
                            } else if (showDetailedView) {
                                items(matchingLines, key = { "detail-line-$it" }) { index ->
                                    val line = note.lines[index]
                                    val playingLine = state.playback.isPlaying && state.playback.positionMs >= line.startMs && state.playback.positionMs < line.endMs
                                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
                                        if (playingLine) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("${voiceNoteTime(line.startMs)} · ${line.language ?: "언어 미확인"}", style = MaterialTheme.typography.labelMedium)
                                            if (playingLine) Text("재생 중인 구간", style = MaterialTheme.typography.labelMedium)
                                            SelectionContainer { Text(voiceNoteLineText(line), style = MaterialTheme.typography.bodyLarge) }
                                            if (line.edited) Text("직접 수정한 문장", style = MaterialTheme.typography.labelSmall)
                                            if (line.translation.isBlank()) Text("번역 대기 · 원문은 저장됨", style = MaterialTheme.typography.labelSmall)
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = { model.playFrom(line.startMs) }, enabled = enabled && !note.interrupted,
                                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("여기서 듣기") }
                                                TextButton(onClick = { speakerIndex = index; speaker = line.speaker; speakerAttempted = false }, enabled = enabled,
                                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("화자 이름") }
                                                TextButton(onClick = { editIndex = index; editOriginal = line.original; editTranslation = line.translation; translationEdited = false; editAttempted = false }, enabled = enabled,
                                                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("문장 수정") }
                                            }
                                        }
                                    }
                                }
                                if (state.playback.prepared) {
                                    item {
                                        VoiceNotePlaybackControls(state.playback, enabled, model::seek, model::skipPlayback, model::setPlaybackSpeed)
                                    }
                                }
                            } else {
                                items(matchingLines, key = { "default-line-$it" }) { index ->
                                    val line = note.lines[index]
                                    val playingLine = state.playback.isPlaying && state.playback.positionMs >= line.startMs && state.playback.positionMs < line.endMs
                                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                        SelectionContainer {
                                            Text(
                                                text = line.original,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = if (playingLine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        if (showTranslation && line.translation.isNotBlank()) {
                                            Spacer(Modifier.height(2.dp))
                                            SelectionContainer {
                                                Text(
                                                    text = line.translation,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (playingLine) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (showLibrary) {
                        item { HorizontalDivider(); Text("음성노트 보관함", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
                        item {
                            OutlinedTextField(libraryQuery, { libraryQuery = it.take(120) }, label = { Text("보관함에서 제목 찾기") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (libraryQuery.isNotBlank()) TextButton(onClick = { libraryQuery = "" }, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("제목 검색 지우기") }
                            Text("${library.size}개 노트 · 최신 녹음 순 · 최대 500개", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { model.leave(); title = ""; showLibrary = false; showTools = false }, enabled = enabled, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("새 음성노트") }
                        }
                        if (state.library.isEmpty()) item { Text("아직 저장된 노트가 없습니다. 첫 녹음을 시작해 보세요.") }
                        else if (library.isEmpty()) item { Text("일치하는 제목이 없습니다. 검색어를 바꿔 보세요.") }
                        items(library, key = { it.id }) { saved ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(saved.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${formatArchiveSessionTime(saved.createdAt)} · ${if (saved.interrupted) "복구 필요" else voiceNoteTime(saved.durationMs)}", style = MaterialTheme.typography.bodySmall)
                                    Row {
                                        TextButton(onClick = { model.open(saved.id); showLibrary = false; showTools = false }, enabled = enabled, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("열기") }
                                        TextButton(onClick = { deleteNote = saved }, enabled = enabled, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)) { Text("삭제") }
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.recording && userScrolledUp) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            userScrolledUp = false
                            userDragging = false
                            userDragHappened = false
                            coroutineScope.launch {
                                val total = listState.layoutInfo.totalItemsCount
                                if (total > 0) listState.animateScrollToItem(total - 1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        text = { Text("최신 문장으로") },
                        icon = { Text("↓") }
                    )
                }
            }

            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    when {
                        state.recording -> {
                            OutlinedButton(
                                onClick = model::stopRecording,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("녹음 종료·저장")
                            }
                        }
                        state.busy -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                            OutlinedButton(
                                onClick = model::cancel,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("작업 중지")
                            }
                        }
                        note == null -> {
                            Button(
                                onClick = { request(false) },
                                enabled = enabled && (!liveTranscription || source != null),
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text(if (liveTranscription) "녹음·받아쓰기 시작" else "녹음만 시작")
                            }
                        }
                        note.interrupted -> {
                            Button(
                                onClick = model::recover,
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            ) {
                                Text("중단된 녹음 복구")
                            }
                        }
                        else -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = model::playPause,
                                    enabled = enabled && note.durationMs > 0,
                                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text(if (state.playback.isPlaying) "일시정지" else "녹음 재생")
                                }
                                OutlinedButton(
                                    onClick = { model.leave(); title = ""; showTools = false },
                                    enabled = enabled,
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                                ) {
                                    Text("새 음성노트")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteNote?.let { saved -> AlertDialog(onDismissRequest = { deleteNote = null }, title = { Text("음성노트를 삭제할까요?") },
        text = { Text("‘${saved.title}’의 녹음·원문·번역을 함께 삭제합니다. 내려받은 파일은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { model.delete(saved.id); deleteNote = null }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleteNote = null }) { Text("취소") } }) }
    speakerIndex?.let { index -> AlertDialog(onDismissRequest = { speakerIndex = null }, title = { Text("이 구간의 화자 이름") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(speaker, { speaker = it.take(80).replace('\n', ' ') }, label = { Text("예: 화자 1 / 안내자") }, singleLine = true, enabled = !state.busy)
            if (speakerAttempted && !state.busy) state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { TextButton(onClick = { speakerAttempted = true; model.setSpeaker(index, speaker) { speakerIndex = null } }, enabled = enabled) { Text("저장") } },
        dismissButton = { TextButton(onClick = { speakerIndex = null }) { Text("취소") } }) }
    renameTitle?.let { value -> AlertDialog(onDismissRequest = { renameTitle = null }, title = { Text("노트 제목 변경") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value, { renameTitle = it.take(120).replace('\n', ' ') }, label = { Text("새 제목") }, singleLine = true, enabled = !state.busy)
            if (renameAttempted && !state.busy) state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { TextButton(onClick = { renameAttempted = true; model.rename(value) { renameTitle = null } }, enabled = enabled && value.isNotBlank()) { Text("제목 저장") } },
        dismissButton = { TextButton(onClick = { renameTitle = null }) { Text("취소") } }) }
    editIndex?.let { index -> AlertDialog(onDismissRequest = { editIndex = null }, title = { Text("문장 수정") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("원문을 고치면 기존 자동 번역은 지워집니다. 새 번역을 직접 입력하거나 저장 후 남은 구간을 번역하세요. 원음과 화자 이름은 유지됩니다.")
            OutlinedTextField(editOriginal, { editOriginal = it.take(65_536); if (!translationEdited) editTranslation = "" }, label = { Text("원문 수정") }, maxLines = 5, enabled = !state.busy)
            OutlinedTextField(editTranslation, { editTranslation = it.take(65_536); translationEdited = true }, label = { Text("번역문 수정 (선택)") }, maxLines = 5, enabled = !state.busy)
            if (editAttempted && !state.busy) state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { TextButton(onClick = { editAttempted = true; model.editLine(index, editOriginal, editTranslation, translationEdited) { editIndex = null } }, enabled = enabled && editOriginal.isNotBlank()) { Text("수정 저장") } },
        dismissButton = { TextButton(onClick = { editIndex = null }) { Text("취소") } }) }
    if (translateDialog) AlertDialog(onDismissRequest = { translateDialog = false }, title = { Text("번역 언어를 바꿀까요?") },
        text = { Text("새 ${VOICE_NOTE_LANGUAGES[target]} 번역을 모두 완료한 뒤 기존 번역과 직접 수정한 번역을 대체합니다. 중지하거나 실패하면 기존 번역을 유지합니다. 원문·화자·녹음도 유지됩니다. 두 번역이 모두 필요하면 먼저 TXT를 내려받으세요.") },
        confirmButton = { TextButton(onClick = { translateDialog = false; model.translateRemaining(target) }) { Text("번역 변경") } },
        dismissButton = { TextButton(onClick = { translateDialog = false }) { Text("취소") } })
    if (replaceDialog) AlertDialog(onDismissRequest = { replaceDialog = false }, title = { Text("다시 받아쓸까요?") },
        text = { Text("새 원문이 완성되면 기존 스크립트·번역·직접 지정한 화자 이름을 대체합니다. 원음은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { replaceDialog = false; request(true) }) { Text("다시 받아쓰기") } },
        dismissButton = { TextButton(onClick = { replaceDialog = false }) { Text("취소") } })
    if (leaveDialog) AlertDialog(onDismissRequest = { leaveDialog = false }, title = { Text("진행 중인 작업을 중지할까요?") },
        text = { Text("녹음은 종료·저장하고, 변환은 중지합니다. 저장된 내용은 보관함에 남습니다.") },
        confirmButton = { TextButton(onClick = { model.leave(); leaveDialog = false; onBack() }) { Text("중지하고 홈으로") } },
        dismissButton = { TextButton(onClick = { leaveDialog = false }) { Text("계속하기") } })
}

@Composable
private fun VoiceNoteLanguagePicker(label: String, selected: String?, enabled: Boolean, automatic: Boolean, onSelect: (String?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (automatic) FilterChip(selected == null, onClick = { onSelect(null) }, enabled = enabled, label = { Text("자동 전환") })
        VOICE_NOTE_LANGUAGES.forEach { (tag, name) -> FilterChip(selected == tag, onClick = { onSelect(tag) }, enabled = enabled, label = { Text(name) }) }
    }
}
