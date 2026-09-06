package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.guidecast.core.translation.GlossaryTerm
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Composable
internal fun GlossaryScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = (context.applicationContext as GuideCastApplication).glossary
    val scope = rememberCoroutineScope()
    var source by rememberSaveable { mutableStateOf("ko") }
    var target by rememberSaveable { mutableStateOf("en") }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<GlossaryRow>()) }
    var total by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<GlossaryRow?>(null) }
    var pending by remember { mutableStateOf<List<GlossaryTerm>?>(null) }
    var exportSource by rememberSaveable { mutableStateOf("ko") }
    var exportTarget by rememberSaveable { mutableStateOf("en") }
    var exportTemplate by rememberSaveable { mutableStateOf(false) }
    val warning by repository.warning.collectAsState()
    BackHandler(onBack = onBack)

    fun launchOperation(operation: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try { operation() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message?.take(240) ?: "사전 처리 실패" }
            finally { busy = false }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val request = Triple(exportSource, exportTarget, exportTemplate)
            launchOperation {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { writer ->
                        if (request.third) {
                            writer.write("\uFEFF${GlossaryCsv.header}\r\n")
                        } else repository.export(request.first, request.second, writer)
                    }
                }
                message = "CSV 저장 완료 · Excel에서 수정 후 CSV UTF-8로 저장해 가져오세요."
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val requestedSource = source
            val requestedTarget = target
            launchOperation {
                val terms = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        InputStreamReader(input, Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
                            .buffered().use(GlossaryCsv::read)
                    }
                }
                require(terms.isNotEmpty()) { "등록할 용어가 없습니다. 양식에 행을 추가하세요." }
                require(terms.all { it.sourceLanguage == requestedSource && it.targetLanguage == requestedTarget }) {
                    "선택한 언어 $requestedSource → $requestedTarget 의 용어만 가져오세요."
                }
                pending = terms
            }
        }
    }
    LaunchedEffect(source, target, query, page, revision) {
        loading = true
        try {
            delay(200)
            total = repository.count(source, target)
            rows = repository.search(source, target, query, page * 100)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message?.take(240) }
        finally { loading = false }
    }
    LazyColumn(modifier.fillMaxSize().imePadding().semantics { paneTitle = "번역 용어 사전" },
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← 설정으로") }
            Text("번역 용어 사전", style = MaterialTheme.typography.headlineMedium)
            Text("국립국어원 공공용어 통합 자료 · 영어·중국어·일본어 연계", style = MaterialTheme.typography.titleSmall)
            Text("표준 번역 정보 조회 및 공식 감수 신청은 국립국어원 공공언어 통합 지원 시스템(publang.korean.go.kr)에서 확인하실 수 있습니다. (출처: 국립국어원·서울시·한국관광공사·외교부·행정안전부)", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openExternalOrCopy(context, "https://publang.korean.go.kr") }) {
                    Text("공공언어 통합 지원 시스템 ↗")
                }
                OutlinedButton(onClick = { openExternalOrCopy(context, "https://www.korean.go.kr") }) {
                    Text("국립국어원 누리집 ↗")
                }
            }
            Text("사용자 수정이 우선합니다. Gemma에는 문장에 맞는 권장어를 전달하고, 등록한 오번역 표현은 자막·TTS 전에 교정합니다. 다른 번역 엔진은 오번역 표현 교정만 적용됩니다.", style = MaterialTheme.typography.bodySmall)
            Text("원음과 이미 확정된 자막·음성은 바꾸지 않으며 다음 번역부터 적용합니다.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            GlossaryLanguageChoice("입력 언어", source, busy) { source = it; page = 0 }
            GlossaryLanguageChoice("번역 언어", target, busy) { target = it; page = 0 }
            Text("$source → $target · ${total}개 용어", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, { query = it; page = 0 }, label = { Text("원어·번역어 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(onClick = { editing = GlossaryRow(GlossaryTerm(source, target, "", "", origin = "사용자 등록"), "", true) }, enabled = !busy && source != target, modifier = Modifier.fillMaxWidth()) { Text("용어 등록") }
            OutlinedButton(onClick = { exportSource = source; exportTarget = target; exportTemplate = false; exporter.launch("guidecast-$source-$target.csv") }, enabled = !busy && !loading, modifier = Modifier.fillMaxWidth()) { Text("이 언어 사전 내려받기") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportSource = source; exportTarget = target; exportTemplate = true; exporter.launch("guidecast-glossary-template.csv") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("양식 내려받기") }
                OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }, enabled = !busy && source != target, modifier = Modifier.weight(1f)) { Text("CSV 일괄 등록") }
            }
            Text("양식: source_language=$source, target_language=$target, source_term=원어, preferred_term=권장어, replace_translation=바꿀 오번역(선택), category=분류, origin=출처, enabled=1(적용)/0(보류). 기존 항목은 같은 언어·원어로 갱신합니다. XLS 직접 가져오기는 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
        items(rows, key = { "${it.term.sourceLanguage}:${it.term.targetLanguage}:${it.term.sourceTerm}" }) { row ->
            OutlinedCard(onClick = { editing = row }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.term.sourceTerm, style = MaterialTheme.typography.titleMedium)
                    Text(row.term.preferredTerm)
                    Text(if (!row.term.enabled) "자동 적용 보류 · 후보 확인 후 수정" else if (row.edited) "사용자 수정 · 적용" else "기본 사전 · 적용", style = MaterialTheme.typography.labelMedium)
                    Text("${row.term.category} · ${row.term.origin}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            if (rows.isEmpty() && !loading) Text("검색 결과가 없습니다. 새 용어를 등록할 수 있습니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { page-- }, enabled = page > 0 && !loading) { Text("이전 100개") }
                Text("${page + 1}쪽", Modifier.padding(top = 12.dp))
                TextButton(onClick = { page++ }, enabled = rows.size == 100 && !loading) { Text("다음 100개") }
            }
        }
    }
    editing?.let { row ->
        GlossaryEditDialog(row, onDismiss = { editing = null }, onSave = { term ->
            launchOperation { repository.save(listOf(term)); editing = null; revision++; message = "용어 저장 완료 · 다음 번역부터 적용됩니다." }
        }, onRestore = {
            launchOperation { repository.restore(row.term); editing = null; revision++; message = "사용자 수정 해제 · 기본 사전으로 복원했습니다." }
        }, busy = busy)
    }
    pending?.let { terms ->
        AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("일괄 등록 확인") },
            text = { Text("${terms.size}개 용어를 등록합니다. 같은 언어·원어는 덮어쓰고, 파일에 없는 기존 용어는 유지합니다. 기본 사전 원본은 보존합니다.\n\n${terms.take(3).joinToString("\n") { "${it.sourceTerm} → ${it.preferredTerm}" }}") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                launchOperation { repository.save(terms); pending = null; revision++; message = "${terms.size}개 등록 완료 · 다음 번역부터 적용됩니다." }
            }) { Text("등록 적용") } }, dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("취소") } })
    }
}

