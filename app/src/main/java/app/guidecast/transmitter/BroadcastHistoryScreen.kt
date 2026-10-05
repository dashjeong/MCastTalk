package app.guidecast.transmitter

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.RecordedPcmSegment
import kotlinx.coroutines.*
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
    var playbackEpoch by remember { mutableIntStateOf(0) }
    var positionMillis by remember { mutableLongStateOf(0) }
    var durationMillis by remember { mutableLongStateOf(0) }
    var playbackPaused by remember { mutableStateOf(false) }
    val playbackControl = remember { RecordedAudioPlaybackControl() }
    var pendingPlayback by remember { mutableStateOf<List<RecordedPcmSegment>?>(null) }
    var pendingDownloadName by rememberSaveable { mutableStateOf<String?>(null) }
    val savedDocument = downloadState.takeIf { it.recordingId == selected }?.savedUri?.let { uri -> downloadState.mime?.let { uri to it } }
    var deleting by remember { mutableStateOf<String?>(null) }
    var includeScript by remember { mutableStateOf(false) }
    var exportExpanded by remember(selected) { mutableStateOf(false) }
    val snapshot = history.firstOrNull { it.id == selected }
    val choices = snapshot?.let { recordedAudioChoices(it.segments) }.orEmpty()
    val audioChoice = choice?.takeIf { it in choices } ?: choices.firstOrNull { it.channel == "source" } ?: choices.firstOrNull()
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
    DisposableEffect(Unit) { onDispose { playback?.cancel(); savingJob?.cancel() } }
    LaunchedEffect(runtime.inputPhase) {
        if (runtime.inputPhase in setOf(InputPhase.ACTIVE, InputPhase.STARTING)) {
            playback?.cancel()
            if (message in setOf("저장 음성 재생 중", "저장 음성 준비 중", "재생 일시정지")) message = "입력 재개로 다시 듣기를 중지했습니다."
        }
    }
    fun stopPlayback() { playback?.cancel(); playback = null; playbackPaused = false; message = "재생 중지됨" }
    fun play(segments: List<RecordedPcmSegment>) {
        playback?.cancel()
        val epoch = ++playbackEpoch
        playbackPaused = false; playbackControl.resume(); positionMillis = 0
        durationMillis = segments.sumOf { it.committedBytes * 1000 / (2L * it.channel.sampleRateHz) }
        playback = scope.launch {
            try {
                message = "저장 음성 준비 중"
                playRecordedPcm(segments, app.recordedPlayback, playbackControl, { position, duration ->
                    if (epoch == playbackEpoch) { positionMillis = position; durationMillis = duration }
                }) { if (epoch == playbackEpoch) message = "저장 음성 재생 중" }
                if (epoch == playbackEpoch) message = "재생 완료"
            } catch (cancel: CancellationException) {
                if (app.broadcastRuntime.state.value.inputPhase in setOf(InputPhase.ACTIVE, InputPhase.STARTING))
                    message = "입력 재개로 다시 듣기를 중지했습니다."
                throw cancel
            } catch (error: Exception) {
                RuntimeDiagnosticLog.record("recorded_playback", "stage=FAILED reason=${error.javaClass.simpleName}")
                if (epoch == playbackEpoch) message = "음성을 재생하지 못했습니다. 저장 상태와 기기 음량을 확인하세요."
            }
            finally { if (epoch == playbackEpoch) { playback = null; playbackPaused = false } }
        }
    }
    fun requestPlayback(segments: List<RecordedPcmSegment>) {
        if (segments.isEmpty()) { message = "이 문장에 대응하는 음성이 없습니다. 위의 전체 음원으로 들어보세요."; return }
        if (runtime.inputPhase in setOf(InputPhase.ACTIVE, InputPhase.STARTING) || runtime.translationTestActive) pendingPlayback = segments else play(segments)
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
                if (script) downloadScript.launch("MCastTalk-${session.startedAtMillis}-전체스크립트.txt")
                else downloadMp3.launch("MCastTalk-${session.startedAtMillis}-${requireNotNull(selectedAudio).channel}.mp3")
            } catch (cancel: CancellationException) { message = "파일 준비를 취소했습니다."; throw cancel }
            catch (_: Exception) { message = "파일 준비 실패 · 녹음 상태와 저장 공간을 확인하세요." }
            finally { preparing = false; savingJob = null }
        }
    }
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = if (selected == null) "방송 이력" else "저장한 방송" },
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = { stopPlayback(); onBack() }) { Text("서비스 목록") }
            Text(if (snapshot == null) "방송 이력" else "저장한 방송", style = MaterialTheme.typography.headlineMedium)
            message?.let { Text(it) }
            downloadState.message?.let { Text(it) }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("파일 준비·저장 중 · $saveProgress%"); TextButton(onClick = { if (downloadState.active) downloadModel.cancelCopy() else savingJob?.cancel() }) { Text(if (downloadState.active) "파일 저장 취소" else "파일 준비 취소") } }
        }
        if (snapshot == null) {
            if (history.isEmpty()) item { Text(if (historyLoading) "방송 목록을 불러오는 중입니다." else "저장된 방송이 없습니다. 방송을 시작하면 음성과 스크립트를 기기에 보관합니다.") }
            items(history, key = { it.id }) { session ->
                Card(onClick = { selected = session.id; message = null }, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(DateFormat.getDateTimeInstance().format(Date(session.startedAtMillis)))
                        Text(recordedSessionState(session)); Text("음성과 스크립트 확인 · 다시 듣기")
                        if (session.failure != null || session.droppedRecordingFrames > 0) Text("녹음에 공백이 있습니다", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            item {
                TextButton(onClick = { stopPlayback(); selected = null; message = null }) { Text("전체 방송 목록") }
                Text(DateFormat.getDateTimeInstance().format(Date(snapshot.startedAtMillis)), style = MaterialTheme.typography.titleLarge)
                Text(recordedSessionState(snapshot))
                Text("먼저 내용을 읽고 들어보세요. 다시 듣기에는 AI를 호출하지 않습니다.")
                if (snapshot.failure != null || snapshot.droppedRecordingFrames > 0) Text("녹음 공백 ${snapshot.droppedRecordingFrames}회 · 저장 오류 ${snapshot.failure ?: "없음"}", color = MaterialTheme.colorScheme.error)
            }
            item {
                Card(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("음성 미리 듣기", style = MaterialTheme.typography.titleMedium)
                        if (choices.isEmpty()) Text("저장된 음성이 없습니다. 입력·통역이 시작되지 않았거나 녹음에 실패했을 수 있습니다. 아래 스크립트를 확인하세요.")
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
                            Text("일시정지·녹음되지 않은 무음 시간은 제외됩니다.", style = MaterialTheme.typography.bodySmall)
                            if (playback == null) Button(onClick = { requestPlayback(selectedSegments) }, modifier = Modifier.fillMaxWidth()) { Text("${requireNotNull(audioChoice).label} 재생") }
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
                Text("원문·번역 스크립트", style = MaterialTheme.typography.titleMedium)
                Text("이 방송 전체를 순서대로 조회합니다. 한 번에 100문장씩 표시하며, 전체 스크립트는 아래에서 저장할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                if (app.recordings.captionIncomplete(snapshot.id)) Text("스크립트 저장 공백이 있습니다", color = MaterialTheme.colorScheme.error)
                if (captions.isEmpty()) Text(if (captionLoading) "스크립트를 불러오는 중입니다." else if (pageAfter != null) "마지막 문장까지 확인했습니다. 처음 문장으로 돌아가거나 전체 스크립트를 저장하세요." else "보관된 스크립트가 없습니다. 전사 없이 저장된 음성은 위에서 들어볼 수 있습니다.")
                else LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(captions, key = { "${it.part}:${it.sequence}" }) { caption -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                        Text(if (caption.final) "확정" else "미완료 · 확정 대응 미확인", style = MaterialTheme.typography.labelMedium)
                        caption.outputState?.let { Text(LiveOutputState.entries.firstOrNull { state -> state.name == it }?.label ?: "통역 상태 미확인") }
                        caption.endReason?.let { Text(NativeAudioEndReason.entries.firstOrNull { reason -> reason.name == it }?.label ?: "종료 원인 미확인") }
                        Text(caption.original.ifBlank { "원문 전사 없음" })
                        TextButton(onClick = { requestPlayback(snapshot.segments.filter { it.partId == caption.part && it.channel.id == "source" }) }) { Text("원음 구간 전체 듣기 · 문장 위치 미확인") }
                        caption.translations.forEach { (language, text) ->
                            Text("${java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.KOREAN)} · $text")
                            TextButton(onClick = { scope.launch {
                                choice = choices.firstOrNull { it.channel == language }
                                val slice = withContext(Dispatchers.IO) { app.recordings.audio.sequenceSlice(snapshot.id, caption.part, language.lowercase(), caption.sequence) }
                                requestPlayback(slice)
                            } }) { Text("이 통역 구간 듣기") }
                        }
                        if (caption.alignment == "NATIVE_PAIR_UNCONFIRMED") Text("Live 원문·번역의 대응 관계 미확인", style = MaterialTheme.typography.bodySmall)
                    } } }
                }
                FlowRow {
                    TextButton(enabled = pageAfter != null, onClick = { pageAfter = null }) { Text("처음 문장") }
                    TextButton(enabled = captions.size == 100, onClick = { captions.lastOrNull()?.let { pageAfter = it.part to it.sequence } }) { Text("다음 100문장") }
                }
            }
            item {
                OutlinedButton(onClick = { exportExpanded = !exportExpanded }, modifier = Modifier.fillMaxWidth()) { Text(if (exportExpanded) "저장·공유 접기" else "음원·스크립트 저장·공유") }
                if (exportExpanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    savedDocument?.let { (uri, mime) ->
                        val canOpen = runtime.inputPhase !in setOf(InputPhase.ACTIVE, InputPhase.STARTING) && !runtime.translationTestActive
                        Text("파일을 저장했습니다. 다른 앱으로 열어 확인할 수 있습니다.")
                        OutlinedButton(enabled = canOpen, onClick = {
                            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "저장한 파일 열기")) }
                                .onFailure { message = "파일을 열 앱을 찾지 못했습니다. 저장 위치에서 다른 앱으로 열어보세요." }
                        }, modifier = Modifier.fillMaxWidth()) { Text("저장한 파일 열기") }
                        if (!canOpen) Text("입력을 일시정지한 뒤 저장한 파일을 열어보세요.")
                    }
                    Text("${audioChoice?.label ?: "음원 없음"}을 실제 MP3로 저장합니다. 진행 중인 방송은 현재 저장된 구간까지만 포함합니다.")
                    OutlinedButton(enabled = !busy && audioChoice != null, onClick = { prepareDownload(snapshot, false) }, modifier = Modifier.fillMaxWidth()) { Text("선택 음원 MP3 내려받기") }
                    OutlinedButton(enabled = !busy && scriptAvailable, onClick = { prepareDownload(snapshot, true) }, modifier = Modifier.fillMaxWidth()) { Text("전체 스크립트 TXT 내려받기") }
                    ServiceExperienceToggle("전체 스크립트도 함께 공유", "선택 음원 MP3와 전체 스크립트 TXT를 함께 준비합니다.",
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
                                message = "공유할 앱을 직접 선택하세요. 준비한 파일은 24시간 동안 읽을 수 있습니다."
                            } catch (cancel: CancellationException) { message = "공유 준비를 취소했습니다."; throw cancel }
                            catch (_: Exception) { message = "공유 준비 실패 · 저장 공간과 공유 앱을 확인하세요." }
                            finally { if (!chooserOpened) prepared.forEach { it.delete() }; preparing = false; savingJob = null }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("선택 음원 MP3 공유") }
                    Text("공유한 복사본과 내려받은 파일은 직접 관리하세요. 앱의 공유 임시 파일은 24시간 후 읽기 권한이 만료됩니다.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !busy && (snapshot.endedAtMillis != null || snapshot.state == "INTERRUPTED"), onClick = { deleting = snapshot.id }) { Text("이 방송 삭제") }
            }
        }
    }
    pendingPlayback?.let { segments -> AlertDialog(onDismissRequest = { pendingPlayback = null }, title = { Text("입력을 일시정지하고 다시 듣기") },
        text = { Text("재생 소리가 마이크에 다시 들어가지 않도록 입력을 먼저 일시정지합니다. 통역 중계는 연결도 일시정지합니다. 다시 중계하려면 운영 화면에서 직접 시작하세요.") },
        confirmButton = { TextButton(onClick = { pendingPlayback = null; scope.launch {
            if (runtime.translationTestActive) { message = "시험을 마친 뒤 다시 들어주세요."; return@launch }
            if (runtime.isInterpreterRelay) BroadcastService.pauseBroadcast(context) else BroadcastService.pauseInput(context)
            BroadcastService.stopLocalMonitor(context)
            try { withTimeout(8000) { app.broadcastRuntime.state.first { it.inputPhase !in setOf(InputPhase.ACTIVE, InputPhase.STARTING) } }; play(segments) }
            catch (_: Exception) { message = "입력 일시정지를 확인하지 못했습니다. 운영 화면에서 중지한 뒤 재생하세요." }
        } }) { Text("일시정지 후 재생") } }, dismissButton = { TextButton(onClick = { pendingPlayback = null }) { Text("취소") } }) }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("방송 기록 삭제") },
        text = { Text("기기 내 음성·스크립트와 앱의 공유 임시 파일을 삭제합니다. 내려받은 파일과 수신 앱의 복사본은 직접 삭제해야 합니다.") },
        confirmButton = { TextButton(onClick = { deleting = null; scope.launch {
            try { withContext(Dispatchers.IO) { app.recordings.delete(id) }; selected = null }
            catch (_: Exception) { message = "일부 파일을 삭제하지 못했습니다. 저장 공간을 확인하고 다시 시도하세요." }
        } }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
}

private fun recordedDuration(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun recordedSessionState(session: RecordedBroadcast): String = when (session.state) {
    "RECORDING", "PREPARING" -> "방송 중 · 기기에 저장 중"
    "PAUSED" -> "일시정지 · 같은 방송에 이어서 저장"
    "COMPLETED" -> "종료 ${session.endedAtMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "시각 미확인"}"
    else -> session.endedAtMillis?.let { "중단 ${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: "중단 · 종료 시각 미확인"
}
