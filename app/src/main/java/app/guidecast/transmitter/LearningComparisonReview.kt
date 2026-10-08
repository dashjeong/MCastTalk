package app.guidecast.transmitter

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Human-reviewed examples only; provider output is never an automatic training target. */
@Composable
internal fun LearningComparisonReview(comparison: ShadowComparison?, repository: DomainCorpusRepository,
    commitAdmission: ((ShadowComparison) -> NativeComparisonCommitAdmission)? = null,
    isComparisonCurrent: (ShadowComparison) -> Boolean = { true },
    referenceLabel: String = "온라인", entryLabel: String = "비교 예문 직접 검수 · 로컬 자료 개정",
    initialReferenceCorrection: Boolean = false,
    correctedTranslationAllowed: (String) -> Boolean = { true },
    showEntry: Boolean = true, showRollback: Boolean = true) {
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var candidate by remember { mutableStateOf<ShadowComparison?>(null) }
    var corrected by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf<DomainLearningRevision?>(null) }
    var confirmRollback by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val correctedReady = corrected.trim().length in 1..500 && correctedTranslationAllowed(corrected)
    LaunchedEffect(revision) {
        rollback = repository.latestLearningRevision()
        if (!busy) {
            reviewed = false
            if (candidate?.let { it.corpusRevision != revision || !isComparisonCurrent(it) } == true) candidate = null
        }
    }
    LaunchedEffect(comparison) {
        if (!busy && candidate?.let { it.corpusRevision != revision || !isComparisonCurrent(it) } == true) {
            candidate = null; reviewed = false
        }
    }
    if (showEntry && comparison != null) Text(
        if (comparison.corpusRevision == revision && isComparisonCurrent(comparison))
            "최근 비교 결과 · 직접 검수 대기. 비교 완료만으로 정확도가 승인되거나 전문 자료에 예문이 저장되지는 않습니다."
        else "현재 자료·설정과 다른 비교 결과입니다. 저장하려면 현재 조건으로 다시 비교하세요.")
    if (showEntry) TextButton(enabled = comparison != null && !busy && comparison.corpusRevision == revision && isComparisonCurrent(comparison), onClick = {
        candidate = comparison
        corrected = if (initialReferenceCorrection) comparison?.online.orEmpty() else ""
        reviewed = false
    }) { Text(entryLabel) }
    if (showRollback && rollback != null) {
        Text("직접 검수한 예문 자료가 활성화되어 있습니다. 언어·문체 조건이 맞는 준비된 로컬 자료에서 재사용하며, 모델 가중치를 학습하지 않습니다.")
        TextButton(enabled = !busy, onClick = { confirmRollback = true }) { Text("직전 검수 자료로 되돌리기") }
    }
    message?.let { Text(it) }
    candidate?.let { captured ->
        ReviewedComparisonDialog(onDismissRequest = { if (!busy) { candidate = null; reviewed = false } },
            title = "예문 검수·저장", confirmButton = {
            TextButton(enabled = reviewed && correctedReady && !busy &&
                captured.corpusRevision == revision && isComparisonCurrent(captured), onClick = {
                val approvedCorrection = corrected
                val approvedReview = reviewed
                if (busy || !approvedReview || approvedCorrection.trim().length !in 1..500 ||
                    !correctedTranslationAllowed(approvedCorrection)) return@TextButton
                busy = true
                scope.launch {
                    try {
                        check(captured.corpusRevision == repository.revision.value && isComparisonCurrent(captured)) {
                            "중계·비교 설정이나 자료가 바뀌었습니다. 새 예문을 확인하세요."
                        }
                        val applied = repository.applyReviewedComparison(captured, approvedCorrection, approvedReview, commitAdmission?.invoke(captured))
                        message = "직접 검수한 예문을 자료 버전 ${applied.revision}에 저장하고 활성 자료로 선택했습니다. 실제 품질 향상은 별도 검증합니다."
                        candidate = null; reviewed = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "자료 개정 실패" }
                    finally { busy = false }
                }
            }) { Text("검수 승인 · 기기에 저장") }
        }, dismissButton = {
            TextButton(enabled = !busy, onClick = { candidate = null; reviewed = false }) { Text("취소") }
        }) {
            Text("원문: ${captured.original}\n$referenceLabel: ${captured.online}\n오프라인: ${captured.offline}")
            captured.offlineModel?.let { Text("비교 기준: $it") }
            Text("원문과 두 번역을 읽고 사용할 번역을 직접 확인하세요. 저장하면 기존 활성 자료를 복사한 새 예문 자료가 선택됩니다. 준비된 로컬 자료에서 조건이 맞을 때 재사용하며, 처리 중인 발화와 이전 자료는 유지됩니다.")
            OutlinedTextField(corrected, {
                if (!busy && it != corrected) { corrected = it; reviewed = false }
            }, label = { Text("직접 확인한 번역 (500자 이하)") }, enabled = !busy,
                isError = corrected.isNotEmpty() && !correctedReady,
                supportingText = { Text("${corrected.trim().length}/500자 · 민감정보를 넣지 마세요.") })
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(reviewed,
                enabled = !busy && correctedReady, role = Role.Checkbox, onValueChange = { if (!busy) reviewed = it }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(reviewed, onCheckedChange = null, enabled = !busy && correctedReady)
                Text("의미·주체·부정·숫자·용어 및 이 자료에 저장할 데이터 범위를 직접 확인했습니다.")
            }
        }
    }
    if (confirmRollback) ReviewedComparisonDialog(onDismissRequest = { if (!busy) confirmRollback = false },
        title = "직전 검수 자료로 되돌리기", confirmButton = { TextButton(enabled = !busy && rollback != null, onClick = {
            if (busy) return@TextButton
            val change = rollback ?: return@TextButton
            busy = true
            scope.launch {
                try { repository.rollbackLearning(change); message = "이전 검수 자료를 다시 활성 자료로 선택했습니다."; confirmRollback = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "되돌리기 실패" }
                finally { busy = false }
            }
        }) { Text("되돌리기") } }, dismissButton = { TextButton(enabled = !busy, onClick = { confirmRollback = false }) { Text("취소") } }) {
            Text("다음 발화부터 이전 자료를 사용합니다. 현재 처리 중인 발화는 시작할 때의 자료를 유지합니다.")
        }
}
