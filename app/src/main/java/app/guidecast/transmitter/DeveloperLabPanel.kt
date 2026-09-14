package app.guidecast.transmitter

import android.os.Build
import android.speech.SpeechRecognizer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.guidecast.core.translation.SpeechExpressionProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Developer display is the master gate; every experiment also needs its own explicit opt-in. */
@Composable
internal fun DeveloperLabPanel(
    settings: DeveloperLabSettings,
    previewAllowed: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    if (!LocalDeveloperInfo.current) return
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val context = LocalContext.current
    val options by settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var text by remember { mutableStateOf("안녕하세요. 다음 장소로 함께 이동해 볼까요?") }
    var language by remember { mutableStateOf("ko") }
    var keyInput by remember(options.provider) { mutableStateOf("") }
    var modelInput by remember(options.provider, options.modelId) { mutableStateOf(options.modelId) }
    var message by remember { mutableStateOf<String?>(null) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    var previewBusy by remember { mutableStateOf(false) }
    var previewExpression by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    val hasOnDeviceStt by produceState<Boolean?>(null) {
        value = withContext(Dispatchers.IO) {
            Build.VERSION.SDK_INT >= 31 && runCatching {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            }.getOrDefault(false)
        }
    }
    LaunchedEffect(previewAllowed, options.expressiveTtsEnabled) {
        if (!previewAllowed || previewExpression && !options.expressiveTtsEnabled) previewJob?.cancel()
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) previewJob?.cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); previewJob?.cancel() }
    }
    fun preview(expressionRequested: Boolean) {
        if (previewBusy || !developerSpeechPreviewAllowed(true, expressionRequested,
                options.expressiveTtsEnabled, previewAllowed) || text.isBlank()) return
        val utterance = text
        val tag = language
        val expression = if (expressionRequested) deriveSpeechExpression(utterance, options.translationRegister)
            else SpeechExpressionProfile.BASELINE
        val label = if (expressionRequested) "표현 실험" else "기본 음성"
        previewBusy = true
        previewExpression = expressionRequested
        previewMessage = "$label 준비·재생 중 · 요청 속도 ${expression.rate}, 높낮이 ${expression.pitch}"
        previewJob = scope.launch {
            try {
                playDeveloperSpeechPreview(context, utterance, tag, expression)
                previewMessage = "$label 재생 완료 · 정확한 내용과 자연스러움은 직접 듣고 비교하세요."
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                previewMessage = "음성 시험 시간이 초과됐습니다. 설치된 오프라인 음성과 출력 장치를 확인하세요."
            } catch (cancelled: CancellationException) {
                previewMessage = "음성 시험을 중지했습니다."
                throw cancelled
            } catch (_: Exception) {
                previewMessage = "음성 시험을 완료하지 못했습니다. 해당 언어의 오프라인 음성과 출력 장치를 확인하세요."
            } finally { previewBusy = false }
        }
    }

    Card(if (onBack != null) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxWidth()) {
        val contentModifier = if (onBack != null) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(contentModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            onBack?.let { TextButton(onClick = it) { Text("돌아가기") } }
            Text("개발자 실험실", style = MaterialTheme.typography.titleLarge)
            Text("각 실험은 기본 꺼짐입니다. 개발자 정보 표시를 끄면 실험 적용과 온라인 검토가 중지됩니다.")
            LabSwitch("억양·속도 표현 실험", options.expressiveTtsEnabled, settings::setExpressiveTtsEnabled)
            Text("문장 부호와 선택한 문체를 바탕으로 Android 설치 음성의 속도·높낮이를 0.9–1.1 범위에서 조절합니다. 원음의 감정을 완전히 재현하는 기능은 아닙니다. Moonshine 음성에는 이 조절을 적용하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            LabSwitch("문체·의역 실험", options.paraphraseEnabled, settings::setParaphraseEnabled)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationRegister.entries.forEach { register ->
                    FilterChip(selected = options.translationRegister == register,
                        onClick = { settings.setTranslationRegister(register) },
                        label = { Text(if (register == TranslationRegister.FORMAL) "공식 안내" else "자연스러운 대화") })
                }
            }
            Text("문체 조절에는 선택한 경로의 준비된 Gemma 모델 또는 키를 저장하고 전송을 허용한 온라인 API가 필요합니다. ML Kit 기본 번역만 사용하면 이 옵션을 켜도 의역하지 않습니다. 숫자·이름·부정·조건을 유지하도록 요청하며, 결과는 원음과 대조하세요.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("같은 문장으로 음성 비교", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(text, { if (it.length <= 500) text = it }, enabled = !previewBusy,
                label = { Text("비교할 문장 · 최대 500자") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ko" to "한국어", "en" to "영어", "ja" to "일본어", "zh" to "중국어").forEach { (tag, label) ->
                    FilterChip(selected = language == tag, enabled = !previewBusy,
                        onClick = { language = tag }, label = { Text(label) })
                }
            }
            if (!previewAllowed) Text("입력·방송·실시간 시험을 중지한 뒤 비교할 수 있습니다.", color = MaterialTheme.colorScheme.error)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { preview(false) }, enabled = !previewBusy && previewAllowed && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("기본 음성 듣기") }
                Button(onClick = { preview(true) }, enabled = !previewBusy && previewAllowed && options.expressiveTtsEnabled && text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("표현 실험 듣기") }
            }
            if (previewBusy) OutlinedButton(onClick = { previewJob?.cancel() }) { Text("음성 시험 중지") }
            previewMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("온라인 문장 검토", style = MaterialTheme.typography.titleMedium)
            Text("허용하면 원문·번역문·언어 정보를 선택한 API 제공자에게 전송하며 해당 계정 요금이 발생할 수 있습니다. 음원과 진단 로그는 보내지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudReviewProvider.entries.forEach { provider ->
                    FilterChip(selected = options.provider == provider, onClick = { settings.setProvider(provider) },
                        label = { Text(if (provider == CloudReviewProvider.OPENAI) "OpenAI" else "Google") })
                }
            }
            OutlinedTextField(modelInput, { if (it.length <= 80) modelInput = it }, label = { Text("API 모델 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { message = if (settings.setModelId(modelInput)) "모델 설정을 저장했습니다." else "모델 ID 형식을 확인하세요." }) { Text("모델 설정 저장") }
            OutlinedTextField(keyInput, { if (it.length <= 512) keyInput = it }, label = { Text("API 키") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val saved = settings.setApiKey(keyInput)
                    keyInput = ""
                    message = if (saved) "API 키를 기기에 저장했습니다." else "API 키를 저장하지 못했습니다. 키 형식과 기기 보안 저장소를 확인하세요."
                }, enabled = keyInput.isNotBlank()) { Text("키 저장") }
                TextButton(onClick = {
                    val removed = settings.clearApiKey()
                    keyInput = ""
                    message = if (removed) "저장된 키를 삭제했습니다."
                        else "키 삭제를 확인하지 못했습니다. 온라인 전송은 해제했습니다."
                }, enabled = options.hasApiKey) { Text("키 삭제") }
            }
            Text(if (options.hasApiKey) "API 키 저장됨" else "API 키 없음", style = MaterialTheme.typography.bodySmall)
            LabSwitch("온라인 전송·문장 검토 허용", options.cloudReviewEnabled, settings::setCloudReviewEnabled, options.hasApiKey)
            LabSwitch("검증된 문장 표현 자동 학습", options.autoLearnEnabled, settings::setAutoLearnEnabled)
            Text("자동 학습은 검증을 통과한 표현을 기기의 학습 자료에 추가합니다. 원래 확정 문장을 덮어쓰지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Text("실시간 통역의 API 검토는 자동 학습도 켠 경우에만 별도로 진행합니다. 이미 표시·낭독한 문장은 바꾸지 않으며, 저장한 표현은 이후 같은 문장을 사용할 때 반영합니다. 파일 번역은 제한 시간 안에 검토한 결과를 반영합니다.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            Text("현재 기기의 음성 엔진", style = MaterialTheme.typography.titleMedium)
            Text(when (hasOnDeviceStt) { null -> "온디바이스 인식 서비스 확인 중"; true -> "Android 온디바이스 인식 서비스 있음 · 언어팩과 파일 입력 지원은 실제 요청 시 확인"; false -> "Android 온디바이스 인식 서비스 미확인 · 시스템 음성 서비스와 언어팩을 확인하세요" })
            Text("최신 옵션 검토(2026-09-14): ML Kit Speech Recognition alpha는 기본 모드 Android 31+, 고급 모드 Pixel 10·11 중심입니다. 아직 이 앱에 추가하지 않았습니다. 현재 Moonshine SDK는 v0.1.5이며 한국어 모델·기기별 품질 확인 없이 교체하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch,
        onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
