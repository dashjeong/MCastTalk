package app.guidecast.transmitter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Human-reviewed PoC examples only; provider output is never an automatic training target. */
@Composable
internal fun LearningComparisonReview(comparison: ShadowComparison?, repository: DomainCorpusRepository,
    commitAdmission: ((ShadowComparison) -> NativeComparisonCommitAdmission)? = null,
    isComparisonCurrent: (ShadowComparison) -> Boolean = { true }) {
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    var candidate by remember { mutableStateOf<ShadowComparison?>(null) }
    var corrected by remember { mutableStateOf("") }
    var reviewed by remember { mutableStateOf(false) }
    var rollback by remember { mutableStateOf<DomainLearningRevision?>(null) }
    var confirmRollback by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) { rollback = repository.latestLearningRevision() }
    LaunchedEffect(comparison) { if (!busy && candidate?.let { !isComparisonCurrent(it) } == true) candidate = null }
    TextButton(enabled = comparison != null && !busy, onClick = {
        candidate = comparison; corrected = ""; reviewed = false
    }) { Text("비교 예문 직접 검수 · 로컬 자료 개정") }
    TextButton(enabled = rollback != null && !busy, onClick = { confirmRollback = true }) { Text("직전 검수 자료로 되돌리기") }
    message?.let { Text(it) }
    candidate?.let { captured ->
        AlertDialog(onDismissRequest = { if (!busy) candidate = null }, title = { Text("예문 검수 · PoC") }, text = {
            Column {
                Text("원문: ${captured.original}\n온라인: ${captured.online}\n오프라인: ${captured.offline}")
                captured.offlineModel?.let { Text("비교 기준: $it") }
                Text("온라인 답변도 오답일 수 있습니다. 활성 자료를 복사해 새 버전을 만들며 다음 발화부터 적용합니다. 처리 중 발화와 이전 자료는 유지됩니다. 모델 가중치 학습이 아닙니다.")
                OutlinedTextField(corrected, { corrected = it.take(500) }, label = { Text("직접 확인한 번역 (500자 이하)") }, enabled = !busy)
                Row { Checkbox(reviewed, { reviewed = it }, enabled = !busy); Text("의미·주체·부정·숫자·용어 및 이 자료에 저장할 데이터 범위를 직접 확인했습니다.") }
            }
        }, confirmButton = { TextButton(enabled = reviewed && corrected.isNotBlank() && !busy && isComparisonCurrent(captured), onClick = {
            busy = true
            scope.launch {
                try {
                    check(isComparisonCurrent(captured)) { "중계·비교 설정이 바뀌었습니다. 새 예문을 확인하세요." }
                    val applied = repository.applyReviewedComparison(captured, corrected, reviewed, commitAdmission?.invoke(captured))
                    message = "검수 자료 버전 ${applied.revision} 적용됨 · 효용 검증은 별도입니다."
                    candidate = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "자료 개정 실패" }
                finally { busy = false }
            }
        }) { Text("검수 승인 · 기기에 저장") } }, dismissButton = {
            TextButton(enabled = !busy, onClick = { candidate = null }) { Text("취소") }
        })
    }
    if (confirmRollback) AlertDialog(onDismissRequest = { if (!busy) confirmRollback = false },
        title = { Text("직전 검수 자료로 되돌리기") }, text = { Text("다음 발화부터 이전 자료를 사용합니다. 현재 처리 중인 발화는 시작할 때의 자료를 유지합니다.") },
        confirmButton = { TextButton(enabled = !busy && rollback != null, onClick = {
            val change = rollback ?: return@TextButton
            busy = true
            scope.launch {
                try { repository.rollbackLearning(change); message = "이전 자료로 되돌렸습니다."; confirmRollback = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { message = error.message ?: "되돌리기 실패" }
                finally { busy = false }
            }
        }) { Text("되돌리기") } }, dismissButton = { TextButton(enabled = !busy, onClick = { confirmRollback = false }) { Text("취소") } })
}
