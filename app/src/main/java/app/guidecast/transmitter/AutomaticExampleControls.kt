package app.guidecast.transmitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AutomaticExampleControls(repository: AutomaticTranslationExamples, enabledForChanges: Boolean) {
    val status by repository.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canDelete = enabledForChanges && status.ready
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("자동 검사 예문", style = MaterialTheme.typography.titleMedium)
            ServiceExperienceToggle("검사 통과 예문 자동 사용", 
                "기본 꺼짐. 원문·번역·직전 문맥을 기기에 보관하며, 사람이 검수한 전문 자료와 분리합니다.",
                status.enabled, status.ready && (enabledForChanges || status.enabled)) { enabled ->
                message = if (repository.setEnabled(enabled)) null else "자동 예문 사용 설정을 저장하지 못했습니다. 다시 확인하세요."
            }
            Text("켜기만 해서는 API 요청이 발생하지 않습니다. 통번역 스트리밍의 상시 비교 또는 이번 실행의 비교를 따로 켜야 새 예문을 수집합니다. OFFLINE의 온라인 보조 비교는 추가 전송·비용에 대한 별도 동의가 필요합니다.", style = MaterialTheme.typography.bodySmall)
            Text("방송이 끝나면 예문을 검사·저장합니다. 같은 원문·직전 문맥·언어·말투·전문 자료 조건에서만 기기 내 번역에 재사용하며, 재사용에는 API 요청이 없습니다. 다른 문장까지 품질이 좋아진다는 보장은 없습니다.", style = MaterialTheme.typography.bodySmall)
            Text("자동 검사는 사람이 확인한 정확도 승인이 아닙니다. 번역이 틀릴 수 있으며 모델 가중치를 학습하지 않습니다. 직접 검수한 전문 자료를 우선 사용합니다.", style = MaterialTheme.typography.bodySmall)
            Text("개인정보·비밀은 예문에 넣지 마세요. 자동 검사가 모든 민감정보를 찾아내지는 못합니다.", style = MaterialTheme.typography.bodySmall)
            Text(if (!status.ready) "저장 예문 불러오는 중" else "현재 조회 가능한 예문 ${status.stored}/${AutomaticTranslationExamples.MAX_EXAMPLES}개 · 최근 예문을 보관합니다.")
            Text("앱 실행 중 · 검사 통과 저장 ${status.accepted} · 재사용 ${status.reused} · 거절·조건 변경 ${status.rejected} · 저장 대기 등록 ${status.deferred} (누적)", style = MaterialTheme.typography.bodySmall)
            Text(if (status.enabled) "자동 예문 사용 켜짐" else "자동 예문 사용 꺼짐 · 보관된 예문도 조회·재사용하지 않습니다.")
            if (!enabledForChanges) Text("새로 켜거나 삭제하려면 방송·마이크·기기 내 작업을 마쳐 주세요. 사용 중에도 이 옵션을 끌 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(enabled = canDelete, onClick = { confirmClear = true }) { Text("자동 예문 모두 삭제") }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("자동 예문을 모두 삭제할까요?") },
        text = { Text("자동 검사로 보관한 예문만 삭제합니다. 직접 검수한 전문 자료와 방송 기록은 유지합니다. 사용 옵션이 켜져 있으면 이후 비교 결과가 다시 저장될 수 있습니다.") },
        confirmButton = { TextButton(enabled = canDelete, onClick = {
            if (!canDelete || !repository.state.value.ready) return@TextButton
            repository.clear(); confirmClear = false
        }) { Text("모두 삭제") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("취소") } })
}
