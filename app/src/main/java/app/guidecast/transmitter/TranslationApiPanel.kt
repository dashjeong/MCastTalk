package app.guidecast.transmitter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.guidecast.core.translation.TranslationStyle

@Composable
internal fun AdvancedTranslationApiPanel(settings: TranslationApiSettings, service: TranslationApiService, enabled: Boolean, corpus: DomainCorpusRepository? = null, liveMonitor: GeminiLiveMonitor? = null) {
    val options by settings.state.collectAsState()
    val states by service.states.collectAsState()
    val usage by service.usage.collectAsState()
    val shadow by service.shadow.collectAsState()
    val sessionLearning by settings.sessionLearning.collectAsState()
    var learningConsent by remember { mutableStateOf(false) }
    var consentReferences by remember { mutableStateOf(false) }
    var domain by remember(options.domainPrompt) { mutableStateOf(options.domainPrompt) }
    var model by remember(options.model) { mutableStateOf(options.model) }
    var base by remember(options.baseUrl) { mutableStateOf(options.baseUrl) }
    var key by remember(options.credentialScope) { mutableStateOf("") }
    var persistKey by remember(options.credentialScope) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    if (learningConsent) {
        val learningProvider = if (options.provider == TranslationApiProvider.LOCAL) settings.preparedLearningProvider() else options
        AlertDialog(onDismissRequest = { learningConsent = false }, title = { Text("이번 실행의 학습 비교 동의") },
            text = { Column {
                Text("주 방송은 현재 선택을 유지합니다. 비교 대상: ${learningProvider?.provider?.label ?: "미설정"} / ${learningProvider?.model ?: "미설정"}. 원문과 최대 1,000자의 직전 문맥을 이 API로 전송하며 별도 요금이 발생합니다. 원음 파일은 보내지 않습니다.")
                Row { Checkbox(consentReferences, { consentReferences = it }); Text("동일 자료 비교를 위해 검색된 관련 근거 최대 600자 전송도 허용") }
            } }, confirmButton = { TextButton(onClick = {
                message = if (settings.beginSessionLearning(true, consentReferences)) "이번 실행의 학습 비교를 켰습니다."
                    else "ONLINE 설정에서 비교 제공자와 키를 먼저 저장하세요. OFFLINE으로 돌아온 뒤 학습을 켤 수 있습니다."
                learningConsent = false
            }) { Text("전송·비용에 동의하고 학습 켜기") } },
            dismissButton = { TextButton(onClick = { learningConsent = false }) { Text("취소") } })
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("통번역 운용 모드 · 문체", style = MaterialTheme.typography.titleLarge)
            val learningCapability = serviceExperience(options)
            val learningSupported = learningCapability.supportsLearningComparison
            ServiceExperienceToggle("학습 비교 (상시 설정 포함)",
                if (learningSupported) "별도 동의 후 보조 엔진과 비교합니다. 주 방송 경로는 유지됩니다."
                else if (learningCapability.supportsNativePairComparison) "직접 음성 비교는 On-통 Live(AI 통역)의 ‘오프라인 결과와 비교’에서 켜세요. 상시 문장 비교 설정은 이 경로에 적용되지 않습니다."
                else "이 Live 음성 경로에서는 학습 비교를 지원하지 않습니다. 저장된 상시 학습 설정은 적용되지 않습니다.",
                learningSupported && (sessionLearning || (options.provider != TranslationApiProvider.LOCAL && options.alwaysLearnOnline)),
                enabled && learningSupported) { on ->
                if (!on) { settings.endSessionLearning(); if (options.provider != TranslationApiProvider.LOCAL) settings.setAlwaysLearnOnline(false) }
                else { consentReferences = false; learningConsent = true }
            }
            Text("OFFLINE은 로컬 주 방송, ONLINE은 선택 API 주 방송입니다. 이번 학습에 따로 동의한 경우에만 반대쪽 엔진을 보조 비교합니다.", style = MaterialTheme.typography.bodySmall)
            shadow.lastPause?.let { Text("학습 일시 중지: $it", style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationApiProvider.entries.forEach { provider ->
                    FilterChip(selected = options.provider == provider, enabled = enabled, onClick = {
                        val next = when (provider) {
                            TranslationApiProvider.OPENAI_REALTIME -> options.copy(provider = provider, model = "gpt-realtime-2.1-mini", baseUrl = "https://api.openai.com/v1", protocol = TranslationApiProtocol.RESPONSES, realtimeAudio = false)
                            TranslationApiProvider.GEMINI_LIVE -> options.copy(provider = provider, model = if (options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL) GEMINI_LIVE_AGENT else GEMINI_LIVE_TRANSLATE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
                            TranslationApiProvider.GEMINI -> options.copy(provider = provider, model = "gemini-3.5-flash-lite", baseUrl = "https://generativelanguage.googleapis.com/v1beta")
                            TranslationApiProvider.COMPATIBLE -> options.copy(provider = provider, model = "your-model", baseUrl = "https://your-provider.example/v1", protocol = TranslationApiProtocol.CHAT_COMPLETIONS)
                            else -> options.copy(provider = provider, model = "gpt-5.4-mini", baseUrl = "https://api.openai.com/v1", protocol = TranslationApiProtocol.RESPONSES)
                        }
                        settings.configure(next); message = null
                    }, label = { Text(if (provider == TranslationApiProvider.LOCAL) "OFFLINE · 기기 내" else "ONLINE · ${provider.label}") })
                }
            }
            if (options.provider != TranslationApiProvider.LOCAL) {
                Text("온라인 통역 방식", style = MaterialTheme.typography.titleMedium)
                Row {
                    OnlineInterpretationMode.entries.forEach { mode ->
                        FilterChip(selected = options.interpretationMode == mode, enabled = enabled, onClick = {
                            settings.configure(options.copy(interpretationMode = mode,
                                model = if (mode == OnlineInterpretationMode.PROFESSIONAL && options.provider == TranslationApiProvider.GEMINI_LIVE && options.model == GEMINI_LIVE_TRANSLATE) GEMINI_LIVE_AGENT else options.model))
                        }, label = { Text(mode.label) })
                    }
                }
                Text("연속통역은 원문을 충실하게 이어서 방송합니다. 전문통역은 분야·상황 지시를 추가합니다. 두 방식 모두 음성 직접 처리 모델을 사용할 수 있으며, 전문통역에 RAG가 필수인 것은 아닙니다.")
                if (options.interpretationMode == OnlineInterpretationMode.PROFESSIONAL) {
                    OutlinedTextField(domain, { domain = it.take(300) }, enabled = enabled,
                        label = { Text("분야·상황 지시 (예: 반도체 장비 기술 세미나의 전문 통역)") }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = enabled, onClick = {
                        message = if (settings.setDomainPrompt(domain)) "전문통역 지시 저장됨" else "300자 이내의 분야 지시를 확인하세요. 키·민감정보는 넣지 마세요."
                    }) { Text("분야 지시 저장") }
                    Text("이 지시는 선택 API로 전송됩니다. 분야 지정은 정확도 보증이나 모델 학습이 아닙니다. On-통 Live(AI 통역)의 전문 자료·지침에서 지원 모델과 전달할 발췌를 확인하세요.")
                }
            }
            if (options.provider != TranslationApiProvider.GEMINI_LIVE) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationStyle.entries.forEach { style ->
                    FilterChip(selected = options.tone == style, enabled = enabled, onClick = { settings.setTone(style) },
                        label = { Text(when (style) { TranslationStyle.AUTO -> "문맥에 맞게"; TranslationStyle.FORMAL -> "공식·문어체"; TranslationStyle.CONVERSATIONAL -> "대화·구어체" }) })
                }
            }
            Text("문체 지시는 Gemma·API 경로에서 적용합니다. ML Kit 기본 번역은 문체 지시를 지원하지 않습니다. 의역하더라도 숫자·이름·부정·조건을 유지하도록 요청합니다.", style = MaterialTheme.typography.bodySmall)
            if (options.provider != TranslationApiProvider.LOCAL) {
                if (options.provider == TranslationApiProvider.GEMINI_LIVE) {
                    Row {
                        listOf(GEMINI_LIVE_AGENT to "Live · 일반 음성", GEMINI_LIVE_TRANSLATE to "Live Translate · 통역 전용").forEach { (id, label) ->
                            FilterChip(selected = options.model == id, enabled = enabled, onClick = {
                                settings.configure(options.copy(model = id, interpretationMode = if (id == GEMINI_LIVE_TRANSLATE) OnlineInterpretationMode.CONTINUOUS else options.interpretationMode)); model = id
                            }, label = { Text(label) })
                        }
                    }
                    Text("모델: ${options.model}")
                    liveMonitor?.let { monitor ->
                        val live by monitor.state.collectAsState()
                        Text("${live.state} · 연결 ${live.connections} · 최종 사용량 미확인 ${live.unknownSessions}건")
                        live.lossesByLanguage.forEach { (language, reasons) ->
                            reasons.forEach { (reason, total) ->
                                val label = when (reason) {
                                    LiveAudioLoss.BEFORE_READY -> "연결 준비 전 미전송"
                                    LiveAudioLoss.INPUT_OVERFLOW -> "입력 대기열 초과"
                                    LiveAudioLoss.INPUT_ABANDONED -> "중지 후 미전송·대기열 폐기"
                                    LiveAudioLoss.OUTPUT_BLOCKED -> "출력 송출 차단"
                                    LiveAudioLoss.LISTENER_OVERFLOW -> "청취자 대기열 누락 (청취자별 합계)"
                                }
                                val bytesPerMs = if (reason in setOf(LiveAudioLoss.OUTPUT_BLOCKED, LiveAudioLoss.LISTENER_OVERFLOW)) 48 else 32
                                Text("$language · $label: ${total.frames} 프레임 / ${total.bytes / bytesPerMs} ms")
                            }
                        }
                        Text("마지막 서버 사용량 표본: 음성 입력 ${live.inputAudioTokens ?: "미확인"} / 출력 ${live.outputAudioTokens ?: "미확인"}, 텍스트 입력 ${live.inputTextTokens ?: "미확인"} / 출력 ${live.outputTextTokens ?: "미확인"}. 누적 청구 총액이 아닙니다.")
                    }

                    Text("마이크 → Gemini Live → 통역 음성. 음성 전송에 동의하면 원음이 Google로 전달됩니다. 직접 음성 중계는 한 출력 언어를 지원하며, 같은 언어의 청취자는 한 연결을 공유합니다.")
                    Text("gemini-3.5-live-translate-preview: 연속 통역, RAG·텍스트·문체 지시 미지원. gemini-3.8-live: 일반 음성 모델, 통역 지시 사용. 두 모델의 실기기 품질 비교 전 자동 기본 경로로 사용하지 않습니다.")
                    Text(if (options.allowLiveAudio) "선택한 서비스로 음성을 전송하는 데 동의했습니다." else "음성 전송 동의는 연결 확인에서 진행합니다.")
                    Text("BYOK 개인 기기 시험용입니다. 배포 앱에 공용 장기 키를 넣지 마세요. 공용 서비스는 사용자 인증·한도·단기 토큰 발급 서버가 필요하며 아직 배포하지 않았습니다. 무료/유료 데이터 정책과 계정 한도를 확인하세요.")
                    Text("중지할 때까지 중계합니다. 연결 끊김이나 과부하로 중단되면 다시 시작하세요. 이전 음성을 자동 재전송하지 않습니다. Gemini Live는 원문·통역의 대응 ID를 확인할 수 없어 비교를 보류합니다. 파일 입력은 지원하지 않으며, 참고 자료는 Gemini 3.8 Live에서 허용한 발췌만 전달합니다.")
                }
                if (options.provider == TranslationApiProvider.OPENAI_REALTIME) {
                    ServiceExperienceChoices("Realtime 처리 방식", listOf(
                        ExperienceChoice("audio", "직접 음성 통역", "마이크 음성 → OpenAI 통역 음성·원문/번역 자막. 한 출력 언어를 지원합니다."),
                        ExperienceChoice("text", "문장 연결", "기기 음성 인식 → API 문장 번역 → 기기 음성 재생.")),
                        if (options.realtimeAudio) "audio" else "text", enabled) {
                        settings.configure(options.copy(realtimeAudio = it == "audio"))
                    }
                    Text(if (options.realtimeAudio)
                        "음성 전송에 동의하면 중지할 때까지 직접 통역합니다. 한 출력 언어의 한 연결을 청취자들이 공유합니다. 중지·실패 후 이전 음성을 자동 재전송하지 않습니다."
                        else "Realtime 문장 연결 · ASR/TTS는 기기 내 엔진입니다. 발화마다 연결하며 실패한 발화는 자동 재전송하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                    if (options.realtimeAudio) liveMonitor?.let { monitor ->
                        val live by monitor.state.collectAsState()
                        Text("${live.state} · 연결 ${live.connections} · 최종 사용량 미확인 ${live.unknownSessions}건")
                        Text("마지막 사용량 표본: 음성 입력 ${live.inputAudioTokens ?: "미확인"} / 출력 ${live.outputAudioTokens ?: "미확인"}, 텍스트 입력 ${live.inputTextTokens ?: "미확인"} / 출력 ${live.outputTextTokens ?: "미확인"}. 실제 청구와 추가 자막 인식 사용량은 제공자 콘솔에서 확인하세요.")
                        Text(live.transcriptionUsage?.durationSeconds?.let {
                            "별도 자막 인식 사용량 표본: ${String.format(java.util.Locale.ROOT, "%.3f", it)}초. 응답 생성 토큰과 구분하며 최종 청구액이 아닙니다."
                        } ?: "별도 자막 인식 사용량 표본: 입력 ${live.transcriptionUsage?.input ?: "미확인"} / 출력 ${live.transcriptionUsage?.output ?: "미확인"} / 합계 ${live.transcriptionUsage?.total ?: "미확인"}. 응답 생성 사용량과 구분하며 최종 청구액이 아닙니다.")
                        live.lossesByLanguage.forEach { (language, reasons) -> reasons.forEach { (reason, total) ->
                            Text("$language · ${reason.name}: ${total.frames} 프레임 / ${total.bytes} bytes")
                        } }
                    }
                }
                if (options.provider != TranslationApiProvider.GEMINI_LIVE) OutlinedTextField(model, { model = it.take(120) }, label = { Text("사용할 모델 ID") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                if (options.provider == TranslationApiProvider.COMPATIBLE) {
                    OutlinedTextField(base, { base = it.take(300) }, label = { Text("HTTPS API 기본 주소 · 예: https://host/v1") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    TranslationApiProtocol.entries.forEach { protocol ->
                        FilterChip(selected = options.protocol == protocol, enabled = enabled,
                            onClick = { settings.configure(options.copy(protocol = protocol)) }, label = { Text(if (protocol == TranslationApiProtocol.RESPONSES) "Responses" else "Chat Completions") })
                    }
                    Text("표준 Responses 또는 Chat Completions 형식의 텍스트 API를 연결합니다. 공급자별 모델·추가 인증·비표준 응답은 개별 확인이 필요합니다.", style = MaterialTheme.typography.bodySmall)
                }
                if (options.provider != TranslationApiProvider.GEMINI_LIVE) TextButton(enabled = enabled, onClick = { message = if (settings.configure(options.copy(model = model.trim(), baseUrl = base.trim().trimEnd('/'))))
                    "API 설정 저장됨 · 전송 허용을 확인하세요." else "모델 ID와 HTTPS 주소를 확인하세요." }) { Text("API 설정 저장") }
                Text("ONLINE 실패 시 오류를 표시합니다. OFFLINE으로 자동 전환하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                val capability = serviceExperience(options)
                ServiceExperienceToggle("관련 도메인 근거 전송 허용 (최대 600자)",
                    if (capability.supportsReferences) "기기에서 찾은 짧은 관련 근거만 전송합니다. 전체 자료 파일은 보내지 않습니다." else "이 음성 경로는 자료 참고를 지원하지 않습니다.",
                    capability.supportsReferences && options.allowDomainReferences, enabled && capability.supportsReferences, settings::setAllowDomainReferences)
                ServiceExperienceToggle("온라인 사용 시 상시 학습",
                    if (capability.supportsLearningComparison) "온라인 결과와 기기 내 결과를 비교합니다. 검토 전에는 자동 적용하지 않습니다."
                    else if (capability.supportsNativePairComparison) "On-통 Live(AI 통역)의 ‘오프라인 결과와 비교’를 별도로 켜세요. 저장된 상시 문장 비교 설정은 적용하지 않습니다."
                    else "이 음성 경로에서는 저장된 학습 옵션을 적용하지 않습니다.",
                    capability.supportsLearningComparison && options.alwaysLearnOnline, capability.supportsLearningComparison && (enabled || options.alwaysLearnOnline), settings::setAlwaysLearnOnline)
                Text("기본 꺼짐. 설정한 API 전송 동의 범위에서 같은 원문을 준비된 로컬 엔진과 비교합니다. 검토 결과는 자동 적용하지 않습니다. 이 저장 옵션만으로 OFFLINE 전송이 켜지지 않습니다. OFFLINE 보조 비교에는 이번 학습의 별도 동의가 필요합니다.", style = MaterialTheme.typography.bodySmall)
                Text(if (options.hasKey) "키 저장됨 · ${if (options.allowOnline) "전송 허용됨" else "전송 꺼짐"}" else "API 키 없음")
                Text("이 앱 실행 중 요청 ${usage.requests}회 · 확인된 사용량의 예상 합계 USD ${usage.estimatedUsd.toPlainString()}" +
                    "\n사용량 미확인 ${usage.unconfirmedUsage}회 · 가격 미확인 ${usage.unpricedUsage}회" +
                    "\n실측 텍스트 입력 ${usage.textInputTokens} · 캐시 ${usage.cachedInputTokens} · 출력 ${usage.textOutputTokens} 토큰" +
                    "\n요청 경과 ${usage.requestWallMillis} ms (청구 시간 아님)" +
                    "\n가격표 ${usage.priceVersion ?: "미확인"} · 실제 청구액이 아니며, 시간 기반 추정치를 합산하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                states.forEach { (language, state) -> Text("$language · ${state.label}", style = MaterialTheme.typography.bodySmall) }
            }
                Text("학습 비교 시도 ${shadow.attempted} · 완료 ${shadow.completed} · 미완료 ${shadow.incomplete} · 건너뜀 ${shadow.skipped}", style = MaterialTheme.typography.bodySmall)
                shadow.last?.let { comparison ->
                    Text("${comparison.source} → ${comparison.target} · 로컬 근거 버전 ${comparison.corpusRevision} · 검증/승인 전 비교 기록" +
                        "\n원문: ${comparison.original}\n온라인: ${comparison.online}\n오프라인: ${comparison.offline}", style = MaterialTheme.typography.bodySmall)
                }
            corpus?.let { LearningComparisonReview(shadow.last, it) }
            message?.let { Text(it) }
        }
    }
}
