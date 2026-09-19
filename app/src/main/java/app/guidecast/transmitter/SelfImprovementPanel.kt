package app.guidecast.transmitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.guidecast.core.translation.TranslationStyle

internal enum class ImprovementAction { AUTOMATIC_PREPARATION, LOCAL_FALLBACK, CONTEXT_REGISTER, DEVELOPMENT_REVIEW }
internal data class ImprovementProposal(val id: String, val category: String, val evidence: String,
    val proposal: String, val comparison: String, val verification: String, val action: ImprovementAction)

internal fun improvementProposals(operator: OperatorOptions, api: TranslationApiOptions,
    runtime: BroadcastSnapshot, learning: TeacherLearningProgress): List<ImprovementProposal> = buildList {
    if (!operator.automaticPreparation) add(ImprovementProposal("automatic-preparation", "사용 편의",
        "선택 언어 자동 준비가 꺼져 있습니다.", "선택 언어 자동 준비 활성화",
        "현재: 수동 준비 → 승인 후: 저장된 자료를 확인하고 누락된 자료만 준비",
        "다음 유휴 상태에 한 번 준비합니다. 인터넷·저장 공간이 필요하며 중지할 수 있습니다.", ImprovementAction.AUTOMATIC_PREPARATION))
    if (api.provider != TranslationApiProvider.LOCAL && !api.localFallback) add(ImprovementProposal("local-fallback", "서비스 연속성",
        "API 실패 시 기기 내 대체 번역이 꺼져 있습니다.", "준비된 기기 내 번역으로 복구 활성화",
        "현재: API 실패가 해당 번역 실패로 이어짐 → 승인 후: 준비된 로컬 번역을 시도",
        "기기에 모델이 있어야 합니다. 원음·다른 언어의 처리는 독립적으로 유지합니다.", ImprovementAction.LOCAL_FALLBACK))
    if (api.tone != TranslationStyle.AUTO) add(ImprovementProposal("context-register", "통번역 품질",
        "번역 문체가 ${api.tone.name}로 고정되어 있습니다.", "문맥에 맞는 문체로 전환",
        "현재: 고정 문체 → 승인 후: 대화·안내 문맥을 모델이 판단하도록 요청",
        "Gemma·API에서 적용됩니다. 같은 문장으로 의미·숫자·말투를 비교해야 합니다.", ImprovementAction.CONTEXT_REGISTER))
    val drops = runtime.translationChannels.sumOf { it.droppedUtterances } + runtime.recognitionDroppedFrameCount
    if (drops > 0) add(ImprovementProposal("latency-investigation", "성능·개발 방향", "현재 세션 대기 초과 ${drops}건",
        "기기 처리량과 언어별 지연을 분리한 개선 검토", "현재 표본을 기준으로 준비된 동일 언어·입력에서 지연과 누락을 비교",
        "승인 시 개발 검토 대상으로 기록합니다. 코드 변경·배포는 별도 회귀시험과 앱 업데이트가 필요합니다.", ImprovementAction.DEVELOPMENT_REVIEW))
    if (learning.rejected > 0) add(ImprovementProposal("teacher-boundary-review", "학습 품질·보안", "이번 실행의 스승 응답 미반영 ${learning.rejected}건",
        "보류 원인과 응답 검증 경계 검토", "전후 리포트에서 시간 초과·내용 검사·근거 부족을 구분",
        "미반영이 공격을 뜻하지는 않습니다. 검증 규칙을 자동으로 완화하지 않고 새 회귀 사례를 검토합니다.", ImprovementAction.DEVELOPMENT_REVIEW))
}

@Composable
internal fun SelfImprovementPanel(app: GuideCastApplication) {
    val lab by app.developerLabSettings.state.collectAsState()
    val operator by app.operatorSettings.state.collectAsState()
    val api by app.translationApiSettings.state.collectAsState()
    val runtime by app.broadcastRuntime.state.collectAsState()
    val learning by app.cloudTranslationReviewer.learningProgress.collectAsState()
    val preferences = remember { app.getSharedPreferences("improvement_decisions", android.content.Context.MODE_PRIVATE) }
    var revision by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("자가진단 발전수행 · 개선 계획", style = MaterialTheme.typography.titleMedium)
    Text("모드를 켜면 현재 상태에서 개선 대상을 자동으로 찾습니다. 사용자는 승인·보류를 결정합니다. 외부 AI가 실행할 명령이나 코드를 정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    if (!lab.teacherLearningEnabled) { Text("자가진단·자기개선 모드를 켜면 제안을 표시합니다."); return }
    val proposals = improvementProposals(operator, api, runtime, learning)
    if (proposals.isEmpty()) Text("현재 관측에서 새 설정·개발 제안은 없습니다. 선별된 문장 개선안은 전후 리포트에 표시됩니다.")
    proposals.forEach { proposal ->
        val decision = remember(revision, proposal.id) { preferences.getString(proposal.id, null) }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${proposal.category} · ${proposal.proposal}", style = MaterialTheme.typography.titleSmall)
                Text("근거: ${proposal.evidence}")
                Text(proposal.comparison)
                Text(proposal.verification, style = MaterialTheme.typography.bodySmall)
                decision?.let { Text(if (it == "held") "보류 중" else "개발 검토 승인됨 · 업데이트 필요") }
                OutlinedButton(enabled = !runtime.dataTransferUnavailable() && decision != "approved", onClick = {
                    when (proposal.action) {
                        ImprovementAction.AUTOMATIC_PREPARATION -> app.operatorSettings.setAutomaticPreparation(true)
                        ImprovementAction.LOCAL_FALLBACK -> app.translationApiSettings.setFallback(true)
                        ImprovementAction.CONTEXT_REGISTER -> app.translationApiSettings.setTone(TranslationStyle.AUTO)
                        ImprovementAction.DEVELOPMENT_REVIEW -> preferences.edit().putString(proposal.id, "approved").apply()
                    }
                    if (proposal.action != ImprovementAction.DEVELOPMENT_REVIEW) preferences.edit().remove(proposal.id).apply()
                    revision++
                    message = if (proposal.action == ImprovementAction.DEVELOPMENT_REVIEW) "개발 검토 승인을 기록했습니다. 현재 앱 코드를 변경하지 않았습니다."
                        else "승인한 설정을 적용했습니다. 다음 통번역 시험에서 결과를 비교하세요."
                }) { Text(if (proposal.action == ImprovementAction.DEVELOPMENT_REVIEW) "개발 검토 승인" else "승인·설정 적용") }
                TextButton(onClick = { preferences.edit().putString(proposal.id, "held").apply(); revision++ }) { Text("보류") }
            }
        }
    }
    message?.let { Text(it) }
}
