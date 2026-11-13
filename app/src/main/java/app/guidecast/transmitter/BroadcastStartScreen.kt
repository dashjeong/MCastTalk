package app.guidecast.transmitter

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioInputDevice
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.AudioOutputKind
import app.guidecast.core.stream.RECORDING_TITLE_MAX_CODE_POINTS

internal enum class BroadcastOutput(val label: String) {
    LISTENERS("웹방송"), PHONE("이 휴대폰 스피커"), SPEAKER("스피커"), BOTH("둘 다")
}

// 실기기에서 확인된 인식 중단 원인을 찾을 때까지 이 값 하나로 선택을 닫는다.
internal const val PHONE_SPEAKER_OPTION_ENABLED = false

internal fun availableBroadcastOutput(choice: BroadcastOutput): BroadcastOutput =
    if (choice == BroadcastOutput.PHONE && !PHONE_SPEAKER_OPTION_ENABLED) BroadcastOutput.LISTENERS else choice

internal fun broadcastSpeakerOutput(input: AudioInputDevice?, outputs: List<LocalMonitorRoute>,
    phone: Boolean, language: String, targetCount: Int, native: Boolean): LocalMonitorRoute? {
    if (input == null) return null
    val allowed = phone && input.kind == AudioInputKind.BUILT_IN &&
        LocalInterpretationPolicy(enabled = true).permits(language, targetCount, native, true)
    if (phone && !allowed) return null
    return LocalMonitorOutputRoutePlanner.eligible(input, outputs, allowed).firstOrNull {
        it.platformId != null && (!phone || it.kind == AudioOutputKind.BUILT_IN_SPEAKER)
    }
}

internal fun pendingPhoneSpeakerChannel(broadcast: BroadcastSnapshot, recording: String?, language: String): String? {
    if (recording == null || broadcast.recordingId != recording || broadcast.phase != BroadcastPhase.LIVE ||
        broadcast.inputPhase != InputPhase.ACTIVE || broadcast.inputStopping || broadcast.inputDraining ||
        !broadcast.localInterpretationEnabled || broadcast.isInterpreterRelay) return null
    return broadcast.translationChannels.singleOrNull()?.takeIf {
        it.channelId != "source" && it.languageTag == language && language != "source"
    }?.channelId
}

internal fun phoneSpeakerPreparation(api: TranslationApiOptions, models: TranslationModelUiState): StreamingPreparationAdvice? =
    streamingPreparationAdvice(api, models.copy(broadcastTranslationEnabled = true))

