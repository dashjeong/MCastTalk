package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TeacherLearningControls(settings: DeveloperLabSettings, reviewer: CloudTranslationReviewer, onReports: () -> Unit) {
    val options by settings.state.collectAsState()
    val progress by reviewer.learningProgress.collectAsState()
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("ko") }
    var target by remember { mutableStateOf("en") }
    var original by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var showInput by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("스승을 통한 자가학습 · 자기진단적 강화학습", style = MaterialTheme.typography.titleMedium)
    Text("기기에서 문제 후보 선별 → ChatGPT(OpenAI API)·Gemini와 비교 → 개선안 제안 → 사용자 승인·보류 → 핵심 문장 반영. 모델 가중치를 재학습하는 기능은 아닙니다.", style = MaterialTheme.typography.bodySmall)
    Text("숫자 불일치, 원문 그대로 출력, 목표 언어·길이 이상, 동일 원문의 번역 변동과 직접 요청한 문장을 검토합니다. 정상·중복 항목은 보내지 않습니다. 선별 신호 자체는 오류 확정이 아닙니다.", style = MaterialTheme.typography.bodySmall)
    Text("최대 분당 2건 · 동시 2건 · 건당 원문 4,000자/번역 8,000자. 실시간 낭독은 기다리지 않으며, 실패 시 원래 번역을 유지합니다. 저장된 보정은 같은 언어·문체·원문에만 적용합니다.", style = MaterialTheme.typography.bodySmall)
    Text("이번 실행: 진단 ${progress.examined} · 전송 대상 ${progress.selected} · 생략 ${progress.skipped} · 개선안 ${progress.proposed} · 변경 없음 ${progress.unchanged} · 미반영 ${progress.rejected} · 재사용 ${progress.reused}", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onReports, modifier = Modifier.fillMaxWidth()) { Text("학습 전후 비교 리포트") }
    TextButton(onClick = { showInput = !showInput }) { Text(if (showInput) "문장 진단 접기" else "개선할 문장 직접 진단") }
    if (showInput) {
        OutlinedTextField(source, { source = it.take(30) }, label = { Text("원문 언어 코드 · 예: ko, en, ja") }, singleLine = true, enabled = !busy)
        OutlinedTextField(target, { target = it.take(30) }, label = { Text("번역 언어 코드 · 예: en, ko, zh") }, singleLine = true, enabled = !busy)
        OutlinedTextField(original, { original = it.take(4_000) }, label = { Text("진단할 원문") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft, { draft = it.take(8_000) }, label = { Text("앱 번역 · 학습 전") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && options.teacherLearningEnabled && options.cloudReviewEnabled && options.hasApiKey &&
            reviewTextWithinBounds(original, draft) && runCatching { normalizeMemoryLanguage(source); normalizeMemoryLanguage(target) }.isSuccess,
            modifier = Modifier.fillMaxWidth(), onClick = {
                val text = original; val translation = draft; val from = source; val to = target
                val register = options.translationRegister
                busy = true; message = "선택한 한 문장을 진단하고 있습니다."
                scope.launch {
                    try {
                        withContext(TranslationStyleContext(TranslationStyle.valueOf(register.name))) {
                            reviewer.refine(from, to, text, translation, requestTeacherReview = true)
                        }
                        message = "진단 처리를 마쳤습니다. 리포트에서 개선안을 승인·보류하세요. 중복·횟수 제한·확정 문장은 새 검토를 생략합니다."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "진단하지 못했습니다. 설정을 확인하고 다시 시도하세요." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "진단 중" else "이 문장 진단·학습") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun TeacherLearningReportScreen(memory: SentenceTranslationMemory, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by memory.revision.collectAsState()
    var reports by remember { mutableStateOf(emptyList<TeacherLearningReport>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var clearRequested by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val snapshot = memory.teacherReports()
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                    it.write(teacherReportExport(snapshot))
                } }
                message = "선별된 학습 리포트를 저장했습니다. 이 파일에는 원문과 번역이 포함됩니다."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "리포트를 저장하지 못했습니다. 저장 위치를 확인하세요." }
            finally { busy = false }
        }
    }
    LaunchedEffect(revision) {
        try { reports = memory.teacherReports() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "리포트를 읽지 못했습니다. 다시 열어 주세요." }
    }
    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text("실험실로") }
                Text("학습 전후 비교 리포트", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text("최근 선별 결과 최대 200건을 기기에 보관합니다. 전체 방송·음원·로그는 수집하지 않습니다. 학습 내용은 문장·회화 사전에서 확인·수정할 수 있습니다.")
                Text("숫자·문자·내용 검사는 의미 정확도나 자연스러움의 실측 점수가 아닙니다. ‘핵심 개선’은 스승 모델이 제안한 분류이며, 원문과 함께 확인하세요.", style = MaterialTheme.typography.bodySmall)
                Text("표시 ${reports.size}건 · 승인 대기 ${reports.count { it.outcome == TeacherReviewOutcome.PROPOSED }} · 승인 적용 ${reports.count { it.outcome == TeacherReviewOutcome.APPROVED }} · 보류 ${reports.count { it.outcome == TeacherReviewOutcome.HELD }}")
                OutlinedButton(enabled = !busy && reports.isNotEmpty(), onClick = { export.launch("MCastTalk-learning-report.json") }) { Text("비교 리포트 내보내기") }
                Text("이관용 백업·가져오기는 설정 → 사전 백업에서 함께 처리합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy && reports.isNotEmpty(), onClick = { clearRequested = true }) { Text("리포트 기록 지우기") }
                message?.let { Text(it) }
            }
            if (reports.isEmpty()) item { Text("선별된 학습 리포트가 없습니다. 학습을 켜고 개선할 문장을 진단하세요.") }
            items(reports, key = { it.key }) { report ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(report.outcome.label, style = MaterialTheme.typography.titleMedium)
                        Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.createdAtEpochMillis))} · ${report.sourceLanguageTag} → ${report.targetLanguageTag}", style = MaterialTheme.typography.labelMedium)
                        Text("선별 이유: ${report.signals.joinToString { it.label }}")
                        Text("원문: ${report.original}")
                        Text("학습 전 · 앱 번역: ${report.before}")
                        Text("학습 후 후보 · 스승 보정: ${report.after ?: "결과 없음"}", color = MaterialTheme.colorScheme.primary)
                        Text("핵심 개선: ${report.lessons.joinToString { it.label }.ifEmpty { "확인된 보정 분류 없음" }}")
                        TextButton(onClick = { expanded = if (expanded == report.key) null else report.key }) { Text("전후 검사 비교") }
                        if (expanded == report.key) {
                            report.checks().forEach { check ->
                                fun label(value: Boolean?) = when (value) { true -> "통과"; false -> "확인 필요"; null -> "결과 없음" }
                                Text("${check.label}: ${label(check.before)} → ${label(check.after)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("검토 엔진: ${report.provider.name} / ${report.modelId}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (report.outcome in setOf(TeacherReviewOutcome.PROPOSED, TeacherReviewOutcome.HELD)) {
                            fun decide(approve: Boolean) { scope.launch {
                                busy = true
                                try { message = if (memory.decideTeacherLearning(report, approve)) {
                                    if (approve) "승인한 보정을 같은 원문·언어·문체에 적용합니다." else "보류했습니다. 현재 통번역은 유지됩니다."
                                } else "기존 확정 문장 또는 변경된 기록이 있어 적용하지 않았습니다." }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { message = "결정을 저장하지 못했습니다. 다시 시도하세요." }
                                finally { busy = false }
                            } }
                            OutlinedButton(enabled = !busy, onClick = { decide(true) }) { Text("승인·적용") }
                            TextButton(enabled = !busy, onClick = { decide(false) }) { Text("보류") }
                        }
                        if (report.outcome in setOf(TeacherReviewOutcome.LEARNED, TeacherReviewOutcome.APPROVED)) OutlinedButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try { message = if (memory.undoTeacherLearning(report)) "이 리포트에서 적용한 보정을 취소했습니다. 이후 수정된 문장은 유지됩니다."
                                    else "현재 문장이 이미 변경·확정·삭제되어 취소하지 않았습니다." }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { message = "학습을 취소하지 못했습니다. 다시 시도하세요." }
                                finally { busy = false }
                            }
                        }) { Text("이 학습 취소") }
                    }
                }
            }
        }
    }
    if (clearRequested) AlertDialog(onDismissRequest = { clearRequested = false }, title = { Text("리포트 기록을 지울까요?") },
        text = { Text("비교 기록과 중복 검토 이력을 지웁니다. 저장된 문장 보정은 유지됩니다. 취소한 후보도 이후 다시 선별될 수 있습니다.") },
        confirmButton = { TextButton(onClick = { clearRequested = false; scope.launch {
            try { memory.clearTeacherReports() } catch (_: Exception) { message = "기록을 지우지 못했습니다." }
        } }) { Text("기록 지우기") } }, dismissButton = { TextButton(onClick = { clearRequested = false }) { Text("유지") } })
}
