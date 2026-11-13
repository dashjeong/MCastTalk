package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.guidecast.core.translation.BriefingFormality
import app.guidecast.core.translation.InterpretationBriefing
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InterpretationBriefingScreen(settings: InterpretationBriefingSettings, onBack: () -> Unit) {
    val saved by settings.state.collectAsState()
    var topic by rememberSaveable { mutableStateOf(saved.topic) }
    var situation by rememberSaveable { mutableStateOf(saved.situation) }
    var speaker by rememberSaveable { mutableStateOf(saved.speaker) }
    var audience by rememberSaveable { mutableStateOf(saved.audience) }
    var notes by rememberSaveable { mutableStateOf(saved.notes) }
    var terms by rememberSaveable { mutableStateOf(saved.terms) }
    var register by rememberSaveable { mutableStateOf(saved.formality.name) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val draft = InterpretationBriefing(topic, situation, speaker, audience, BriefingFormality.valueOf(register), notes,
        configured = saved.configured, terms = terms)
    fun leave() { if (draft != saved) confirmLeave = true else onBack() }
    fun saveAndLeave() { settings.save(draft); onBack() }
    BackHandler { leave() }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false },
        title = { Text("입력 내용을 저장할까요?") },
        text = { Text("아직 저장하지 않은 행사 정보가 있습니다. 저장하면 다음 문장부터 참고해요.") },
        confirmButton = { TextButton(onClick = { saveAndLeave() }) { Text("저장하고 계속") } },
        dismissButton = { Column {
            TextButton(onClick = onBack) { Text("입력 버리고 계속") }
            TextButton(onClick = { confirmLeave = false }) { Text("편집 계속") }
        } })
    Scaffold(bottomBar = {
        Surface(Modifier.imePadding()) {
            Button(onClick = { saveAndLeave() }, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) { Text("저장") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().semantics { paneTitle = "행사 정보" },
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = { leave() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("돌아가기") }
                Text("행사 정보", style = MaterialTheme.typography.headlineMedium)
                Text("통역할 행사와 참석자를 알려 주세요. 다음 문장부터 참고해요.")
                Text("온라인 통역을 사용하면 이 정보도 함께 보내요.", style = MaterialTheme.typography.bodySmall)
            }
            item { BriefingField("주제", topic) { topic = it } }
            item { BriefingField("장소 · 행사 종류", situation) { situation = it } }
            item { BriefingField("말하는 사람 · 이름과 직함", speaker) { speaker = it } }
            item { BriefingField("듣는 사람", audience) { audience = it } }
            item {
                BriefingField("자주 나올 이름 · 용어", terms) { terms = it }
                Text("쉼표나 줄바꿈으로 나눠 주세요. 예: 철원, 노동당사, 소이산=Soisan", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("높임말 수준", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BriefingFormality.entries.forEach { level ->
                        FilterChip(selected = register == level.name, onClick = { register = level.name },
                            modifier = Modifier.heightIn(min = 48.dp), label = { Text(level.label) })
                    }
                }
            }
            item { BriefingField("꼭 지킬 표현 · 메모", notes) { notes = it } }
            item { Text("합계 ${topic.length + situation.length + speaker.length + audience.length + notes.length + terms.length + BriefingFormality.valueOf(register).label.length}/600자 · 넘는 내용은 저장할 때 줄여요.",
                style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BriefingField(label: String, value: String, change: (String) -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    OutlinedTextField(value, onValueChange = { change(it.take(600)) }, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(requester).onFocusChanged {
            if (it.isFocused) scope.launch { requester.bringIntoView() }
        })
}