@Composable
internal fun BroadcastStartScreen(
    online: Boolean, native: Boolean, enabled: Boolean,
    source: Pair<String, String>, sources: List<Pair<String, String>>,
    targets: List<Pair<String, String>>, languages: List<Pair<String, String>>, maximum: Int,
    output: BroadcastOutput, speakerSound: String, outputName: String?, onlineNotice: String?,
    inputLabel: String, onInputSettings: () -> Unit,
    onMode: (Boolean) -> Unit, onSource: (String) -> Unit, onTarget: (String) -> Unit,
    onOutput: (BroadcastOutput) -> Unit, onSpeakerSound: (String) -> Unit,
    onOnlineSettings: () -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier,
    broadcastTitle: String? = null, onBroadcastTitleChange: (String) -> Unit = {},
    favorites: @Composable () -> Unit = {},
) {
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(modifier.fillMaxSize()) {
    // 높이가 부족하면 선택 창으로 모아 시작 버튼이 아래로 밀리지 않게 한다.
    val textLine = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
    val expandedHeight = 48.dp * 5 + textLine * 3 + 64.dp + 8.dp + 6.dp * 16 +
        (if (online) 48.dp else 0.dp) +
        (if (!online && targets.size >= 3) textLine * 3 else if (targets.size > 3) textLine else 0.dp) +
        (if (output != BroadcastOutput.LISTENERS) 48.dp + textLine * 2 else 0.dp) +
        (if (broadcastTitle != null) 48.dp + 8.dp else 0.dp)
    val compact = maxHeight < expandedHeight
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        favorites()
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { picker = "source" }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("말하는 언어 · ${source.second} ▾", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onInputSettings, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("입력 · $inputLabel ▾", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!compact) Text("통역 방식", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "오프라인", true to "온라인").forEach { (value, label) ->
                FilterChip(selected = online == value, enabled = enabled, onClick = { onMode(value) },
                    label = { Text(label) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
            if (compact && online) TextButton(enabled = enabled, onClick = onOnlineSettings,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(if (onlineNotice != null) "이용 설정 확인" else "이용 설정") }
        }
        if (online && !compact) Row {
            Text(onlineNotice ?: "이용 키와 전송 동의를 확인했어요", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (onlineNotice != null) TextButton(enabled = enabled, onClick = onOnlineSettings) { Text("넣기") }
        }
        if (compact) TextButton(enabled = enabled, onClick = { picker = "targets" },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("들려줄 언어 · ${targets.joinToString(" · ") { it.second }} ▾", maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
        Text(if (output == BroadcastOutput.PHONE) "들려줄 언어 · 하나씩 번갈아 말해요" else
            "들려줄 언어 · ${targets.size}개 선택 · 최대 ${maximum}개", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            targets.forEach { (tag, label) -> FilterChip(selected = true, enabled = enabled && targets.size > 1,
                onClick = { onTarget(tag) }, label = { Text("$label ×") }, modifier = Modifier.heightIn(min = 48.dp)) }
            OutlinedButton(enabled = enabled, onClick = { picker = "targets" }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (output == BroadcastOutput.PHONE) "언어 바꾸기" else "언어 추가") }
        }
        // 오프라인은 번역을 한 번에 하나씩 해서, 3개부터는 마감에 못 맞출 문장을 간단한 번역으로 대신 낸다(S21 실측).
        if (!online && targets.size >= 3) Text("오프라인에서 언어 3개부터는 일부 문장이 간단한 번역으로 나가요. " +
            "정확도가 중요하면 2개 이하나 온라인을 쓰세요", style = MaterialTheme.typography.bodySmall)
        else if (targets.size > 3) Text("언어가 많으면 음성이 늦어질 수 있어요", style = MaterialTheme.typography.bodySmall)
        }
        if (compact) Row(Modifier.fillMaxWidth()) {
            TextButton(enabled = enabled, onClick = { picker = "output" },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("청취 방법 · ${output.label} ▾", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (output != BroadcastOutput.LISTENERS) TextButton(enabled = enabled, onClick = { picker = "sound" },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("스피커 소리 · ${if (speakerSound == "source") "말한 그대로" else targets.firstOrNull { it.first == speakerSound }?.second.orEmpty()} ▾",
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
        Text("청취 방법", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BroadcastOutput.entries.filter { availableBroadcastOutput(it) == it }.forEach { choice -> FilterChip(selected = output == choice,
                enabled = enabled && !(native && choice == BroadcastOutput.PHONE), onClick = { onOutput(choice) }, label = { Text(choice.label, style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.heightIn(min = 48.dp)) }
        }
        if (output != BroadcastOutput.LISTENERS) {
            TextButton(enabled = enabled, onClick = { picker = "sound" }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("스피커로 낼 소리 · ${if (speakerSound == "source") "말한 그대로" else targets.firstOrNull { it.first == speakerSound }?.second.orEmpty()} ▾")
            }
            Text(if (output == BroadcastOutput.PHONE) "말한 그대로는 스피커를 연결해야 들을 수 있어요" else
                outputName ?: "스피커나 이어폰을 연결하면 쓸 수 있어요", style = MaterialTheme.typography.bodySmall)
        }
        if (native && PHONE_SPEAKER_OPTION_ENABLED) Text("이 휴대폰 스피커는 음성을 바로 주고받는 통역에서는 쓸 수 없어요", style = MaterialTheme.typography.bodySmall)
        }
        if (broadcastTitle != null && compact) TextButton(enabled = enabled, onClick = { picker = "title" },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("방송 제목 · ${broadcastTitle.ifBlank { "자동으로 정하기" }} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (broadcastTitle != null && !compact) OutlinedTextField(value = broadcastTitle, onValueChange = {
            if (it.codePointCount(0, it.length) <= RECORDING_TITLE_MAX_CODE_POINTS) onBroadcastTitleChange(it)
        }, enabled = enabled, singleLine = true, label = { Text("방송 제목 (선택)") },
            placeholder = { Text("비워 두면 자동으로 정해요") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Button(onClick = onStart, enabled = enabled && (output != BroadcastOutput.PHONE || outputName != null),
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("방송 시작") }
        Spacer(Modifier.height(8.dp))
    }
    }
    if (picker == "output") AlertDialog(onDismissRequest = { picker = null }, title = { Text("청취 방법") },
        text = { Column {
            BroadcastOutput.entries.filter { availableBroadcastOutput(it) == it }.forEach { choice ->
                TextButton(enabled = enabled && !(native && choice == BroadcastOutput.PHONE), onClick = { onOutput(choice); picker = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(choice.label) }
            }
            Text(outputName ?: "스피커나 이어폰을 연결하면 쓸 수 있어요", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { picker = null }) { Text("닫기") } })
    if (picker == "title") AlertDialog(onDismissRequest = { picker = null }, title = { Text("방송 제목 (선택)") },
        text = { OutlinedTextField(value = broadcastTitle.orEmpty(), onValueChange = {
            if (it.codePointCount(0, it.length) <= RECORDING_TITLE_MAX_CODE_POINTS) onBroadcastTitleChange(it)
        }, enabled = enabled, singleLine = true, placeholder = { Text("비워 두면 자동으로 정해요") }) },
        confirmButton = { TextButton(onClick = { picker = null }) { Text("닫기") } })
    picker?.takeUnless { it in setOf("output", "title") }?.let { selectedPicker ->
        val choices = when (selectedPicker) {
            "source" -> sources
            "sound" -> (if (native || output == BroadcastOutput.PHONE) emptyList() else listOf("source" to "말한 그대로")) + targets
            else -> languages
        }
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(when (selectedPicker) {
            "source" -> "말하는 언어"; "sound" -> "스피커로 낼 소리"; else -> "들려줄 언어"
        }) }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEach { (tag, label) ->
                val checked = targets.any { it.first == tag }
                TextButton(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = enabled && (selectedPicker != "targets" || output == BroadcastOutput.PHONE ||
                        (if (checked) targets.size > 1 else targets.size < maximum)),
                    onClick = { when (selectedPicker) {
                        "source" -> { onSource(tag); picker = null }
                        "sound" -> { onSpeakerSound(tag); picker = null }
                        else -> { onTarget(tag); if (output == BroadcastOutput.PHONE) picker = null }
                    } }) { Text(if (selectedPicker == "targets" && checked) "✓ $label" else label) }
            }
        } }, confirmButton = { TextButton(onClick = { picker = null }) { Text("닫기") } })
    }
}
