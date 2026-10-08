package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun onlineServiceChoice(current: TranslationApiOptions, google: Boolean): TranslationApiOptions =
    if ((google && current.provider == TranslationApiProvider.GEMINI_LIVE) ||
        (!google && current.provider == TranslationApiProvider.OPENAI_REALTIME && !current.realtimeAudio)) current
    else current.copy(provider = if (google) TranslationApiProvider.GEMINI_LIVE else TranslationApiProvider.OPENAI_REALTIME,
        model = if (google) {
            if (current.interpretationMode == OnlineInterpretationMode.CONTINUOUS) GEMINI_LIVE_TRANSLATE else GEMINI_LIVE_AGENT
        } else "gpt-realtime-2.1-mini",
        baseUrl = if (google) "https://generativelanguage.googleapis.com/v1beta" else "https://api.openai.com/v1",
        protocol = TranslationApiProtocol.RESPONSES, realtimeAudio = false)

internal fun openAiAudioChoice(current: TranslationApiOptions): TranslationApiOptions =
    if (current.provider == TranslationApiProvider.OPENAI_REALTIME && current.realtimeAudio) current
    else onlineServiceChoice(current, false).copy(realtimeAudio = true)

internal fun geminiSharedInputChoice(current: TranslationApiOptions): TranslationApiOptions =
    if (current.provider == TranslationApiProvider.GEMINI) current
    else current.copy(provider = TranslationApiProvider.GEMINI, model = "gemini-3.5-flash-lite",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta", protocol = TranslationApiProtocol.RESPONSES)

internal fun openAiTextChoice(current: TranslationApiOptions): TranslationApiOptions =
    if (current.provider == TranslationApiProvider.OPENAI) current
    else current.copy(provider = TranslationApiProvider.OPENAI, model = "gpt-5.4-mini",
        baseUrl = "https://api.openai.com/v1", protocol = TranslationApiProtocol.RESPONSES)

/** Everyday setup stays short. Diagnostics never stand between the operator and a usable key. */
@Composable
internal fun TranslationApiPanel(settings: TranslationApiSettings, service: TranslationApiService, enabled: Boolean,
    corpus: DomainCorpusRepository? = null, liveMonitor: GeminiLiveMonitor? = null, nativeOnly: Boolean = false,
    textOnly: Boolean = false,
    checkConnection: suspend (TranslationApiOptions) -> OnlineConnectionResult = { selected ->
        OnlineConnectionCheck().run(selected, settings.key(selected).orEmpty()) { settings.authorized(selected) }
    },
    openItem: String? = null,
    onOpenItemHandled: () -> Unit = {},
    contentOptionsEnabled: Boolean = true,
    automaticExampleControls: (@Composable () -> Unit)? = null,
) {
    require(!nativeOnly || !textOnly) { "Select either a native or a text translation route" }
    val contentEnabled = enabled && contentOptionsEnabled
    val options by settings.state.collectAsState()
    val usage by service.usage.collectAsState()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current
    var advanced by remember { mutableStateOf(false) }
    var servicePicker by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf(false) }
    var aiModelPicker by remember { mutableStateOf(false) }
    var keyDialog by remember { mutableStateOf(false) }
    var consentDialog by remember { mutableStateOf(false) }
    var consentForSetup by remember { mutableStateOf(false) }
    var keyDraft by remember(options.credentialScope) { mutableStateOf("") }
    var keepKey by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<String?>(null) }
    var domain by remember(options.domainPrompt) { mutableStateOf(options.domainPrompt) }
    var message by remember { mutableStateOf<String?>(null) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var checkRevision by remember { mutableStateOf<Long?>(null) }
    var checking by remember { mutableStateOf(false) }
    val nativeServiceMissing = nativeOnly && !options.usesNativeLiveAudio
    val online = options.provider != TranslationApiProvider.LOCAL && !nativeServiceMissing
    val google = options.provider in setOf(TranslationApiProvider.GEMINI, TranslationApiProvider.GEMINI_LIVE)
    val experience = serviceExperience(options)
    val serviceName = if (google) "Google Gemini" else if (options.provider == TranslationApiProvider.COMPATIBLE) "선택한 API 서비스" else "OpenAI"
    fun startCheck() {
        if (!enabled || checking) return
        val selected = settings.state.value
        if (textOnly && selected.usesNativeLiveAudio) {
            message = "스트리밍은 문장 번역 서비스를 선택하세요. 직접 음성 API는 통역 중계에서 사용합니다."
            return
        }
        if (!settings.authorized(selected)) { consentForSetup = false; consentDialog = true; return }
        checkJob?.cancel(); checkRevision = selected.revision; checking = true; message = null
        checkJob = scope.launch {
            try {
                val result = checkConnection(selected)
                if (settings.state.value.revision == selected.revision) message = result.message
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { message = onlineConnectionFailureResult(error).message }
            finally { checking = false }
        }
    }
    LaunchedEffect(options.revision) {
        if (checkRevision != null && checkRevision != options.revision) {
            checkJob?.cancel(); checking = false; checkRevision = null; message = null
        }
    }
    LaunchedEffect(openItem, enabled) {
        if (enabled && openItem != null) {
            if (nativeServiceMissing) {
                if (contentEnabled) servicePicker = true
            } else when (openItem) {
            "key" -> keyDialog = true
            "model" -> if (contentEnabled) aiModelPicker = true
            "service" -> if (contentEnabled) servicePicker = true
            "consent" -> { consentForSetup = true; consentDialog = true }
            }
            onOpenItemHandled()
        }
    }
    if (keyDialog) AlertDialog(
        onDismissRequest = { keyDialog = false; keyDraft = "" },
        title = { Text("$serviceName API 키") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(keyDraft, { keyDraft = it.take(512); keyError = null }, label = { Text("API 키") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            ServiceExperienceToggle("이 기기에 암호화하여 보관 (선택)", "끄면 이번 실행에서만 사용합니다.", keepKey, enabled) { keepKey = it }
            Text(if (keepKey) "다음 실행에도 사용할 수 있습니다. 키 삭제에서 언제든 지울 수 있습니다." else "이번 앱 실행에서만 사용합니다. 앱을 종료하거나 업데이트하면 다시 입력합니다.", style = MaterialTheme.typography.bodySmall)
            keyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = enabled && keyDraft.isNotBlank(), onClick = {
            val applied = if (keepKey) settings.saveKey(keyDraft.trim()) else settings.useSessionKey(keyDraft.trim())
            if (applied) { keyDraft = ""; keyDialog = false; message = "키 적용됨 · 연결을 확인해 주세요" }
            else keyError = "키 형식을 확인해 주세요. 기존 키는 유지됩니다."
        }) { Text("키 적용") } },
        dismissButton = { TextButton(onClick = { keyDraft = ""; keyDialog = false }) { Text("취소") } },
    )
    if (consentDialog) AlertDialog(
        onDismissRequest = { consentDialog = false }, title = { Text("온라인 통역 이용 동의") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (options.usesNativeLiveAudio)
                "통역을 위해 마이크 음성과 입력한 관련 내용을 $serviceName 서비스로 전송합니다."
                else "통역을 위해 인식된 문장과 필요한 문맥을 $serviceName 서비스로 전송합니다. 현재 이 연결의 음성 인식·재생은 기기에서 처리합니다.")
            Text("처리·보관·데이터 활용에는 해당 서비스의 정책과 계정 설정이 적용되며, 사용량에 따라 별도 API 요금이 발생할 수 있습니다.")
            Text("연결 확인은 서버에 접속하지만 음성·대본을 보내거나 번역을 생성하지 않습니다. 실제 통역을 시작할 때 전송하며, 동의는 언제든 철회할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri(if (google) "https://ai.google.dev/gemini-api/terms" else "https://platform.openai.com/docs/guides/your-data") }) { Text("서비스의 데이터 처리 안내") }
        } },
        confirmButton = { TextButton(enabled = enabled && options.hasKey, onClick = {
            settings.consentToSelectedService(); consentDialog = false
            if (consentForSetup) message = "동의 적용됨 · 운영 화면에서 시작해 주세요." else startCheck()
        }) { Text(if (consentForSetup) "동의 적용" else "동의하고 연결 확인") } },
        dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("동의하지 않음") } },
    )
    if (servicePicker) AlertDialog(onDismissRequest = { servicePicker = false },
        title = { Text("통역 서비스 선택") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { ServiceExperienceChoices("AI 서비스", translationApiServiceChoices(nativeOnly, textOnly),
            when (options.provider) { TranslationApiProvider.GEMINI -> "gemini-batch"; TranslationApiProvider.GEMINI_LIVE -> "gemini"; TranslationApiProvider.OPENAI_REALTIME -> if (options.realtimeAudio) "openai-audio" else "openai"; TranslationApiProvider.OPENAI -> "openai-text"; else -> "" }, contentEnabled) {
            val next = translationApiServiceChoice(options, it, nativeOnly, textOnly)
            if (next != options) settings.configure(next)
            servicePicker = false
        } } }, confirmButton = { TextButton(onClick = { servicePicker = false }) { Text("닫기") } })
    if (aiModelPicker) AlertDialog(onDismissRequest = { aiModelPicker = false },
        title = { Text("AI 모델 선택") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ServiceModelPicker(options, contentEnabled && !checking) { settings.selectModel(it.model); aiModelPicker = false }
            if (serviceModelChoices(options).none { it.id == options.model })
                Text("현재 사용자 지정 모델 · ${options.model}. 직접 입력은 고급 설정에서 변경할 수 있습니다.")
            Text(experience.processing)
            Text("사용량에 따라 API 요금이 발생합니다. 예상 비용은 안내용이며 실제 청구는 제공자 기준입니다.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri(if (google) "https://ai.google.dev/gemini-api/docs/pricing" else "https://openai.com/api/pricing/") }) { Text("공식 모델별 요금 안내") }
        } }, confirmButton = { TextButton(onClick = { aiModelPicker = false }) { Text("닫기") } })
    if (modelPicker) AlertDialog(onDismissRequest = { modelPicker = false },
        title = { Text("통역 방식 선택") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (options.provider == TranslationApiProvider.GEMINI_LIVE)
                ServiceModelPicker(options, contentEnabled) { settings.selectModel(it.model); modelPicker = false }
            else ServiceExperienceChoices("통역 방식", listOf(
                ExperienceChoice("continuous", "연속통역", "말하는 내용을 이어서 통역합니다."),
                ExperienceChoice("professional", "전문통역", "분야와 상황을 알려 용어 해석을 돕습니다.")),
                if (options.interpretationMode == OnlineInterpretationMode.CONTINUOUS) "continuous" else "professional", contentEnabled) {
                settings.configure(options.copy(interpretationMode = if (it == "continuous") OnlineInterpretationMode.CONTINUOUS else OnlineInterpretationMode.PROFESSIONAL)); modelPicker = false
            }
            ServiceExperienceSummary(options)
        } }, confirmButton = { TextButton(onClick = { modelPicker = false }) { Text("닫기") } })
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("통역 서비스", style = MaterialTheme.typography.titleLarge)
            if (!enabled) ServiceConnectionFeedback("설정을 변경하려면 진행 중인 입력·방송·통역 시험을 먼저 중지해 주세요.")
            if (textOnly && options.usesNativeLiveAudio) ServiceConnectionFeedback(
                "현재 Live 음성 서비스는 통역 중계에서 사용합니다. 스트리밍에 사용할 문장 번역 서비스를 직접 선택해 주세요.")
            if (!nativeOnly) ServiceExperienceChoices("사용 방식", listOf(
                ExperienceChoice("offline", "오프라인", "기기에 준비한 모델로 통역합니다."),
                ExperienceChoice("online", "온라인", "선택한 AI 서비스에 연결해 통역합니다.")),
                if (online) "online" else "offline", contentEnabled && !checking) {
                if (it == "offline") settings.configure(options.copy(provider = TranslationApiProvider.LOCAL))
                else if (textOnly) settings.restoreTextOnlineSelection() else settings.restoreOnlineSelection()
            }
            if (nativeServiceMissing) {
                Text("통역 중계에 사용할 음성 통역 서비스를 선택해 주세요.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(enabled = contentEnabled && !checking, onClick = { servicePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("AI 서비스 선택")
                }
            }
            if (!nativeServiceMissing) {
                val styleSupported = options.provider != TranslationApiProvider.GEMINI_LIVE || options.model == GEMINI_LIVE_AGENT
                ServiceExperienceChoices("통역 말투", listOf(
                    ExperienceChoice("CONVERSATIONAL", "구어체 (대화체)", "상황에 맞는 존댓말과 자연스럽게 들리는 문장으로 전달합니다."),
                    ExperienceChoice("FORMAL", "문어체", "명료하고 격식을 갖춘 문장으로 전달합니다.")),
                    if (styleSupported) options.tone.name else "", contentEnabled && !checking && styleSupported) {
                    settings.setTone(TranslationStyle.valueOf(it))
                }
                if (!styleSupported) {
                    Text("선택한 Live Translate는 말투 지시를 지원하지 않습니다. 말투를 지정하려면 일반 Live 음성 모델로 전환하세요.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = contentEnabled && !checking, onClick = {
                        settings.selectModel(GEMINI_LIVE_AGENT)
                    }) { Text("말투를 지원하는 Live로 전환") }
                } else if (options.tone == TranslationStyle.AUTO) {
                    Text("기존 문맥 자동 설정을 유지 중입니다. 위에서 말투를 직접 선택할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (!online) Text("말투 지시는 Gemma에서 적용됩니다. ML Kit 번역은 말투 지시를 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            }
            if (online) {
                OutlinedButton(enabled = contentEnabled && !checking, onClick = { servicePicker = true }, modifier = Modifier.fillMaxWidth()) { Text("서비스 · $serviceName") }
                OutlinedButton(enabled = contentEnabled && !checking, onClick = { aiModelPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("AI 모델 · ${options.model}") }
                OutlinedButton(enabled = contentEnabled && !checking, onClick = { modelPicker = true }, modifier = Modifier.fillMaxWidth()) { Text("통역 방식 · ${options.interpretationMode.label}") }
                Text(experience.processing, style = MaterialTheme.typography.bodySmall)
                if (options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL && !nativeOnly) {
                    OutlinedTextField(domain, { domain = it.take(300) }, label = { Text("분야·상황 (선택)") },
                        placeholder = { Text("예: 반도체 장비 세미나") }, enabled = contentEnabled && !checking, modifier = Modifier.fillMaxWidth())
                    if (domain != options.domainPrompt) TextButton(enabled = contentEnabled && !checking, onClick = {
                        message = if (settings.setDomainPrompt(domain)) "분야 설정 적용됨" else "분야 내용을 확인해 주세요"
                    }) { Text("분야 적용") }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = enabled && !checking, onClick = { keyDraft = ""; keyError = null; keepKey = false; keyDialog = true }) {
                        Text(if (options.hasKey) "API 키 변경" else "API 키 입력")
                    }
                    Button(enabled = enabled && options.hasKey && !checking, onClick = { startCheck() }) { Text(if (checking) "확인 중…" else "연결 확인") }
                }
                Text(if (!options.hasKey) "API 키를 입력해 주세요" else if (settings.usesTemporaryKey()) "키 적용됨 · 이번 실행만 사용" else "키 적용됨 · 기기에 암호화 보관", style = MaterialTheme.typography.bodySmall)
                if (checking) ServiceConnectionFeedback("연결을 확인하고 있습니다. 음성·대본은 보내지 않습니다.", true)
                else message?.let { ServiceConnectionFeedback(it) }
                if (checking) TextButton(onClick = { checkJob?.cancel(); checking = false; message = "연결 확인 취소됨" }) { Text("확인 취소") }
                experience.limitation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(if (options.usesNativeLiveAudio) "예상 비용 · 사용량 집계 후 표시" else if (usage.requests == 0L) "예상 비용 · 사용량 집계 후 표시"
                    else if (usage.estimatedUsd.signum() == 0 && usage.unconfirmedUsage + usage.unpricedUsage > 0) "예상 비용 · 미확인 (서비스 사용량에서 확인하세요)" else
                    "확인된 사용량 예상 USD ${usage.estimatedUsd.stripTrailingZeros().toPlainString()} · 미확인 ${usage.unconfirmedUsage + usage.unpricedUsage}회", style = MaterialTheme.typography.bodySmall)
                Text("예상 비용은 안내용이며 실제 청구는 서비스 제공자 기준입니다. 금액 미확인은 이용을 제한하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                if (options.provider == TranslationApiProvider.GEMINI && usage.sharedTargetCount != null) {
                    Text("최근 공유 번역 ${usage.sharedTargetCount}개 언어 · 입력 ${usage.reportedPromptTokens ?: "미확인"} · " +
                        "출력 ${usage.reportedCandidateTokens ?: "미확인"} · 생각 ${usage.reportedThoughtTokens ?: "미확인"} · " +
                        "총 ${usage.reportedTotalTokens ?: "미확인"} 토큰", style = MaterialTheme.typography.bodySmall)
                }
                if (options.provider == TranslationApiProvider.OPENAI && usage.sharedTargetCount != null) {
                    Text("최근 공유 번역 ${usage.sharedTargetCount}개 언어 · 입력 ${usage.reportedInputTokens ?: "미확인"} · " +
                        "출력 ${usage.reportedOutputTokens ?: "미확인"} · 총 ${usage.reportedTotalTokens ?: "미확인"} 토큰 · " +
                        "출력에 포함된 생각 ${usage.reportedReasoningTokens ?: "미확인"}", style = MaterialTheme.typography.bodySmall)
                }
                if (usage.sharedTargetCount != null && usage.reportedTotalsMatch == false)
                    Text("사용량 합계가 일치하지 않아 누적 사용량과 요금 추정에서 제외했습니다.", style = MaterialTheme.typography.bodySmall)
                if (options.allowOnline) TextButton(onClick = { checkJob?.cancel(); settings.revokeSelectedService(); message = "전송 동의 철회됨 · 진행 중인 요청을 중지합니다" }) { Text("전송 동의 철회") }
                if (options.hasKey) TextButton(enabled = enabled && !checking, onClick = { message = if (settings.clearKey()) "키 삭제됨" else "삭제를 확인하지 못했습니다. 전송은 중지했습니다." }) { Text("키 삭제") }
            }
            if (!nativeOnly && (!textOnly || corpus != null || automaticExampleControls != null))
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "상세 설정 닫기" else if (textOnly) "비교·자동 예문" else "모델·비교·사용량 상세")
                }
            if (advanced && !nativeOnly) {
                if (textOnly) TranslationComparisonSettings(settings, service, contentEnabled, corpus, automaticExampleControls)
                else AdvancedTranslationApiPanel(settings, service, contentEnabled, corpus, liveMonitor, automaticExampleControls)
            }
        }
    }
}
