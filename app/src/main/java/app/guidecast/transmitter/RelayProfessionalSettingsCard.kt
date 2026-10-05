package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun RelayProfessionalSettingsCard(app: GuideCastApplication, broadcast: BroadcastSnapshot,
    busy: Boolean, domain: String, instructions: String, onDomainChange: (String) -> Unit,
    onInstructionsChange: (String) -> Unit, onSave: () -> Boolean, onDiscard: () -> Unit, onManage: () -> Unit) {
    val api by app.translationApiSettings.state.collectAsState()
    val relay by app.interpreterRelaySettings.state.collectAsState()
    val documentRevision by app.domainCorpus.referenceRevision.collectAsState()
    val corpusRevision by app.domainCorpus.revision.collectAsState()
    val supported = if (busy) broadcast.relayContext?.supportsInstructions == true
        else serviceExperience(api).supportsDomainInstructions
    val dirty = domain != api.domainPrompt || instructions != api.interpreterInstructions
    var references by remember { mutableStateOf(NativeReferenceSnapshot()) }
    var message by remember { mutableStateOf<String?>(null) }
    var referenceMessage by remember { mutableStateOf<String?>(null) }
    var loadingReferences by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf(false) }
    val transmitted = nativeReferenceTransmissionPreview(references, supported, api.allowDomainReferences)
    LaunchedEffect(documentRevision, corpusRevision, relay.source, relay.target, api.tone, busy, api.model) {
        if (busy) preview = false
        if (!busy) {
            references = NativeReferenceSnapshot()
            loadingReferences = true
            referenceMessage = null
            val refreshed = refreshNativeReferencePreview {
                app.domainCorpus.prepareNativeReferences(relay.source, relay.target, api.tone)
            }
            references = refreshed.references
            loadingReferences = false
            if (refreshed.failed) referenceMessage = "참고 자료를 읽지 못했습니다. 자료 관리에서 확인해 주세요. 이전 발췌는 사용하지 않습니다."
        }
    }
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("참고 자료 후보 미리보기") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("전체 보관 자료에서 최대 4개 발췌, 합계 600자만 준비합니다. 용어 메모·번역 예문·대본·참고 글에 분량을 나눕니다. 제외된 내용은 이 연결에 보내지 않습니다.")
            val excerpts = remember(references.payload) { runCatching { nativeReferencePreview(references.payload) }.getOrDefault(emptyList()) }
            if (loadingReferences) Text("참고 자료를 확인하고 있습니다.")
            else if (excerpts.isEmpty()) Text("준비된 발췌가 없습니다.")
            excerpts.forEach { entry ->
                Text("${referenceKindLabel(entry.kind)} · ${entry.title}", style = MaterialTheme.typography.titleMedium)
                Text(professionalReferencePreviewText(entry))
            }
            Text("합계 ${references.characters} / 600자 · 전송 형식에 필요한 글자도 포함합니다.")
            if (references.omittedTermLines > 0) Text("선택한 용어 메모 중 ${references.omittedTermLines}줄은 분량 제한으로 제외했습니다. 용어 한 줄을 중간에 자르지 않습니다.")
            referenceMessage?.let { Text(it) }
            Text(nativeReferencePreviewNotice(references, supported, api.allowDomainReferences))
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("닫기") } })
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("전문 분야·내 통역 지침", style = MaterialTheme.typography.titleLarge)
        Text("${if (busy) "연결 모델" else "선택 모델"} · ${if (busy) broadcast.relayContext?.model ?: "연결 준비 중" else api.model}")
        Text(if (supported) "이번 중계에 사용할 분야·지침과 참고 자료를 준비합니다. 오프라인 비교 학습은 별도 기능입니다."
            else "이 번역 전용 음성 모델은 텍스트 자료와 지침을 받지 않습니다. 자료는 보관할 수 있지만 현재 중계에 적용되지 않습니다.")
        if (!supported && api.provider == TranslationApiProvider.GEMINI_LIVE) {
            OutlinedButton(enabled = !busy, onClick = { app.translationApiSettings.selectModel(GEMINI_LIVE_AGENT) }, modifier = Modifier.fillMaxWidth()) {
                Text("전문 자료를 지원하는 Gemini 3.8 Live로 전환")
            }
        }
        if (!busy) {
            OutlinedTextField(domain, { if (it.length <= 300) { message = null; onDomainChange(it) } }, enabled = supported, label = { Text("전문 분야·상황") },
                placeholder = { Text("예: 반도체 장비 강의") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(instructions, { if (it.length <= MAX_INTERPRETER_INSTRUCTIONS) { message = null; onInstructionsChange(it) } }, enabled = supported,
                label = { Text("내 통역 지침 (선택)") }, placeholder = { Text("예: 처음 나온 약어는 원어를 함께 말해 주세요.") },
                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
            Text("${instructions.length} / $MAX_INTERPRETER_INSTRUCTIONS 자 · 원문의 뜻·숫자·긍정과 부정을 유지하도록 적용합니다.")
            if (dirty) Text("아직 저장하지 않은 입력이 있습니다. 저장한 뒤 다음 중계에 반영합니다.",
                modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            Button(enabled = supported && dirty, modifier = Modifier.fillMaxWidth(), onClick = {
                message = if (onSave()) "분야·지침을 저장했습니다. 다음 중계 연결에 전달합니다."
                    else "저장하지 못했습니다. 글자 수를 확인하고 API 키는 지침에 넣지 마세요."
            }) { Text("분야·지침 저장") }
            if (dirty) TextButton(onClick = onDiscard) { Text("입력 취소 · 저장한 값으로") }
        }
        message?.let { ServiceConnectionFeedback(it) }
        OutlinedButton(enabled = !busy, onClick = onManage, modifier = Modifier.fillMaxWidth()) { Text("강의 대본·참고 글·용어집 관리") }
        if (busy) {
            Text("이 연결의 참고 자료 · ${broadcast.relayReferenceEntries}개 발췌 / ${broadcast.relayAvailableReferenceEntries}개 후보 · ${broadcast.relayReferenceCharacters}자")
            Text(if (broadcast.relayReferenceCharacters > 0) "연결 준비에 포함한 분량입니다. 설정 변경은 중지 후 다음 연결에 반영합니다."
                else "이 연결에는 참고 자료를 포함하지 않았습니다.")
            Text("분야 · ${broadcast.relayContext?.domain?.ifBlank { "설정 안 함" } ?: "연결 준비 중"} · 사용자 지침 ${broadcast.relayContext?.instructionCharacters ?: 0}자")
        } else {
            Text(if (loadingReferences) "참고 자료를 확인하고 있습니다."
                else "보관·활성 자료 ${references.availableEntries}개 후보 · 준비된 발췌 ${references.includedEntries}개")
            Text("다음 연결에 포함할 수 있는 자료 ${transmitted.includedEntries}개 · ${transmitted.characters} / 600자")
            Text(nativeReferencePreviewNotice(references, supported, api.allowDomainReferences))
            if (references.omittedTermLines > 0) Text("선택한 용어 메모 중 ${references.omittedTermLines}줄은 분량 제한으로 제외했습니다.")
            ServiceExperienceToggle("짧은 참고 자료 전송 허용", "선택한 AI 서비스로 관련 발췌를 보냅니다. 대본 전체는 보내지 않습니다. 전송하지 않으면 자료는 기기에만 보관됩니다.",
                supported && api.allowDomainReferences, supported) { app.translationApiSettings.setAllowDomainReferences(it) }
            TextButton(onClick = { preview = true }, enabled = !loadingReferences && references.availableEntries > 0) { Text("준비된 후보 발췌 보기") }
        }
        if (!busy) referenceMessage?.let { ServiceConnectionFeedback(it) }
    } }
}

private fun professionalReferencePreviewText(entry: NativeReferenceEntry): String {
    if (entry.kind != "TRANSLATION_EXAMPLE") return entry.text
    return runCatching {
        val example = org.json.JSONObject(entry.text)
        "원문: ${example.getString("source")}\n번역: ${example.getString("translation")}"
    }.getOrDefault(entry.text)
}
