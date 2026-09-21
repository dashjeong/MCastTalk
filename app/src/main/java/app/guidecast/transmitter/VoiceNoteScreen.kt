package app.guidecast.transmitter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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

@Composable
internal fun VoiceNoteRoute(model: VoiceNoteViewModel, onBack: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var title by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf<String?>("ko-KR") }
    var target by rememberSaveable { mutableStateOf("ko-KR") }
    var transcribePermission by rememberSaveable { mutableStateOf(false) }
    var deleteNote by remember { mutableStateOf<VoiceNote?>(null) }
    var speakerIndex by remember { mutableStateOf<Int?>(null) }
    var speaker by remember { mutableStateOf("") }
    var leaveDialog by remember { mutableStateOf(false) }
    var replaceDialog by remember { mutableStateOf(false) }
    val note = state.selected
    LaunchedEffect(note?.id) {
        if (note != null) { source = note.sourceLanguage; target = note.targetLanguage }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.permissionDenied()
        else if (transcribePermission) model.transcribe(source, target) else model.record(title, source, target)
    }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> if (uri != null) model.export(uri, "txt") }
    val srt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri -> if (uri != null) model.export(uri, "srt") }
    val wav = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri -> if (uri != null) model.export(uri, "wav") }
    val request: (Boolean) -> Unit = { transcribe ->
        transcribePermission = transcribe
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (transcribe) model.transcribe(source, target) else model.record(title, source, target)
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val back = {
        if (state.recording || state.busy) leaveDialog = true
        else { model.leave(); onBack() }
    }
    BackHandler(onBack = back)
    val enabled = !state.busy && !state.recording && !state.unavailable
    Surface(Modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = MCastService.NOTES.title }) {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().imePadding()) {
            item {
                TextButton(onClick = back) { Text("← 서비스 홈") }
                Text(MCastService.NOTES.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text("녹음하고, 읽고, 다시 듣는 나만의 음성노트", style = MaterialTheme.typography.bodyMedium)
            }
            if (state.unavailable) item {
                Text("방송·파일 작업·모델 준비를 마친 뒤 시작하세요.", color = MaterialTheme.colorScheme.error)
            }
            item {
                if (note == null) OutlinedTextField(title, { title = it.take(120) }, label = { Text("노트 제목") },
                    enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
                else {
                    Text(note.title, style = MaterialTheme.typography.titleLarge)
                    Text("${formatArchiveSessionTime(note.createdAt)} · ${voiceNoteTime(note.durationMs)}", style = MaterialTheme.typography.bodySmall)
                }
                VoiceNoteLanguagePicker("말하는 언어", source, enabled, Build.VERSION.SDK_INT >= 34) { source = it }
                VoiceNoteLanguagePicker("괄호 안에 표시할 번역 언어", target, enabled, false) { target = requireNotNull(it) }
                Text("자동 전환은 Android 14 이상과 기기의 오프라인 언어팩 지원이 필요합니다. 한 문장 안의 혼합 언어는 정확히 구분되지 않을 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                when {
                    state.recording -> {
                        Text("● 녹음 중 ${voiceNoteTime(state.elapsedMs)}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
                        Button(onClick = model::stopRecording, modifier = Modifier.fillMaxWidth()) { Text("녹음 종료·저장") }
                    }
                    state.busy -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedButton(onClick = model::cancel) { Text("작업 중지") }
                    }
                    note == null -> Button(onClick = { request(false) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("새 녹음 시작") }
                    note.interrupted -> Button(onClick = model::recover, enabled = enabled) { Text("중단된 녹음 복구") }
                    else -> {
                        Button(onClick = { if (note.lines.isNotEmpty()) replaceDialog = true else request(true) },
                            enabled = enabled && Build.VERSION.SDK_INT >= 33 && note.durationMs > 0, modifier = Modifier.fillMaxWidth()) {
                            Text(if (note.lines.isEmpty()) "받아쓰기·번역 시작" else "선택한 언어로 다시 받아쓰기")
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model::playPause, enabled = enabled && note.durationMs > 0) { Text(if (state.playback.isPlaying) "일시정지" else "녹음 재생") }
                            OutlinedButton(onClick = { wav.launch("MCastTalk-${note.id}.wav") }, enabled = enabled && note.durationMs > 0) { Text("녹음 내려받기") }
                        }
                        if (state.playback.prepared) {
                            Slider(value = state.playback.positionMs.toFloat(), onValueChange = { model.seek(it.toLong()) },
                                valueRange = 0f..maxOf(1L, state.playback.durationMs).toFloat(), enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = "녹음 재생 위치" })
                            Text("${voiceNoteTime(state.playback.positionMs)} / ${voiceNoteTime(state.playback.durationMs)}")
                        }
                    }
                }
                Text("녹음 후 받아씁니다. 화면을 벗어나거나 잠그면 녹음은 종료·저장되고 변환 작업은 중지됩니다. 최대 60분.", style = MaterialTheme.typography.bodySmall)
                if (Build.VERSION.SDK_INT < 33) Text("이 기기는 녹음·재생·WAV 저장을 지원합니다. 받아쓰기는 Android 13 이상이 필요합니다.", style = MaterialTheme.typography.bodySmall)
                Text("음성과 문장은 기기 안에서 처리합니다. 번역 모델의 최초 준비에는 인터넷이 필요할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let { message -> item { Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
            state.playback.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (note != null && !state.recording) {
                note.notice?.let { notice -> item { Text(notice, style = MaterialTheme.typography.bodySmall) } }
                if (note.lines.isNotEmpty()) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { txt.launch("MCastTalk-${note.id}.txt") }, enabled = enabled) { Text("TXT 내려받기") }
                            OutlinedButton(onClick = { srt.launch("MCastTalk-${note.id}.srt") }, enabled = enabled) { Text("SRT 내려받기") }
                        }
                        Text("원문 (번역문) · 화자 이름은 직접 지정할 수 있습니다. 자동 화자 분리는 제공하지 않습니다. 구간 시각은 추정값일 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                    }
                    itemsIndexed(note.lines) { index, line ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${voiceNoteTime(line.startMs)} · ${line.language ?: "언어 미확인"}", style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(voiceNoteLineText(line), style = MaterialTheme.typography.bodyLarge) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { model.seek(line.startMs) }, enabled = enabled && state.playback.prepared) { Text("여기서 듣기") }
                                    TextButton(onClick = { speakerIndex = index; speaker = line.speaker }, enabled = enabled) { Text("화자 이름") }
                                }
                            }
                        }
                    }
                }
                item { OutlinedButton(onClick = { model.leave(); title = "" }, enabled = enabled) { Text("새 음성노트") } }
            }
            item { HorizontalDivider(); Text("음성노트 보관함", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
            if (state.library.isEmpty()) item { Text("아직 저장된 노트가 없습니다. 첫 녹음을 시작해 보세요.") }
            items(state.library, key = { it.id }) { saved ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(saved.title, style = MaterialTheme.typography.titleMedium)
                        Text("${formatArchiveSessionTime(saved.createdAt)} · ${if (saved.interrupted) "복구 필요" else voiceNoteTime(saved.durationMs)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { model.open(saved.id) }, enabled = enabled) { Text("열기") }
                            TextButton(onClick = { deleteNote = saved }, enabled = enabled) { Text("삭제") }
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
        text = { OutlinedTextField(speaker, { speaker = it.take(80).replace('\n', ' ') }, label = { Text("예: 화자 1 / 안내자") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { model.setSpeaker(index, speaker); speakerIndex = null }) { Text("저장") } },
        dismissButton = { TextButton(onClick = { speakerIndex = null }) { Text("취소") } }) }
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
