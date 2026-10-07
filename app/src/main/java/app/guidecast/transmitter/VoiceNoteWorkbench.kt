package app.guidecast.transmitter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.launch

@Composable
internal fun VoiceNoteWorkbench(model: VoiceNoteViewModel, state: VoiceNoteUiState, onBack: () -> Unit) {
    val note = state.selected ?: return
    val context = LocalContext.current
    val options by model.apiSettings.state.collectAsState()
    var tab by rememberSaveable(note.id) { mutableStateOf(0) }
    var target by rememberSaveable(note.id) { mutableStateOf(note.targetLanguage) }
    var engine by rememberSaveable(note.id) { mutableStateOf(FileTranslationEngine.MLKIT) }
    var reviewEngine by rememberSaveable(note.id) { mutableStateOf(FileTranslationEngine.GEMMA) }
    var tone by rememberSaveable(note.id) { mutableStateOf(TranslationStyle.AUTO) }
    var fromOriginal by rememberSaveable(note.id) { mutableStateOf(true) }
    var apiVisible by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var focused by remember { mutableStateOf<VoiceNoteFinding?>(null) }
    var confirmApply by remember { mutableStateOf(false) }
    var confirmRetranslate by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var speechMessage by remember { mutableStateOf<String?>(null) }
    val speaker = remember(context) { VoiceNoteTextSpeaker(context) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(speaker, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speaker.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); speaker.close() }
    }
    LaunchedEffect(state.busy, state.unavailable) { if (state.busy || state.unavailable) speaker.stop() }
    LaunchedEffect(note.targetLanguage) { target = note.targetLanguage }
    LaunchedEffect(state.findings) {
        selectedIds = selectedIds.intersect(state.findings.map { it.id }.toSet())
        if (focused?.id !in state.findings.map { it.id }) focused = null
    }
    val enabled = !state.busy && !state.recording && !state.unavailable
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val back = { if (state.busy) confirmLeave = true else { speaker.stop(); onBack() } }
    BackHandler(onBack = back)
    fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(note.title, text))
    }
    fun share(text: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, note.title), "노트 공유"))
    }
    val displayText = note.lines.joinToString("\n\n") { when (tab) { 0 -> it.originalTranscript; 1 -> it.original; else -> it.translation } }
    Surface(Modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = "음성 노트 문장 검사와 번역" }) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = back) { Text("노트로 돌아가기") }
                Text(note.title, style = MaterialTheme.typography.headlineSmall)
                Text("받아쓴 원문과 녹음은 보존합니다. 선택한 수정은 교정본에만 반영합니다.")
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("원문", "교정본", "번역문").forEachIndexed { index, name -> FilterChip(tab == index, { tab = index }, label = { Text(name) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copy(displayText) }, enabled = displayText.isNotBlank() && enabled) { Text("전체 복사") }
                    TextButton(onClick = { share(displayText) }, enabled = displayText.isNotBlank() && enabled) { Text("공유") }
                    TextButton(onClick = model::undoCorrection, enabled = state.canUndoCorrection && enabled) { Text("교정 되돌리기") }
                    TextButton(onClick = { speaker.stop(); model.pauseNotePlayback() }) { Text("재생 중지") }
                }
            }
            item {
                Text("문장 검사", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(reviewEngine == FileTranslationEngine.GEMMA, { reviewEngine = FileTranslationEngine.GEMMA }, enabled = enabled, label = { Text("기기 내 AI") })
                    FilterChip(reviewEngine == FileTranslationEngine.API, { reviewEngine = FileTranslationEngine.API }, enabled = enabled, label = { Text("온라인 AI") })
                }
                Button(onClick = { speaker.stop(); focused = null; model.checkSource(reviewEngine) }, enabled = enabled && note.lines.isNotEmpty()) { Text("맞춤법·문맥 검사") }
                Text("기기 내 AI는 설정에서 모델을 먼저 준비하세요. 온라인 AI는 설정한 텍스트 API와 전송 동의를 사용합니다.", style = MaterialTheme.typography.bodySmall)
            }
            if (state.findings.isNotEmpty()) {
                item {
                    Text("수정 후보 ${state.findings.size}개 · 항목을 누르면 해당 문장을 보여줍니다.")
                    Button(onClick = { confirmApply = true }, enabled = enabled && selectedIds.isNotEmpty()) { Text("선택한 ${selectedIds.size}개 수정 확인") }
                }
                items(state.findings, key = { "finding-${it.id}" }) { finding ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable {
                        focused = finding; tab = 1
                        scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - note.lines.size + finding.lineIndex).coerceAtLeast(0)) }
                    }) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Checkbox(finding.id in selectedIds, { checked -> selectedIds = if (checked) selectedIds + finding.id else selectedIds - finding.id }, enabled = enabled)
                                Column {
                                    Text("${voiceNoteTime(note.lines[finding.lineIndex].startMs)} · ${finding.before.ifEmpty { "문구 삽입" }} → ${finding.after.ifEmpty { "삭제" }}")
                                    Text(finding.reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            TextButton(onClick = { model.dismissFinding(finding.id) }, enabled = enabled) { Text("이번 제안 제외") }
                        }
                    }
                }
            }
            item {
                Text("번역", style = MaterialTheme.typography.titleMedium)
                var languagesVisible by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { languagesVisible = true }, enabled = enabled) { Text("번역 언어: ${VOICE_NOTE_LANGUAGES[target]}") }
                DropdownMenu(languagesVisible, { languagesVisible = false }) {
                    VOICE_NOTE_LANGUAGES.forEach { (tag, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                        target = tag; languagesVisible = false; model.selectTranslationLanguage(tag)
                    }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(FileTranslationEngine.MLKIT to "오프라인 기본", FileTranslationEngine.GEMMA to "오프라인 AI", FileTranslationEngine.API to "온라인 AI").forEach { (mode, label) ->
                        FilterChip(engine == mode, { engine = mode }, enabled = enabled, label = { Text(label) })
                    }
                }
                Row {
                    FilterChip(fromOriginal, { fromOriginal = true }, enabled = enabled, label = { Text("원문 번역") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(!fromOriginal, { fromOriginal = false }, enabled = enabled, label = { Text("교정본 번역") })
                }
                if (engine != FileTranslationEngine.MLKIT) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TranslationStyle.AUTO to "원문에 맞춤", TranslationStyle.FORMAL to "문어체", TranslationStyle.CONVERSATIONAL to "대화체").forEach { (style, label) ->
                        FilterChip(tone == style, { tone = style }, enabled = enabled, label = { Text(label) })
                    }
                }
                Text(if (engine == FileTranslationEngine.API) "${options.provider.label} · ${options.model}" else
                    if (engine == FileTranslationEngine.GEMMA) "기기 내 기본 번역 + ${model.selectedLocalModelName} 검토" else "준비된 기기 내 번역 모델 사용", style = MaterialTheme.typography.bodySmall)
                if (engine == FileTranslationEngine.GEMMA || reviewEngine == FileTranslationEngine.GEMMA)
                    Text("기기 내 AI는 한국어·영어·일본어·중국어·스페인어·아랍어를 지원합니다. 러시아어·베트남어 등은 오프라인 기본 번역 또는 온라인 AI를 선택하세요.", style = MaterialTheme.typography.bodySmall)
                if (target == "zh-TW") Text("중국어(대만·번체)는 대만에서 사용하는 중국어입니다. 대만 민난어 번역은 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { speaker.stop(); tab = 2; model.translateRemaining(target, engine, tone, fromOriginal) }, enabled = enabled && note.lines.isNotEmpty()) { Text("번역·미완료 이어하기") }
                    TextButton(onClick = { confirmRetranslate = true }, enabled = enabled && note.lines.any { it.translation.isNotBlank() }) { Text("다시 번역") }
                }
                TextButton(onClick = { apiVisible = !apiVisible }, enabled = enabled) { Text("온라인 모델·API 설정") }
                if (apiVisible) AdvancedTranslationApiPanel(model.apiSettings, model.apiService, enabled)
            }
            item {
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancelProcessing) { Text("작업 중지") } }
                state.message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                speechMessage?.let { Text(it) }
            }
            items(note.lines.indices.toList(), key = { "text-line-$it" }) { index ->
                val line = note.lines[index]
                val finding = focused?.takeIf { it.lineIndex == index && it.sourceFingerprint == voiceNoteSourceFingerprint(line.original) }
                val text = when (tab) { 0 -> line.originalTranscript; 1 -> line.original; else -> line.translation }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1} · ${voiceNoteTime(line.startMs)}", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            if (tab == 1 && finding != null) Text(buildAnnotatedString {
                                append(text.substring(0, finding.start))
                                withStyle(SpanStyle(background = highlightColor)) {
                                    append(if (finding.start == finding.end) "▏" else text.substring(finding.start, finding.end))
                                }
                                append(text.substring(finding.end))
                            }) else Text(text.ifBlank { "아직 번역하지 않은 문장입니다." })
                        }
                        if (tab == 2) {
                            val variant = line.translations[note.targetLanguage]
                            if (variant != null) Text("${variant.model} · ${if (variant.fromOriginal) "원문 기준" else "교정본 기준"}", style = MaterialTheme.typography.bodySmall)
                            if (variant != null && !line.translationIsCurrent(note.targetLanguage)) Text("교정본 변경 후 만든 번역이 아닙니다. 다시 번역하세요.", color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { speaker.stop(); model.playFrom(line.startMs) }, enabled = enabled && !note.interrupted) { Text("원음 듣기") }
                            TextButton(onClick = { copy(text) }, enabled = enabled && text.isNotBlank()) { Text("복사") }
                            if (tab == 2) TextButton(onClick = { speechMessage = if (speaker.speak(text, note.targetLanguage)) null else "이 언어의 기기 내 음성이 준비되지 않았습니다. 음성 설정을 확인하세요." },
                                enabled = enabled && text.isNotBlank() && !state.playback.isPlaying) { Text("번역 듣기") }
                        }
                    }
                }
            }
        }
    }
    if (confirmApply) AlertDialog(onDismissRequest = { confirmApply = false }, title = { Text("선택한 수정만 적용할까요?") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Text(state.findings.filter { it.id in selectedIds }.joinToString("\n") { "${it.before.ifEmpty { "삽입" }} → ${it.after.ifEmpty { "삭제" }}" })
            Text("\n받아쓴 원문은 그대로 두고 교정본에만 반영합니다.")
        } },
        confirmButton = { TextButton(onClick = { model.applyFindings(selectedIds); confirmApply = false; tab = 1 }, enabled = enabled) { Text("교정본에 적용") } },
        dismissButton = { TextButton(onClick = { confirmApply = false }) { Text("취소") } })
    if (confirmRetranslate) AlertDialog(onDismissRequest = { confirmRetranslate = false }, title = { Text("이 언어의 번역을 다시 만들까요?") },
        text = { Text("${VOICE_NOTE_LANGUAGES[target]} 번역을 모두 완료한 뒤 교체합니다. 직접 편집한 번역도 교체되며, 다른 언어 번역과 원문은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { model.translateRemaining(target, engine, tone, fromOriginal, true); confirmRetranslate = false; tab = 2 }, enabled = enabled) { Text("다시 번역") } },
        dismissButton = { TextButton(onClick = { confirmRetranslate = false }) { Text("취소") } })
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("작업을 중지하고 돌아갈까요?") },
        text = { Text("녹음과 원문, 이미 저장된 결과는 유지됩니다.") },
        confirmButton = { TextButton(onClick = { model.cancelProcessing(); confirmLeave = false; speaker.stop(); onBack() }) { Text("중지하고 돌아가기") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("계속 작업") } })
}
