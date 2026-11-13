package app.guidecast.transmitter

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioCaptureDiagnosticSnapshot
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.RecordedPcmSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BroadcastHistoryScreen(app: GuideCastApplication, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadModel: RecordedDownloadViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val downloadState by downloadModel.state.collectAsState()
    val runtime by app.broadcastRuntime.state.collectAsState()
    var history by remember { mutableStateOf<List<RecordedBroadcast>>(emptyList()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var pageAfter by remember(selected) { mutableStateOf<Pair<Long, Long>?>(null) }
    var captions by remember(selected, pageAfter) { mutableStateOf<List<RecordedCaption>>(emptyList()) }
    var captionLoading by remember(selected, pageAfter) { mutableStateOf(true) }
    var scriptAvailable by remember(selected) { mutableStateOf(false) }
    var historyLoading by remember { mutableStateOf(true) }
    var choice by remember(selected) { mutableStateOf<RecordedAudioChoice?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    val busy = preparing || downloadState.active
    var savingJob by remember { mutableStateOf<Job?>(null) }
    var saveProgress by remember { mutableIntStateOf(0) }
    var playback by remember { mutableStateOf<Job?>(null) }
    var waitingForInputStop by remember { mutableStateOf<Job?>(null) }
    var replayRequestId by remember { mutableLongStateOf(0) }
    var playbackEpoch by remember { mutableIntStateOf(0) }
    var positionMillis by remember { mutableLongStateOf(0) }
    var durationMillis by remember { mutableLongStateOf(0) }
    var playbackPaused by remember { mutableStateOf(false) }
    val playbackControl = remember { RecordedAudioPlaybackControl() }
    var pendingPlayback by remember { mutableStateOf<List<RecordedPcmSegment>?>(null) }
    var pendingReplayGuard by remember { mutableStateOf<HistoryReplayInputSafety?>(null) }
    var pendingDownloadName by rememberSaveable { mutableStateOf<String?>(null) }
    val savedDocument = downloadState.takeIf { it.recordingId == selected }?.savedUri?.let { uri -> downloadState.mime?.let { uri to it } }
    var deleting by remember { mutableStateOf<List<String>?>(null) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedHistoryIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var renaming by remember { mutableStateOf<RecordedBroadcast?>(null) }
    var renameError by remember(renaming?.id) { mutableStateOf<String?>(null) }
    var managementBusy by remember { mutableStateOf(false) }
    var includeScript by remember { mutableStateOf(false) }
    var exportExpanded by remember(selected) { mutableStateOf(false) }
    val snapshot = history.firstOrNull { it.id == selected }
    val choices = snapshot?.let { recordedAudioChoices(it.segments) }.orEmpty()
    val audioChoice = choice?.takeIf { it in choices } ?: choices.firstOrNull { it.channel == "source" } ?: choices.firstOrNull()
    val activeRecordingId = app.recordings.activeId
    val manageableHistory = history.filter { recordingCanBeDeleted(it, activeRecordingId) }
    val manageableSelection = availableRecordingSelections(selectedHistoryIds, history, activeRecordingId)
    LaunchedEffect(history, activeRecordingId, selectedHistoryIds) {
        if (selectedHistoryIds != manageableSelection) selectedHistoryIds = manageableSelection
    }
    LaunchedEffect(selected, pageAfter) {
        while (true) {
            history = withContext(Dispatchers.IO) { app.recordings.history() }
            historyLoading = false
            selected?.let { id ->
                captions = withContext(Dispatchers.IO) { app.recordings.captions(id, pageAfter) }
                scriptAvailable = withContext(Dispatchers.IO) { app.recordings.captionFile(id) != null }
                captionLoading = false
            }
            delay(1500)
        }
    }
    DisposableEffect(Unit) { onDispose { waitingForInputStop?.cancel(); playback?.cancel(); savingJob?.cancel() } }
    LaunchedEffect(runtime.inputPhase) {
        if (runtime.inputPhase in setOf(InputPhase.ACTIVE, InputPhase.STARTING)) {
            playback?.cancel()
            if (message in setOf("저장 음성 재생 중", "저장 음성 준비 중", "재생 일시정지")) message = "마이크가 다시 켜져 다시 듣기를 멈췄어요."
        }
    }
    fun stopPlayback() {
        ++replayRequestId; ++playbackEpoch
        waitingForInputStop?.cancel(); waitingForInputStop = null
        pendingPlayback = null; pendingReplayGuard = null
        playback?.cancel(); playback = null; playbackPaused = false; message = "재생 중지됨"
    }
    BackHandler(enabled = selectionMode || selected != null || managementBusy) {
        if (managementBusy) Unit
        else if (selectionMode) { selectionMode = false; selectedHistoryIds = emptyList() }
        else { stopPlayback(); selected = null; message = null }
    }
    fun replayState(guard: HistoryReplayInputSafety) = guard.observe(replayRequestId, selected,
        app.broadcastRuntime.state.value, app.audioCaptureEngine.diagnostics.value,
        app.translationApiSettings.state.value.revision, app.broadcastRuntime.inputRequestEpoch)
    fun play(segments: List<RecordedPcmSegment>, guard: HistoryReplayInputSafety) {
        if (replayState(guard) != HistoryReplayReadiness.READY) {
            message = "마이크나 방송 상태가 바뀌었어요. 다시 듣기를 다시 골라 주세요."
            return
        }
        playback?.cancel()
        val epoch = ++playbackEpoch
        playbackPaused = false; playbackControl.resume(); positionMillis = 0
        durationMillis = segments.sumOf { it.committedBytes * 1000 / (2L * it.channel.sampleRateHz) }
        playback = scope.launch {
            try {
                if (replayState(guard) != HistoryReplayReadiness.READY) {
                    message = "마이크나 방송 상태가 바뀌었어요. 다시 듣기를 다시 골라 주세요."
                    return@launch
                }
                message = "저장 음성 준비 중"
                playRecordedPcm(segments, app.recordedPlayback, playbackControl, { position, duration ->
                    if (epoch == playbackEpoch) { positionMillis = position; durationMillis = duration }
                }) { if (epoch == playbackEpoch) message = "저장 음성 재생 중" }
                if (epoch == playbackEpoch) message = "재생 완료"
            } catch (cancel: CancellationException) {
                if (epoch == playbackEpoch && app.broadcastRuntime.state.value.inputPhase in setOf(InputPhase.ACTIVE, InputPhase.STARTING))
                    message = "마이크가 다시 켜져 다시 듣기를 멈췄어요."
                throw cancel
            } catch (error: Exception) {
                RuntimeDiagnosticLog.record("recorded_playback", "stage=FAILED reason=${error.javaClass.simpleName}")
                if (epoch == playbackEpoch) message = "음성을 재생하지 못했어요. 저장 상태와 휴대폰 음량을 확인해 주세요."
            }
            finally { if (epoch == playbackEpoch) { playback = null; playbackPaused = false } }
        }
    }
    fun requestPlayback(segments: List<RecordedPcmSegment>) {
        if (segments.isEmpty()) { message = "이 문장의 음성이 없어요. 위에서 전체 음성을 들어보세요."; return }
        val historyId = selected ?: return
        stopPlayback()
        val current = app.broadcastRuntime.state.value
        if (current.translationTestActive) { message = "시험을 마친 뒤 다시 들어주세요."; return }
        val guard = HistoryReplayInputSafety(replayRequestId, historyId, current,
            app.audioCaptureEngine.diagnostics.value.session, app.translationApiSettings.state.value.revision)
        if (replayState(guard) == HistoryReplayReadiness.READY) play(segments, guard)
        else { pendingReplayGuard = guard; pendingPlayback = segments }
    }
    fun finishDownload(uri: Uri?) {
        val name = pendingDownloadName
        pendingDownloadName = null
        downloadModel.finish(app, name, uri)
    }
    val downloadMp3 = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri -> finishDownload(uri) }
    val downloadScript = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> finishDownload(uri) }
    fun prepareDownload(session: RecordedBroadcast, script: Boolean) {
        val selectedAudio = audioChoice
        if (!script && selectedAudio == null) return
        savingJob = scope.launch {
            downloadModel.clearNotice()
            preparing = true; saveProgress = 0
            try {
                val file = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()
                    if (script) app.recordings.stageScript(session) { job.ensureActive() }
                    else app.recordings.stageMp3(session, requireNotNull(selectedAudio), { job.ensureActive() }) { done, total -> saveProgress = (done * 100 / maxOf(1, total)).toInt() }
                }
                pendingDownloadName = file.name
                if (script) downloadScript.launch("MCastTalk-${session.startedAtMillis}-전체글.txt")
                else downloadMp3.launch("MCastTalk-${session.startedAtMillis}-${requireNotNull(selectedAudio).channel}.mp3")
            } catch (cancel: CancellationException) { message = "파일 준비를 취소했어요."; throw cancel }
            catch (_: Exception) { message = "파일을 준비하지 못했어요. 녹음 상태와 저장 공간을 확인해 주세요." }
            finally { preparing = false; savingJob = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = if (selected == null) "방송 기록" else "저장한 방송" },
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(enabled = !managementBusy, onClick = { stopPlayback(); onBack() }) { Text("기록") }
            Text(if (snapshot == null) "방송 기록" else "저장한 방송", style = MaterialTheme.typography.headlineMedium)
            message?.let { Text(it) }
            downloadState.message?.let { Text(it) }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("파일 준비·저장 중 · $saveProgress%"); TextButton(onClick = { if (downloadState.active) downloadModel.cancelCopy() else savingJob?.cancel() }) { Text(if (downloadState.active) "파일 저장 취소" else "파일 준비 취소") } }
        }
        if (snapshot == null) {
            item {
                BroadcastHistoryManagementBar(selectionMode, manageableSelection.size, manageableHistory.size, busy || managementBusy,
                    onStartSelection = { selectionMode = true; message = null },
                    onSelectAll = { selectedHistoryIds = manageableHistory.map { it.id } },
                    onCancel = { selectionMode = false; selectedHistoryIds = emptyList() },
                    onDelete = { deleting = manageableSelection.takeIf { it.isNotEmpty() } },
                    onRename = { manageableSelection.singleOrNull()?.let { id -> history.firstOrNull { it.id == id }?.let { renaming = it } } })
                if (selectionMode) Text("지울 방송을 골라 주세요. 방송 중에는 끝낸 뒤 지울 수 있어요.", style = MaterialTheme.typography.bodySmall)
            }
            if (history.isEmpty()) item { Text(if (historyLoading) "방송 목록을 불러오고 있어요." else "저장한 방송이 없어요. 방송을 시작하면 음성과 글을 휴대폰에 보관해요.") }
            items(history, key = { it.id }) { session ->
                RecordingHistoryRow(session, selectionMode, session.id in manageableSelection,
                    recordingCanBeDeleted(session, activeRecordingId), busy || managementBusy, recordedSessionState(session),
                    onOpen = { selected = session.id; message = null },
                    onSelect = {
                        if (recordingCanBeDeleted(session, app.recordings.activeId)) {
                            selectionMode = true; message = null
                            selectedHistoryIds = if (session.id in selectedHistoryIds) selectedHistoryIds - session.id else selectedHistoryIds + session.id
                        }
                    }, onRename = { renaming = session })
            }
        } else {
            item {
                MenuWebBroadcastCard(app, MenuBroadcastOrigin.HISTORY, canStart = snapshot.segments.any { it.committedBytes > 0 },
                    onStart = { stopPlayback(); startRecordedWebBroadcast(app, snapshot,
                        snapshot.segments.filter { it.committedBytes > 0 }.mapTo(mutableSetOf()) { it.channel.id }) })
            }
            item {
                TextButton(enabled = !managementBusy, onClick = { stopPlayback(); selected = null; message = null }) { Text("전체 방송 목록") }
                Text(recordingDisplayTitle(snapshot), style = MaterialTheme.typography.titleLarge)
                Text(DateFormat.getDateTimeInstance().format(Date(snapshot.startedAtMillis)))
                TextButton(enabled = !busy && !managementBusy, onClick = { renaming = snapshot }) { Text("이름 변경") }
                Text(recordedSessionState(snapshot))
                Text("먼저 읽고 들어보세요. 다시 듣기에는 온라인 통역을 쓰지 않아요.")
                if (snapshot.failure != null || snapshot.droppedRecordingFrames > 0) Text("녹음 공백 ${snapshot.droppedRecordingFrames}회 · 저장 오류 ${snapshot.failure ?: "없음"}", color = MaterialTheme.colorScheme.error)
            }
            item {
                Card(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("음성 미리 듣기", style = MaterialTheme.typography.titleMedium)
                        if (choices.isEmpty()) Text("저장한 음성이 없어요. 말하기·통역이 시작되지 않았거나 녹음에 실패했을 수 있어요. 아래 글을 확인해 주세요.")
                        else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { option ->
                                FilterChip(selected = option == audioChoice, onClick = { stopPlayback(); positionMillis = 0; choice = option }, label = {
                                    val partOrder = snapshot.segments.map { it.partId }.distinct()
                                    val parts = option.segments(snapshot.segments).map { partOrder.indexOf(it.partId) + 1 }.distinct()
                                    Text(option.label + if (choices.count { it.channel == option.channel } > 1) " · ${parts.joinToString(", ")}번 구간 · ${option.rate} Hz" else "")
                                })
                            } }
                            val selectedSegments = requireNotNull(audioChoice).segments(snapshot.segments)
                            val length = selectedSegments.sumOf { it.committedBytes * 1000 / (2L * it.channel.sampleRateHz) }
                            Text("${recordedDuration(positionMillis)} / ${recordedDuration(if (playback == null) length else durationMillis)} · 저장 음성 길이 기준")
                            Text("잠시 멈추거나 녹음되지 않은 조용한 시간은 빼고 보여 드려요.", style = MaterialTheme.typography.bodySmall)
                            if (waitingForInputStop != null) OutlinedButton(onClick = ::stopPlayback, modifier = Modifier.fillMaxWidth()) { Text("재생 준비 취소") }
                            else if (playback == null) Button(onClick = { requestPlayback(selectedSegments) }, modifier = Modifier.fillMaxWidth()) { Text("${requireNotNull(audioChoice).label} 재생") }
                            else {
                                Button(onClick = {
                                    if (playbackPaused) { playbackControl.resume(); message = "저장 음성 재생 중" } else { playbackControl.pause(); message = "재생 일시정지" }
                                    playbackPaused = !playbackPaused
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (playbackPaused) "다시 재생" else "일시정지") }
                                OutlinedButton(onClick = ::stopPlayback, modifier = Modifier.fillMaxWidth()) { Text("재생 중지") }
                            }
                        }
                    }
                }
            }
            item {
                Text("원문과 통역한 글", style = MaterialTheme.typography.titleMedium)
                Text("방송 내용을 순서대로 100문장씩 보여 드려요. 전체 글은 아래에서 저장할 수 있어요.", style = MaterialTheme.typography.bodySmall)
                if (app.recordings.captionIncomplete(snapshot.id)) Text("일부 글이 저장되지 않았어요", color = MaterialTheme.colorScheme.error)
                if (captions.isEmpty()) Text(if (captionLoading) "글을 불러오고 있어요." else if (pageAfter != null) "마지막 문장까지 보았어요. 처음 문장으로 돌아가거나 전체 글을 저장해 보세요." else "저장한 글이 없어요. 음성이 저장되어 있다면 위에서 들을 수 있어요.")
                else LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(captions, key = { "${it.part}:${it.sequence}" }) { caption -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(if (caption.final) "확정" else "미완료 · 확정 대응 미확인", style = MaterialTheme.typography.labelMedium)
                        caption.outputState?.let { Text(LiveOutputState.entries.firstOrNull { state -> state.name == it }?.label ?: "통역 상태 미확인") }
                        caption.endReason?.let { Text(NativeAudioEndReason.entries.firstOrNull { reason -> reason.name == it }?.label ?: "종료 원인 미확인") }
                        Text(caption.original.ifBlank { "저장한 원문 글이 없어요" })
                        Text("문장의 정확한 음성 위치는 확인하지 못했어요. 원음은 구간 전체를 재생해요.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { requestPlayback(snapshot.segments.filter { it.partId == caption.part && it.channel.id == "source" }) }) { Text("원음 구간 듣기") }
                        nativeMissingCaptionLabel(caption.alignment == "NATIVE_PAIR_UNCONFIRMED", caption.outputState,
                            caption.translations.values.any { it.isNotBlank() })?.let { Text(it) }
                        caption.translations.forEach { (language, text) ->
                            Text("${recordedChannelDisplayName(language, language)} · $text")
                            TextButton(onClick = { scope.launch {
                                choice = choices.firstOrNull { it.channel == language }
                                val slice = withContext(Dispatchers.IO) { app.recordings.audio.sequenceSlice(snapshot.id, caption.part, language.lowercase(), caption.sequence) }
                                requestPlayback(slice)
                            } }) { Text("통역 구간 듣기") }
                        }
                        if (caption.alignment == "NATIVE_PAIR_UNCONFIRMED") Text("원문과 통역문이 서로 맞는지는 확인하지 못했어요", style = MaterialTheme.typography.bodySmall)
                    } } }
                }
                FlowRow {
                    TextButton(enabled = pageAfter != null, onClick = { pageAfter = null }) { Text("처음 문장") }
                    TextButton(enabled = captions.size == 100, onClick = { captions.lastOrNull()?.let { pageAfter = it.part to it.sequence } }) { Text("다음 100문장") }
                }
            }
            item {
                OutlinedButton(onClick = { exportExpanded = !exportExpanded }, modifier = Modifier.fillMaxWidth()) { Text(if (exportExpanded) "접기" else "저장·공유") }
                if (exportExpanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedDocument?.let { (uri, mime) ->
                        val canOpen = runtime.inputPhase !in setOf(InputPhase.ACTIVE, InputPhase.STARTING) && !runtime.translationTestActive
                        Text("파일을 저장했어요. 다른 앱으로 열어 확인할 수 있어요.")
                        OutlinedButton(enabled = canOpen, onClick = {
                            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "파일 열기")) }
                                .onFailure { message = "파일을 열 앱을 찾지 못했어요. 저장한 곳에서 다른 앱으로 열어보세요." }
                        }, modifier = Modifier.fillMaxWidth()) { Text("파일 열기") }
                        if (!canOpen) Text("입력을 일시정지한 뒤 저장한 파일을 열어보세요.")
                    }
                    Text("고른 음성을 파일로 저장해요. 방송 중에는 지금까지 저장된 부분만 담아요.")
                    OutlinedButton(enabled = !busy && audioChoice != null, onClick = { prepareDownload(snapshot, false) }, modifier = Modifier.fillMaxWidth()) { Text("음성 저장") }
                    OutlinedButton(enabled = !busy && scriptAvailable, onClick = { prepareDownload(snapshot, true) }, modifier = Modifier.fillMaxWidth()) { Text("글 저장") }
                    ServiceExperienceToggle("전체 글도 함께 공유", "고른 음성과 전체 글을 함께 준비해요.",
                        includeScript, !busy && scriptAvailable, { includeScript = it })
                    OutlinedButton(enabled = !busy && audioChoice != null, onClick = {
                        val selectedAudio = requireNotNull(audioChoice)
                        savingJob = scope.launch {
                            preparing = true; saveProgress = 0
                            val prepared = mutableListOf<File>(); var chooserOpened = false
                            try {
                                withContext(Dispatchers.IO) {
                                    val job = currentCoroutineContext()
                                    prepared += app.recordings.stageMp3(snapshot, selectedAudio, { job.ensureActive() }) { done, total -> saveProgress = (done * 100 / maxOf(1, total)).toInt() }
                                    if (includeScript) prepared += app.recordings.stageScript(snapshot) { job.ensureActive() }
                                }
                                shareRecordedArtifacts(context, prepared); chooserOpened = true
                                message = "공유할 앱을 골라 주세요. 준비한 파일은 24시간 동안 열 수 있어요."
                            } catch (cancel: CancellationException) { message = "공유 준비를 취소했어요."; throw cancel }
                            catch (_: Exception) { message = "공유할 준비를 못 했어요. 저장 공간과 공유할 앱을 확인해 주세요." }
                            finally { if (!chooserOpened) prepared.forEach { it.delete() }; preparing = false; savingJob = null }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("음성 공유") }
                    Text("따로 저장하거나 다른 앱에 보낸 파일은 직접 관리해 주세요. 공유용 임시 파일은 24시간이 지나면 열 수 없어요.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !busy && !managementBusy && recordingCanBeDeleted(snapshot, activeRecordingId), onClick = { deleting = listOf(snapshot.id) }) { Text("이 방송 삭제") }
            }
        }
    }
    pendingPlayback?.let { segments -> AlertDialog(onDismissRequest = { pendingPlayback = null; pendingReplayGuard = null }, title = { Text("입력을 일시정지하고 다시 듣기") },
        text = { Text("재생 소리가 마이크에 들어가지 않도록 마이크와 통역 음성을 먼저 꺼요. 방송 주소와 기록은 남겨 둬요. 다시 말하려면 방송 화면에서 마이크를 켜 주세요.") },
        confirmButton = { TextButton(onClick = {
            val guard = pendingReplayGuard
            pendingPlayback = null; pendingReplayGuard = null
            val readiness = guard?.let(::replayState)
            if (app.broadcastRuntime.state.value.translationTestActive) {
                message = "시험을 마친 뒤 다시 들어주세요."
            } else if (guard == null || readiness == HistoryReplayReadiness.REPLACED) {
                message = "마이크나 방송 상태가 바뀌었어요. 다시 듣기를 다시 골라 주세요."
            } else if (readiness == HistoryReplayReadiness.READY) {
                if (app.broadcastRuntime.state.value.localMonitor.phase !in setOf(LocalMonitorPhase.IDLE, LocalMonitorPhase.FAILED))
                    BroadcastService.stopLocalMonitor(context)
                play(segments, guard)
            } else {
                val requestId = replayRequestId
                waitingForInputStop = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        if (replayState(guard) == HistoryReplayReadiness.REPLACED) {
                            message = "마이크나 방송 상태가 바뀌었어요. 다시 듣기를 다시 골라 주세요."
                            return@launch
                        }
                        message = "입력을 끄고 저장 음성을 준비하는 중"
                        if (canTurnInputOff(app.broadcastRuntime.state.value.inputPhase))
                            BroadcastService.pauseInput(context)
                        if (app.broadcastRuntime.state.value.localMonitor.phase !in setOf(LocalMonitorPhase.IDLE, LocalMonitorPhase.FAILED))
                            BroadcastService.stopLocalMonitor(context)
                        withTimeout(8000) {
                            combine(app.broadcastRuntime.state, app.audioCaptureEngine.diagnostics) { _, _ ->
                                replayState(guard)
                            }.first { it != HistoryReplayReadiness.WAITING }.also {
                                check(it == HistoryReplayReadiness.READY) { "Replay request replaced" }
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        play(segments, guard)
                    } catch (cancel: CancellationException) {
                        if (cancel is TimeoutCancellationException && requestId == replayRequestId)
                            message = "마이크가 꺼졌는지 확인하지 못했어요. 방송 화면에서 마이크를 끄고 다시 들어주세요."
                        throw cancel
                    } catch (_: Exception) {
                        if (requestId == replayRequestId) message = "마이크나 방송 상태가 바뀌었어요. 다시 듣기를 다시 골라 주세요."
                    } finally {
                        if (requestId == replayRequestId) waitingForInputStop = null
                    }
                }
                waitingForInputStop?.start()
            }
        }) { Text("끄고 재생") } }, dismissButton = { TextButton(onClick = { pendingPlayback = null; pendingReplayGuard = null }) { Text("취소") } }) }
    renaming?.let { recording -> RecordingRenameDialog(recording, managementBusy,
        errorMessage = renameError, onDraftChanged = { renameError = null },
        onCancel = { renaming = null }, onSave = { title -> scope.launch {
            managementBusy = true; renameError = null
            try {
                val saved = withContext(Dispatchers.IO) { app.recordings.rename(recording.id, title) }
                if (saved) {
                    history = withContext(Dispatchers.IO) { app.recordings.history() }
                    renaming = null; message = "방송 이름을 바꿨어요."
                } else renameError = "이름을 저장하지 못했어요. 방송이 남아 있는지와 저장 공간을 확인한 뒤 다시 시도해 주세요."
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { renameError = "이름을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요." }
            finally { managementBusy = false }
        } }) }
    deleting?.let { ids -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("방송 ${ids.size}개 삭제") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ids.size == 1) history.firstOrNull { it.id == ids.single() }?.let { Text(recordingDisplayTitle(it)) }
            Text("이 휴대폰에 저장한 음성·글과 공유용 임시 파일을 지워요. 따로 저장하거나 다른 앱에 보낸 파일은 직접 지워 주세요.")
        } },
        confirmButton = { TextButton(onClick = { deleting = null; scope.launch {
            managementBusy = true; stopPlayback()
            try {
                val failed = withContext(Dispatchers.IO) { ids.filter { id -> runCatching { app.recordings.delete(id) }.isFailure } }
                val removed = ids - failed.toSet()
                if (selected in removed) selected = null
                selectedHistoryIds = selectedHistoryIds - removed.toSet()
                history = withContext(Dispatchers.IO) { app.recordings.history() }
                if (failed.isEmpty()) { selectionMode = false; selectedHistoryIds = emptyList(); message = "방송 ${removed.size}개를 지웠어요." }
                else { selectionMode = true; message = "${removed.size}개 삭제 · ${failed.size}개를 지우지 못했어요. 진행 중인 방송을 끝내거나 저장 공간을 확인한 뒤 다시 시도해 주세요." }
            } catch (_: Exception) { message = "방송 목록을 새로 불러오지 못했어요. 잠시 뒤 다시 확인해 주세요." }
            finally { managementBusy = false }
        } }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
}

internal enum class HistoryReplayReadiness { WAITING, READY, REPLACED }

/** A replay belongs to one selected history and one stopped input request. */
internal class HistoryReplayInputSafety(
    private val requestId: Long,
    private val historyId: String,
    initial: BroadcastSnapshot,
    private val captureSession: Long,
    private val apiRevision: Long,
) {
    private val recordingId = initial.recordingId
    private val listenerUrl = initial.listenerUrl
    private val interpreterRelay = initial.isInterpreterRelay
    private var acknowledgedStopEpoch: Long? = null

    fun observe(currentRequestId: Long, selectedHistoryId: String?, current: BroadcastSnapshot,
        capture: AudioCaptureDiagnosticSnapshot, currentApiRevision: Long, inputRequestEpoch: Long): HistoryReplayReadiness {
        if (currentRequestId != requestId || selectedHistoryId != historyId || current.recordingId != recordingId ||
            current.listenerUrl != listenerUrl || current.isInterpreterRelay != interpreterRelay ||
            capture.session != captureSession || currentApiRevision != apiRevision || current.translationTestActive)
            return HistoryReplayReadiness.REPLACED
        val inputActive = current.inputPhase in setOf(InputPhase.ACTIVE, InputPhase.STARTING)
        acknowledgedStopEpoch?.let { epoch ->
            if (inputActive || inputRequestEpoch != epoch) return HistoryReplayReadiness.REPLACED
        }
        if (inputActive) return HistoryReplayReadiness.WAITING
        if (acknowledgedStopEpoch == null) acknowledgedStopEpoch = inputRequestEpoch
        return if (!current.inputStopping && capture.state in setOf("NOT_STARTED", "IDLE", "CLOSED", "FAILED") &&
            current.localMonitor.phase in setOf(LocalMonitorPhase.IDLE, LocalMonitorPhase.FAILED))
            HistoryReplayReadiness.READY else HistoryReplayReadiness.WAITING
    }
}

private fun recordedDuration(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun recordedSessionState(session: RecordedBroadcast): String = when (session.state) {
    "RECORDING", "PREPARING" -> "방송 중 · 기기에 저장 중"
    "PAUSED" -> "일시정지 · 같은 방송에 이어서 저장"
    "COMPLETED" -> "종료 ${session.endedAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "시각 미확인"}"
    else -> session.endedAtMillis?.let { "중단 ${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: "중단 · 종료 시각 미확인"
}
