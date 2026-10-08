package app.guidecast.transmitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider
import app.guidecast.transmitter.DomainCorpusRepository.Companion.normalizeTargetLanguageTag

private const val DEFERRED_TEACHER_MODEL = "gemini-3.5-flash-lite"

private data class DeferredTeacherUiApproval(
    val apiRevision: Long,
    val relaySource: String,
    val relayTargets: List<String>,
    val textOptions: TranslationApiOptions,
    val targets: List<String>,
) {
    override fun toString() = "DeferredTeacherUiApproval(content=redacted)"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeferredNativeLearningControls(app: GuideCastApplication, enabledForChanges: Boolean) {
    val api by app.translationApiSettings.state.collectAsState()
    val relay by app.interpreterRelaySettings.state.collectAsState()
    val automatic by app.automaticTranslationExamples.state.collectAsState()
    val permit by app.translationApiSettings.deferredTeacher.collectAsState()
    val workflow by app.deferredNativeTeacher.state.collectAsState()
    val comparison by app.deferredTeacherComparisonStatus.collectAsState()
    val usage by app.deferredTeacherRequestUsage.collectAsState()
    var modelChosen by rememberSaveable { mutableStateOf(false) }
    var selectedTargets by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var approval by remember { mutableStateOf<DeferredTeacherUiApproval?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val availableTargets = remember(relay.targetLanguageTags) {
        relay.targetLanguageTags.map(::normalizeTargetLanguageTag).distinct()
            .filter { it != "ko" && GemmaTranslationProvider.supportsTranslation("ko", it) }
    }
    val targets = selectedTargets.filter { it in availableTargets }.take(5)
    val textOptions = deferredTeacherUiTextOptions(api)
    val koreanSource = normalizeTargetLanguageTag(relay.source) == "ko"
    val canBegin = enabledForChanges && permit == null && automatic.enabled && automatic.ready &&
        koreanSource && modelChosen && app.translationApiSettings.authorized(api) && api.allowLiveAudio &&
        validDeferredGoogleTeacherChoice(api, textOptions, targets)
    val activeWork = comparison.phase in setOf(DeferredTeacherPhase.WAITING_IDLE,
        DeferredTeacherPhase.REQUESTING, DeferredTeacherPhase.COMPARING)
    LaunchedEffect(api.revision, relay.source, relay.targetLanguageTags) {
        approval = null
        selectedTargets = ArrayList(selectedTargets.filter { it in availableTargets })
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("방송 후 원음으로 예문 비교", style = MaterialTheme.typography.titleMedium)
            Text(if (permit == null) "추가 문장 전송 동의 꺼짐 · 기본 꺼짐" else "이번 세션의 추가 문장 전송 동의 켜짐")
            Text("이 옵션은 Gemini Live 통역과 별도입니다. 동의 이후 다음 한국어 입력의 원음 최대 12초를 방송 종료 후 기기에서 전사합니다. 기기에서 확정한 원문만 별도 Gemini 문장 모델로 보냅니다. 이 추가 비교는 원음을 API로 다시 보내지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("추가 비교 모델 선택", style = MaterialTheme.typography.labelLarge)
            FilterChip(selected = modelChosen, enabled = enabledForChanges && permit == null,
                onClick = { modelChosen = !modelChosen; approval = null; message = null },
                label = { Text("Gemini 문장 · $DEFERRED_TEACHER_MODEL") })
            Text("예문 비교 언어 선택 · 현재 중계 언어 중 기기 내 비교가 가능한 언어, 최대 5개", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                availableTargets.forEach { target ->
                    val selected = target in targets
                    FilterChip(selected = selected,
                        enabled = enabledForChanges && permit == null && (selected || targets.size < 5),
                        onClick = {
                            selectedTargets = ArrayList(if (selected) targets - target else targets + target)
                            approval = null; message = null
                        }, label = { Text(deferredTeacherUiLanguageName(target, relay.targetLanguageTags)) })
                }
            }
            if (availableTargets.isEmpty()) Text("현재 중계 언어에서 기기 내 비교 가능한 대상이 없습니다. 중계 언어와 준비된 오프라인 모델을 확인하세요.")
            if (permit != null) {
                Text("동의한 모델 ${permit!!.textOptions.model} · ${permit!!.targets.joinToString { deferredTeacherUiLanguageName(it, relay.targetLanguageTags) }}", style = MaterialTheme.typography.bodySmall)
            }
            Text("세션 최대 3문장 요청 · 요청당 최대 5언어 · 출력 상한 1,024토큰. Live 비용에 추가되는 문장 API 비용이며, 앱의 예상 사용량·예약액은 실제 청구액이나 계정 비용 상한을 보증하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("설치된 한국어 기기 내 음성인식과 설치·실행 검증된 호환 Gemma 모델이 필요합니다. 방송 종료 후 여유가 있을 때 기존 모델만 한 번 준비하며, 새로 내려받지 않습니다. 다른 작업이 시작되거나 자료·설정이 바뀌면 준비·비교를 취소합니다.", style = MaterialTheme.typography.bodySmall)
            Text("검사 통과 예문 자동 사용을 먼저 켜세요. Live 원문·통역 출력의 대응쌍을 추정하지 않으며, 별도 문장 요청의 직접 응답으로 비교합니다. 자동 검사는 사람의 정확도 승인이나 모델 가중치 학습이 아닙니다.", style = MaterialTheme.typography.bodySmall)
            if (!koreanSource) Text("이 추가 비교는 한국어 발화만 지원합니다.")
            if (!automatic.enabled || !automatic.ready) Text("자동 예문 사용을 켜고 저장소 준비가 끝나야 추가 비교 동의를 켤 수 있습니다.")
            if (!enabledForChanges && permit == null) Text("방송·마이크·기기 내 작업을 마친 뒤 켜세요. 이후 시작하는 입력 구간부터 적용됩니다.")
            if (permit != null || activeWork) {
                TextButton(onClick = { approval = null; app.deferredNativeTeacher.stop(); message = null }) { Text("추가 예문 비교 중지") }
            } else Button(enabled = canBegin, onClick = {
                approval = DeferredTeacherUiApproval(api.revision, relay.source,
                    relay.targetLanguageTags.toList(), textOptions, targets.toList())
                message = null
            }) { Text("추가 문장 전송·비용 확인") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(workflow.message, style = MaterialTheme.typography.bodySmall)
            Text("기기에서 확정한 원문 ${workflow.sourceAccepted} · 제외 ${workflow.sourceRejected} · 비교 상태 ${deferredTeacherUiPhase(comparison.phase)}", style = MaterialTheme.typography.bodySmall)
            Text("추가 문장 요청 시도 ${usage.dispatchedRequests}/${usage.maximumRequests} · 요청 예약 시도 ${comparison.attemptedRequests} · 대기 ${comparison.queued} · 제외 ${comparison.dropped}", style = MaterialTheme.typography.bodySmall)
            Text("마지막 추가 요청 토큰 · 입력 ${deferredTeacherUiToken(usage.lastPrompt)} · 응답 ${deferredTeacherUiToken(usage.lastCandidates)} · 추론 ${deferredTeacherUiToken(usage.lastThoughts)} · 전체 ${deferredTeacherUiToken(usage.lastTotal)}", style = MaterialTheme.typography.bodySmall)
            if (usage.lastTotalsMatch == false) Text("제공자 토큰 합계가 맞지 않아 비용 확정에 사용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("앱 예산 기준 예상 사용액 USD ${usage.budgetKnownUsd ?: "미확인"} · 예약액 USD ${usage.budgetHeldUsd ?: "미확인"} (다른 문장 요청 포함 가능) · ${usage.message}", style = MaterialTheme.typography.bodySmall)
        }
    }
    approval?.let { captured ->
        AlertDialog(onDismissRequest = { approval = null }, title = { Text("추가 문장 전송과 비용에 동의할까요?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("모델 ${captured.textOptions.model}")
                Text("대상 ${captured.targets.joinToString { deferredTeacherUiLanguageName(it, captured.relayTargets) }}")
                Text("다음 한국어 입력 구간부터, 방송 종료 후 기기에서 확정한 최대 3개 원문을 추가 전송합니다. 원문 하나당 최대 5언어를 한 문장 요청으로 처리하며 요청당 출력 상한은 1,024토큰입니다.")
                Text("추가 문장 API 비용이 발생할 수 있습니다. 기존 Live 통역 동의만으로 이 추가 전송에 동의한 것으로 처리하지 않습니다. 현재 Live 모델·방송·마이크 설정은 바꾸지 않습니다.")
                Text("이 추가 비교는 원음을 API로 다시 보내지 않습니다. 방송 종료 후 설치·실행 검증된 기존 모델만 한 번 준비합니다. 모델 파일이나 한국어 기기 내 음성인식이 없거나 바쁘면 추가 요청 전에 비교를 보류합니다. 자동 검사 예문을 만드는 기능이며 정확도 보증·사람 검수·가중치 학습은 아닙니다.")
            } }, confirmButton = { TextButton(enabled = canBegin, onClick = {
                val currentApi = app.translationApiSettings.state.value
                val currentRelay = app.interpreterRelaySettings.state.value
                val unchanged = currentApi.revision == captured.apiRevision &&
                    currentRelay.source == captured.relaySource && currentRelay.targetLanguageTags == captured.relayTargets &&
                    modelChosen && targets == captured.targets && enabledForChanges &&
                    app.automaticTranslationExamples.state.value.let { it.enabled && it.ready }
                val started = unchanged && app.beginDeferredNativeTeacher(captured.textOptions, captured.targets, true)
                approval = null
                message = if (started) null else "추가 비교를 켜지 못했습니다. Live 동의·키·문장 모델의 가격 정보·기기 내 준비 상태를 확인하고 다시 선택하세요."
            }) { Text("추가 전송·비용에 동의하고 켜기") } },
            dismissButton = { TextButton(onClick = { approval = null }) { Text("취소") } })
    }
}

internal fun deferredTeacherUiTextOptions(api: TranslationApiOptions): TranslationApiOptions = api.copy(
    provider = TranslationApiProvider.GEMINI, model = DEFERRED_TEACHER_MODEL,
    baseUrl = "https://generativelanguage.googleapis.com/v1beta", allowOnline = false,
    allowLiveAudio = false, allowDomainReferences = false, localFallback = false, realtimeAudio = false)

private fun deferredTeacherUiLanguageName(target: String, relayTargets: List<String>): String {
    val originalTag = relayTargets.firstOrNull { normalizeTargetLanguageTag(it) == target }
    return NATIVE_RELAY_LANGUAGE_NAMES[target] ?: originalTag?.let { NATIVE_RELAY_LANGUAGE_NAMES[it] } ?: target
}

internal fun deferredTeacherUiToken(value: Long?): String = value?.toString() ?: "미확인"

private fun deferredTeacherUiPhase(phase: DeferredTeacherPhase): String = when (phase) {
    DeferredTeacherPhase.OFF -> "꺼짐"
    DeferredTeacherPhase.WAITING_IDLE -> "방송 종료·기기 내 준비 대기"
    DeferredTeacherPhase.REQUESTING -> "별도 문장 API 요청 중"
    DeferredTeacherPhase.COMPARING -> "기기 내 비교 중"
    DeferredTeacherPhase.READY_FOR_REVIEW -> "비교 완료 · 자동 검사 저장 대상"
    DeferredTeacherPhase.STOPPED -> "추가 비교 중지"
    DeferredTeacherPhase.REJECTED -> "조건 미충족 · 비교 제외"
}
