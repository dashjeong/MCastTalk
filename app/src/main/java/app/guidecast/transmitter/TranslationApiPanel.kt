package app.guidecast.transmitter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
internal fun TranslationApiPanel(settings: TranslationApiSettings, service: TranslationApiService, enabled: Boolean) {
    val options by settings.state.collectAsState()
    val states by service.states.collectAsState()
    var model by remember(options.model) { mutableStateOf(options.model) }
    var base by remember(options.baseUrl) { mutableStateOf(options.baseUrl) }
    var key by remember(options.credentialScope) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("번역 서비스 · 문체", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationApiProvider.entries.forEach { provider ->
                    FilterChip(selected = options.provider == provider, enabled = enabled, onClick = {
                        val next = when (provider) {
                            TranslationApiProvider.GEMINI -> options.copy(provider = provider, model = "gemini-2.5-flash-lite", baseUrl = "https://generativelanguage.googleapis.com/v1beta")
                            TranslationApiProvider.COMPATIBLE -> options.copy(provider = provider, model = "your-model", baseUrl = "https://your-provider.example/v1", protocol = TranslationApiProtocol.CHAT_COMPLETIONS)
                            else -> options.copy(provider = provider, model = "gpt-5.4-mini", baseUrl = "https://api.openai.com/v1", protocol = TranslationApiProtocol.RESPONSES)
                        }
                        settings.configure(next); message = null
                    }, label = { Text(provider.label) })
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationStyle.entries.forEach { style ->
                    FilterChip(selected = options.tone == style, enabled = enabled, onClick = { settings.setTone(style) },
                        label = { Text(when (style) { TranslationStyle.AUTO -> "문맥에 맞게"; TranslationStyle.FORMAL -> "공식·문어체"; TranslationStyle.CONVERSATIONAL -> "대화·구어체" }) })
                }
            }
            Text("문체 지시는 Gemma·API 경로에서 적용합니다. ML Kit 기본 번역은 문체 지시를 지원하지 않습니다. 의역하더라도 숫자·이름·부정·조건을 유지하도록 요청합니다.", style = MaterialTheme.typography.bodySmall)
            if (options.provider != TranslationApiProvider.LOCAL) {
                OutlinedTextField(model, { model = it.take(120) }, label = { Text("사용할 모델 ID") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                if (options.provider == TranslationApiProvider.COMPATIBLE) {
                    OutlinedTextField(base, { base = it.take(300) }, label = { Text("HTTPS API 기본 주소 · 예: https://host/v1") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    TranslationApiProtocol.entries.forEach { protocol ->
                        FilterChip(selected = options.protocol == protocol, enabled = enabled,
                            onClick = { settings.configure(options.copy(protocol = protocol)) }, label = { Text(if (protocol == TranslationApiProtocol.RESPONSES) "Responses" else "Chat Completions") })
                    }
                    Text("표준 Responses 또는 Chat Completions 형식의 텍스트 API를 연결합니다. 공급자별 모델·추가 인증·비표준 응답은 개별 확인이 필요합니다.", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = enabled, onClick = { message = if (settings.configure(options.copy(model = model.trim(), baseUrl = base.trim().trimEnd('/'))))
                    "API 설정 저장됨 · 전송 허용을 확인하세요." else "모델 ID와 HTTPS 주소를 확인하세요." }) { Text("API 설정 저장") }
                OutlinedTextField(key, { key = it.take(512) }, label = { Text("이 서비스의 API 키") }, enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = enabled && key.isNotEmpty(), onClick = {
                        message = if (settings.saveKey(key)) "키를 기기 보안 저장소에 저장했습니다." else "키를 저장하지 못했습니다."
                        key = ""
                    }) { Text("키 저장") }
                    TextButton(enabled = enabled && options.hasKey, onClick = { message = if (settings.clearKey()) "키 삭제됨 · 전송 해제됨" else "키 삭제 미확인 · 전송은 해제했습니다." }) { Text("키 삭제") }
                }
                Text("전송 대상: ${options.baseUrl}\n선택한 원문과 최대 1,000자의 직전 문맥을 번역 요청에 포함합니다. 인터넷과 별도 API 요금이 필요합니다. 키·원음 파일·진단 로그는 본문에 포함하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                Row { Text("이 API로 통번역 허용", Modifier.weight(1f)); Switch(options.allowOnline, settings::setAllowOnline, enabled = enabled && options.hasKey) }
                Row { Text("API 실패 시 준비된 기기 내 번역 사용", Modifier.weight(1f)); Switch(options.localFallback, settings::setFallback, enabled = enabled) }
                Text(if (options.hasKey) "키 저장됨 · ${if (options.allowOnline) "전송 허용됨" else "전송 꺼짐"}" else "API 키 없음")
                states.forEach { (language, state) -> Text("$language · ${state.label}", style = MaterialTheme.typography.bodySmall) }
            }
            message?.let { Text(it) }
        }
    }
}
