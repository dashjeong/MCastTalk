package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SentenceMemoryScreen(memory: SentenceTranslationMemory, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val revision by memory.revision.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var offset by rememberSaveable { mutableStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<SentenceMemoryEntry>()) }
    var loaded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SentenceMemoryEntry?>(null) }
    var editorVisible by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SentenceMemoryEntry?>(null) }
    var source by rememberSaveable { mutableStateOf("ko-KR") }
    var target by rememberSaveable { mutableStateOf("en-US") }
    var register by rememberSaveable { mutableStateOf(TranslationRegister.FORMAL) }
    // Content drafts are intentionally not copied into saved activity state or diagnostics.
    var original by remember { mutableStateOf("") }
    var corrected by remember { mutableStateOf("") }
    LaunchedEffect(query, offset, revision) {
        loaded = false
        delay(150)
        try { entries = memory.loadPage(offset, 100, query); loaded = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "문장 사전을 읽지 못했습니다. 다시 열어 주세요." }
    }
    fun edit(entry: SentenceMemoryEntry?) {
        editing = entry; editorVisible = true
        source = entry?.sourceLanguageTag ?: source; target = entry?.targetLanguageTag ?: target
        register = entry?.translationRegister ?: register
        original = entry?.original.orEmpty(); corrected = entry?.corrected.orEmpty(); message = null
    }
    fun mutate(action: suspend () -> Boolean, success: String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = if (action()) success else "저장하지 못했습니다. 중복 문장과 사전 용량을 확인하세요." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "작업하지 못했습니다. 입력 내용을 확인하고 다시 시도하세요." }
            finally { busy = false }
        }
    }
    Surface(Modifier.fillMaxSize().safeDrawingPadding().imePadding().semantics { paneTitle = "문장·회화 사전" }) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text("설정으로") }
                Text("문장·회화 사전", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("원문이 일치하는 문장에 확인한 번역을 적용합니다. AI가 저장한 문장은 확인 전까지 개발자 학습 모드에서만 사용합니다.")
                Text("모델 자체를 재학습하지 않습니다. 백업과 가져오기는 설정의 데이터 이관에서 관리하세요.", style = MaterialTheme.typography.bodySmall)
            }
            item { OutlinedTextField(query, { query = it.take(200); offset = 0 }, label = { Text("원문 또는 번역 검색") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item { OutlinedButton(onClick = { edit(null) }, enabled = !busy) { Text("문장 추가") } }
            if (editorVisible) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SentenceLanguageChoice("원문", source, !busy) { source = it }
                        SentenceLanguageChoice("번역", target, !busy) { target = it }
                        TranslationRegister.entries.forEach { option ->
                            FilterChip(selected = option == register, enabled = !busy, onClick = { register = option },
                                label = { Text(when (option) { TranslationRegister.AUTO -> "문맥에 맞게"; TranslationRegister.FORMAL -> "공식·안내"; TranslationRegister.CONVERSATIONAL -> "대화·의역 실험" }) })
                        }
                        OutlinedTextField(original, { original = it.take(4_000) }, label = { Text("원문") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(corrected, { corrected = it.take(8_000) }, label = { Text("확인한 번역") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && original.isNotBlank() && corrected.isNotBlank(), onClick = {
                            val row = SentenceMemoryEntry(editing?.id ?: 0, source, target, register,
                                original.trim(), corrected.trim(), SentenceMemoryOrigin.USER)
                            mutate({ memory.upsert(row).also { if (it) { editorVisible = false; original = ""; corrected = "" } } }, "확인한 문장을 저장했습니다.")
                        }) { Text("확인·저장") }
                        TextButton(enabled = !busy, onClick = { editorVisible = false; original = ""; corrected = "" }) { Text("취소") }
                    }
                }
            }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (loaded && entries.isEmpty()) item { Text("이 페이지에는 저장된 문장이 없습니다.") }
            items(entries, key = { it.id }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${sentenceLanguageLabel(entry.sourceLanguageTag)} → ${sentenceLanguageLabel(entry.targetLanguageTag)} · " +
                            when (entry.translationRegister) { TranslationRegister.AUTO -> "문맥에 맞게"; TranslationRegister.FORMAL -> "공식·안내"; TranslationRegister.CONVERSATIONAL -> "대화" })
                        Text(entry.original, style = MaterialTheme.typography.bodyLarge)
                        Text(entry.corrected, color = MaterialTheme.colorScheme.primary)
                        Text(if (entry.origin == SentenceMemoryOrigin.USER) "사용자 확인 완료" else "AI 보정 · 내용 확인 필요", style = MaterialTheme.typography.labelMedium)
                        if (entry.origin == SentenceMemoryOrigin.AI) OutlinedButton(enabled = !busy, onClick = {
                            mutate({ memory.confirm(entry.id) }, "문장을 확인했습니다. 일반 통번역에도 적용합니다.")
                        }) { Text("내용 확인·적용") }
                        Row {
                            TextButton(enabled = !busy, onClick = { edit(entry) }) { Text("수정") }
                            TextButton(enabled = !busy, onClick = { deleting = entry }) { Text("삭제") }
                        }
                    }
                }
            }
            item {
                Text("${offset / 100 + 1}페이지 · 최대 100개씩 표시")
                Row {
                    TextButton(enabled = loaded && offset > 0, onClick = { offset = (offset - 100).coerceAtLeast(0) }) { Text("이전") }
                    TextButton(enabled = loaded && entries.size == 100, onClick = { offset += 100 }) { Text("다음") }
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("이 문장을 삭제할까요?") },
            text = { Text("저장된 번역 보정이 제거됩니다. 내보낸 백업 파일은 유지됩니다.") },
            confirmButton = { TextButton(onClick = { deleting = null; mutate({ memory.delete(entry.id); true }, "문장을 삭제했습니다.") }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } })
    }
}

private fun sentenceLanguageLabel(tag: String): String = if (tag.equals("zh-TW", true)) "중국어(번체)"
    else FILE_LANGUAGE_OPTIONS.entries
        .firstOrNull { it.key.substringBefore('-').equals(tag.substringBefore('-'), true) }?.value ?: tag

@Composable
private fun SentenceLanguageChoice(label: String, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("$label: ${sentenceLanguageLabel(selected)}") }
        DropdownMenu(expanded, { expanded = false }) {
            (FILE_LANGUAGE_OPTIONS + mapOf("zh-TW" to "중국어(번체)")).forEach { (tag, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(tag) })
            }
        }
    }
}