@Composable
private fun GlossaryLanguageChoice(label: String, selected: String, busy: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember { (listOf(TranslationLanguageOption("ko", "한국어 · Korean")) + TRANSLATION_LANGUAGE_OPTIONS).distinctBy { it.languageTag } }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !busy) { Text("$label · ${options.firstOrNull { it.languageTag == selected }?.label ?: selected} ▾") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text("${option.label} (${option.languageTag})") }, onClick = { expanded = false; onSelect(option.languageTag) })
            }
        }
    }
}

@Composable
private fun GlossaryEditDialog(row: GlossaryRow, onDismiss: () -> Unit, onSave: (GlossaryTerm) -> Unit, onRestore: () -> Unit, busy: Boolean) {
    var sourceTerm by remember(row) { mutableStateOf(row.term.sourceTerm) }
    var preferred by remember(row) { mutableStateOf(row.term.preferredTerm) }
    var replacement by remember(row) { mutableStateOf(row.term.replacement) }
    var enabled by remember(row) { mutableStateOf(row.term.enabled) }
    var error by remember { mutableStateOf<String?>(null) }
    val candidates = remember(row) { runCatching {
        val a = JSONArray(row.alternatives.ifBlank { "[]" })
        (0 until a.length()).map { GlossaryCsv.spokenCandidate(a.getJSONObject(it).getString("value")) }.distinct()
    }.getOrDefault(emptyList()) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("용어 수정 · ${row.term.sourceLanguage} → ${row.term.targetLanguage}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(sourceTerm, { sourceTerm = it }, readOnly = row.term.sourceTerm.isNotBlank(), label = { Text("원어") })
            OutlinedTextField(preferred, { preferred = it }, label = { Text("권장 번역어") })
            OutlinedTextField(replacement, { replacement = it }, label = { Text("바꿀 오번역 표현 (선택)") })
            Text("오번역 표현은 해당 원어가 나온 문장의 번역문에서만 교정합니다. 일본어·중국어 한 글자 치환은 복합어 의미를 바꿀 수 있으니 구절로 등록하세요. 조사·문법·동음이의어는 실제 통역 시험으로 확인하세요.", style = MaterialTheme.typography.bodySmall)
            if (candidates.size > 1) {
                Text("출처별 번역 후보 · 확인 후 선택", style = MaterialTheme.typography.titleSmall)
                candidates.forEach { candidate -> TextButton(onClick = { preferred = candidate; enabled = true }) { Text(candidate) } }
            }
            Row { Checkbox(enabled, { enabled = it }); Text("자동 적용", Modifier.padding(top = 12.dp)) }
            Text("분류: ${row.term.category}\n원출처: ${row.term.origin}", style = MaterialTheme.typography.bodySmall)
            if (row.edited && row.term.sourceTerm.isNotBlank()) TextButton(onClick = onRestore, enabled = !busy) { Text("사용자 수정 해제 / 기본값 복원") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            runCatching { GlossaryCsv.validate(listOf(row.term.copy(sourceTerm = sourceTerm, preferredTerm = preferred, replacement = replacement, enabled = enabled))).single() }
                .onSuccess(onSave).onFailure { error = it.message }
        }) { Text("저장") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } })
}
