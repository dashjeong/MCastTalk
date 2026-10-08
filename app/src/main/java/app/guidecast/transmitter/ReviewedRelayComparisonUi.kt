package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewedRelayComparisonUi(app: GuideCastApplication, broadcast: BroadcastSnapshot,
    onOpenExampleMaterials: () -> Unit) {
    val controller = app.reviewedRelayComparison
    val state by controller.state.collectAsState()
    val api by app.translationApiSettings.state.collectAsState()
    val relay by app.interpreterRelaySettings.state.collectAsState()
    val corpusRevision by app.domainCorpus.revision.collectAsState()
    val choices = remember(broadcast.transcripts) { reviewedRelayCaptionChoices(broadcast.transcripts) }
    var dialogOpen by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(relay.target) }
    var draft by remember { mutableStateOf(ReviewedRelayComparisonDraft()) }
    var draftMessage by remember { mutableStateOf<String?>(null) }
    val inputEpoch = app.broadcastRuntime.inputRequestEpoch
    val controlGeneration = app.interpreterRelaySettings.comparisonGeneration
    val comparing = state.phase == ReviewedRelayComparisonPhase.COMPARING
    val waiting = state.phase == ReviewedRelayComparisonPhase.WAITING_FOR_BROADCAST_END
    val idle = broadcast.phase == BroadcastPhase.IDLE && broadcast.inputPhase == InputPhase.IDLE && !broadcast.inputStopping
    fun currentApproval() = controller.captureApproval(draft.choice?.source ?: relay.source, draft.choice?.target ?: language)
    val approvalNow = currentApproval()

    LaunchedEffect(api.revision, corpusRevision, inputEpoch, controlGeneration,
        approvalNow.modelId, approvalNow.preparationGeneration) {
        if (dialogOpen && draft.bothReadAndSameMeaning) {
            draft = draft.invalidateConfirmation()
            draftMessage = "방송·설정·자료가 바뀌었습니다. 원문과 번역을 다시 확인하세요."
        }
    }
    LaunchedEffect(choices) {
        val selected = draft.choice
        if (dialogOpen && selected != null && choices.firstOrNull { it.selectionKey == selected.selectionKey } != selected) {
            draft = draft.invalidateConfirmation()
            draftMessage = "선택한 자막이 바뀌었습니다. 아래 원문과 번역을 다시 읽고 확인하세요."
        }
    }

    OutlinedButton(enabled = choices.isNotEmpty() && !comparing && !waiting, onClick = {
        draft = ReviewedRelayComparisonDraft()
        language = relay.target.takeIf { selected -> choices.any { it.target == selected } } ?: choices.first().target
        draftMessage = null
        dialogOpen = true
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("직접 확인한 예문 비교") }
    Text("직접 확인한 예문을 준비된 오프라인 모델과 비교합니다. 추가 API 요청·자동 모델 준비 없이 검수·저장한 예문만 재사용하며, 모델 가중치를 학습하지 않습니다.",
        style = MaterialTheme.typography.bodySmall)
    Text("모델 준비: 화면 아래 ‘설정’ → ‘Gemma 고급 번역’. 예문 저장 자료: ‘전문 통역 자료’ → ‘전문 용어·번역 예문 관리’에서 언어 쌍을 준비하세요.",
        style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = !comparing, onClick = onOpenExampleMaterials) { Text("전문 통역 자료 열기") }
    if (choices.isEmpty()) Text("원문과 번역이 함께 나온 최근 중계 자막이 있어야 예문을 고를 수 있습니다.",
        style = MaterialTheme.typography.bodySmall)
    if (state.phase != ReviewedRelayComparisonPhase.IDLE)
        Text(state.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    state.pending?.let { request ->
        val availability = controller.availability(request.source, request.target)
        if (waiting) {
            Text("확인한 예문 1개 · ${relayCaptionLanguageLabel(request.target)}")
            Text(if (idle) availability.message else "방송과 마이크를 종료한 뒤 아래 버튼으로 비교를 시작하세요.")
            Button(enabled = idle && availability.available, onClick = {
                if (!controller.compareWaiting()) draftMessage = "지금은 비교할 수 없습니다. 방송·모델 상태를 확인하세요."
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("오프라인 비교 시작") }
        }
        if (waiting || comparing) TextButton(onClick = { controller.cancel() }) { Text("예문 비교 취소") }
    }
    if (!dialogOpen) draftMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    LearningComparisonReview(state.comparison, app.domainCorpus,
        commitAdmission = controller::commitAdmission, isComparisonCurrent = controller::isCurrent,
        referenceLabel = "직접 확인한 번역", entryLabel = "비교 결과 검수·저장",
        initialReferenceCorrection = true,
        correctedTranslationAllowed = { reviewedRelayTranslationTextValid(it, state.comparison?.target.orEmpty()) },
        showEntry = state.comparison != null, showRollback = false)

    if (dialogOpen) {
        val selected = draft.choice
        val availability = controller.availability(selected?.source ?: relay.source, selected?.target ?: language)
        ReviewedComparisonDialog(title = "원문과 번역 확인", onDismissRequest = {
            dialogOpen = false; draft = ReviewedRelayComparisonDraft(); draftMessage = null
        }, confirmButton = {
            Button(enabled = draft.approvedFor(approvalNow) && availability.available && !comparing && !waiting, onClick = {
                val captured = draft
                val choice = captured.choice ?: return@Button
                if (!captured.approvedFor(controller.captureApproval(choice.source, choice.target))) {
                    draft = draft.invalidateConfirmation()
                    draftMessage = "모델·설정·자료가 바뀌었습니다. 원문과 번역을 다시 확인하세요."
                    return@Button
                }
                if (controller.submit(captured.original, captured.translation, choice.source, choice.target,
                        api.tone, captured.bothReadAndSameMeaning, captured.approvalEnvironment)) {
                    dialogOpen = false
                    draft = ReviewedRelayComparisonDraft()
                    draftMessage = null
                } else {
                    draft = draft.invalidateConfirmation()
                    draftMessage = "비교할 수 없습니다. 원문과 번역, 모델·자료 상태를 다시 확인하세요."
                }
            }) { Text(if (idle) "오프라인 비교" else "확인한 예문 보관") }
        }, dismissButton = { TextButton(onClick = {
            dialogOpen = false; draft = ReviewedRelayComparisonDraft(); draftMessage = null
        }) { Text("취소") } }) {
            Text("음성 중계 자막은 원문과 번역의 대응이 자동 확인되지 않습니다. 뜻이 다르거나 빠진 말은 두 칸에서 직접 고쳐 주세요.")
            Text("통역 언어", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.map { it.target }.distinct().forEach { tag ->
                    FilterChip(selected = language == tag, onClick = {
                        if (language != tag) { language = tag; draft = ReviewedRelayComparisonDraft(); draftMessage = null }
                    }, label = { Text(relayCaptionLanguageLabel(tag)) })
                }
            }
            Text("최근 자막에서 예문 고르기", style = MaterialTheme.typography.titleSmall)
            choices.filter { it.target == language }.forEachIndexed { index, choice ->
                Row(Modifier.fillMaxWidth().selectable(draft.choice?.selectionKey == choice.selectionKey,
                    onClick = { draft = draft.select(choice); draftMessage = null }, role = Role.RadioButton)
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.choice?.selectionKey == choice.selectionKey, onClick = null)
                    Text("자막 ${index + 1} · ${choice.original.take(80)}", modifier = Modifier.weight(1f))
                }
            }
            selected?.let { choice ->
                Text("${NATIVE_RELAY_SOURCE_LANGUAGE_OPTIONS.firstOrNull { it.languageTag == choice.source }?.label?.substringBefore(" · ") ?: choice.source} → ${relayCaptionLanguageLabel(choice.target)}")
                OutlinedTextField(draft.original, { draft = draft.editOriginal(it); draftMessage = null },
                    label = { Text("원문 · 직접 수정·확인") }, modifier = Modifier.fillMaxWidth(),
                    isError = draft.original.isNotEmpty() && !reviewedRelayExampleTextValid(draft.original),
                    supportingText = { Text("${draft.original.trim().length}/500자 · 줄바꿈 없는 문장, API 키·민감정보 제외") })
                OutlinedTextField(draft.translation, { draft = draft.editTranslation(it); draftMessage = null },
                    label = { Text("번역 · 직접 수정·확인") }, modifier = Modifier.fillMaxWidth(),
                    isError = draft.translation.isNotEmpty() && !reviewedRelayTranslationTextValid(draft.translation, choice.target),
                    supportingText = { Text("${draft.translation.trim().length}/500자 · 선택한 언어의 한 문장으로 입력하세요.") })
                Row(Modifier.fillMaxWidth().toggleable(draft.bothReadAndSameMeaning,
                    enabled = draft.validTexts, role = Role.Checkbox,
                    onValueChange = { draft = draft.confirmBoth(it, controller.captureApproval(choice.source, choice.target)) })
                    .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(draft.bothReadAndSameMeaning, onCheckedChange = null, enabled = draft.validTexts)
                    Text("원문과 번역을 모두 읽었고, 같은 내용을 뜻하는지 확인했습니다.", modifier = Modifier.weight(1f))
                }
            }
            Text(availability.message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            draftMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("비교가 끝나도 자동 저장되지 않습니다. 결과를 다시 확인해 저장할지 결정하세요.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Fixed title/actions and a scrollable body keep review controls reachable above the keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewedComparisonDialog(title: String, onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
                Column(Modifier.semantics { paneTitle = title }) {
                    Text(title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) { dismissButton(); confirmButton() }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        content()
                    }
                }
            }
        }
    }
}
