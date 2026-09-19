package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

internal enum class AssistantDestination { INPUT, LANGUAGES, TEST, LEARNING }
internal data class AssistantAdvice(val id: String, val title: String, val observation: String,
    val action: String, val expectedEffect: String, val destination: AssistantDestination)

/** Local, deterministic advice from bounded counters only. Never reads raw logs, keys or script text. */
internal fun operatorAdvice(snapshot: BroadcastSnapshot, models: TranslationModelUiState): List<AssistantAdvice> = buildList {
    if (snapshot.recognitionErrorMessage != null || snapshot.recognitionDroppedFrameCount > 0) add(AssistantAdvice(
        "recognition", "음성 인식 흐름을 확인하세요",
        "인식 오류 신호 ${if (snapshot.recognitionErrorMessage != null) "있음" else "없음"} · 인식 대기 초과 프레임 ${snapshot.recognitionDroppedFrameCount}개",
        "입력 화면에서 신호를 확인하고 ‘음성 인식 다시 연결’을 실행하세요.",
        "방송을 유지하면서 인식 연결을 복구합니다. 무음·권한 문제는 입력 장치 점검이 필요합니다.", AssistantDestination.INPUT))
    val degraded = snapshot.translationChannels.filter { it.translationState == BroadcastChannelWorkerState.DEGRADED || it.synthesisState == BroadcastChannelWorkerState.DEGRADED }
    if (degraded.isNotEmpty()) add(AssistantAdvice("channels", "일부 통역 언어를 복구해야 합니다",
        "현재 저하 상태 ${degraded.size}개 언어 · 번역 실패 ${degraded.sumOf { it.translationFailures }}회 · 음성 실패 ${degraded.sumOf { it.synthesisFailures }}회",
        "언어 설정에서 준비 상태를 확인하고 실패한 항목을 다시 준비하세요.",
        "실패한 언어를 복구하고 다른 언어·원음의 처리를 유지합니다.", AssistantDestination.LANGUAGES))
    if (snapshot.translationChannels.any { it.droppedUtterances > 0 } || snapshot.listenerDroppedFrames > 0) add(AssistantAdvice(
        "backpressure", "처리 속도와 청취 연결을 점검하세요",
        "통역 대기 초과 ${snapshot.translationChannels.sumOf { it.droppedUtterances }}문장 · 청취 대기 초과 ${snapshot.listenerDroppedFrames}프레임",
        "시험에서 사용할 언어만 선택한 상태와 현재 상태를 비교하세요. 청취 연결 상태도 확인하세요.",
        "기기 처리량과 네트워크 병목을 구분할 수 있습니다. 언어 수는 운영자가 결정합니다.", AssistantDestination.TEST))
    val firstAudio = snapshot.transcripts.takeLast(100).filter { it.isFinal }.flatMap { line -> line.firstAudioLatencyMillis.values.filter { it >= 0 } }.sorted()
    if (firstAudio.isNotEmpty()) {
        val p95 = firstAudio[(ceil(firstAudio.size * .95).toInt() - 1).coerceAtLeast(0)]
        add(AssistantAdvice("latency", if (p95 > 2_000) "통역 응답 속도를 비교해 보세요" else "최근 첫 음성 지연을 기록했습니다",
            "현재 메모리의 최대 100문장 · 언어 혼합 ${firstAudio.size}표본 · 첫 음성 p95 ${p95}ms",
            "같은 입력·언어·준비 상태로 통번역 시험을 반복해 비교하세요.",
            "현재 표본의 변화 추적에 사용합니다. Galaxy S23 성능 인증이나 8시간 안정성 결과는 아닙니다.", AssistantDestination.TEST))
    }
    val missing = models.selectedLanguageTags.count { !models.ttsReady(it) }
    if (!models.speechRecognitionReady || missing > 0) add(AssistantAdvice("preparation", "선택한 언어의 준비 상태를 확인하세요",
        "입력 인식 ${if (models.speechRecognitionReady) "준비됨" else "준비 확인 필요"} · 음성 준비 확인 ${missing}개 언어",
        "언어 설정에서 저장된 자료 확인과 필요한 항목 준비를 실행하세요.",
        "다운로드와 실행 시 로딩을 구분해 첫 사용 실패를 줄입니다.", AssistantDestination.LANGUAGES))
    add(AssistantAdvice("quality", "번역 품질은 선별한 문장으로 개선하세요",
        "문장 의미와 자연스러움은 오류 횟수·지연만으로 판정할 수 없습니다.",
        "실험실에서 개선할 문장만 스승에게 검토시키고 학습 전후 리포트를 비교하세요.",
        "핵심 보정만 축적합니다. 일반 사용에도 적용하려면 문장 사전에서 내용을 확인하세요.", AssistantDestination.LEARNING))
}.take(6)

@Composable
internal fun OperatorAssistantScreen(snapshot: BroadcastSnapshot, models: TranslationModelUiState,
    onNavigate: (AssistantDestination) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val advice = operatorAdvice(snapshot, models)
    Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text("돌아가기") }
                AstraAvatar()
                Text("아스트라 미니미", style = MaterialTheme.typography.headlineSmall)
                Text("MCastTalk 비서 · 언어 준비부터 복구와 품질 개선까지", style = MaterialTheme.typography.titleMedium)
                Text("현재 상태를 기기에서 진단하고, 근거와 실행 순서를 제안합니다. 원문·로그·키를 외부 AI에 전송하지 않습니다.")
                Text("제안은 실행 버튼으로 직접 선택하세요. 방송·입력 제어와 개발 방향의 최종 결정은 사용자에게 있습니다.", style = MaterialTheme.typography.bodySmall)
            }
            items(advice, key = { it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text("관측: ${item.observation}")
                        Text("제안: ${item.action}")
                        Text("기대 효과·확인 범위: ${item.expectedEffect}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { onNavigate(item.destination) }) { Text(when (item.destination) {
                            AssistantDestination.INPUT -> "입력 화면으로"
                            AssistantDestination.LANGUAGES -> "언어 설정으로"
                            AssistantDestination.TEST -> "시험 화면으로"
                            AssistantDestination.LEARNING -> "학습 기능 확인"
                        }) }
                    }
                }
            }
            item { Text("개선 순서: 입력 연결 → 언어별 복구 → 지연 비교 → 품질 학습. 실제 기기·소음·언어별 품질 확인이 필요한 제안은 자동 개발이나 품질 인증으로 처리하지 않습니다.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
