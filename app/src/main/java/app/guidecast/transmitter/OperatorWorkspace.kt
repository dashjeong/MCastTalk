package app.guidecast.transmitter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.MicrophoneNoiseMode

internal enum class SettingsCategory(val label: String) {
    LANGUAGES("언어 · 음성"), MODELS("AI 모델"), TOOLS("도구 · 정보"),
}

@Composable
internal fun MicrophoneNoiseOptions(
    mode: MicrophoneNoiseMode,
    enabled: Boolean,
    onSelect: (MicrophoneNoiseMode) -> Unit,
    nearSpeakerFocus: Boolean,
    onFocus: (Boolean) -> Unit,
    inputLabel: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$inputLabel · 음성 입력", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MicrophoneNoiseMode.entries.forEach { choice ->
                FilterChip(
                    selected = mode == choice, onClick = { onSelect(choice) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    label = { Text(choice.label) },
                )
            }
        }
        Text(
            if (!enabled) "입력을 중지한 뒤 변경할 수 있습니다. 방송 서버는 별도로 유지됩니다."
            else when (mode) {
                MicrophoneNoiseMode.OFF -> "앱의 소음 억제·자동 증폭을 끕니다. 음악이나 원본 비교에 사용하세요. 단말 자체 처리는 장치에 따라 남을 수 있습니다."
                MicrophoneNoiseMode.DEVICE -> "단말의 잡음 억제를 사용하며 주변음까지 키우는 자동 증폭은 요청하지 않습니다. 지원 여부는 입력 상태에 표시됩니다."
                MicrophoneNoiseMode.AI -> "오프라인 RNNoise로 음성 중심의 소음 감소를 적용합니다. 단말 잡음 억제·자동 증폭은 겹치지 않습니다. 시험에서 원음과 전사를 비교하세요. 환경에 따라 말소리도 변할 수 있습니다."
            } + " 앱 재생음은 소음 감소 없이 전달합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("가까운 화자 중심", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = nearSpeakerFocus, onCheckedChange = onFocus, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "가까운 화자 중심 입력" })
        }
        if (nearSpeakerFocus) Text(
            "내장 마이크는 화면을 화자 쪽으로 향하게 하고, 외부 마이크는 입 가까이에 두세요. " +
                "지원 기기에 방향·집중 처리를 요청합니다. " +
                "음량만으로 화자를 구분하지 않으며 TV와 동시에 말하면 다른 목소리가 섞일 수 있습니다. " +
                "작은 목소리는 입력 레벨을 확인하고 마이크 가까이에서 말해 주세요.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("입력 종류별로 설정을 기억합니다. 마이크를 바꾸면 해당 입력의 설정을 사용합니다.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Switching pages is view-only: never start, stop or prepare an engine here. */
@Composable
internal fun SettingsCategoryPicker(selected: SettingsCategory,
    categories: List<SettingsCategory> = SettingsCategory.entries, onSelect: (SettingsCategory) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { category ->
            val active = selected == category
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).selectable(active, role = Role.Tab, onClick = { onSelect(category) }),
            ) {
                Column(
                    Modifier.sizeIn(minHeight = 72.dp).padding(horizontal = 4.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SettingsCategoryIcon(category)
                    Text(category.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryIcon(category: SettingsCategory) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp).clearAndSetSemantics { }) {
        val stroke = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round)
        when (category) {
            SettingsCategory.LANGUAGES -> {
                drawLine(color, Offset(size.width * .1f, size.height * .3f), Offset(size.width * .9f, size.height * .3f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .1f, size.height * .7f), Offset(size.width * .9f, size.height * .7f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .7f, size.height * .1f), Offset(size.width * .9f, size.height * .3f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(size.width * .1f, size.height * .7f), Offset(size.width * .3f, size.height * .9f), stroke.width, StrokeCap.Round)
            }
            SettingsCategory.MODELS -> {
                drawRect(color, Offset(size.width * .25f, size.height * .25f), Size(size.width * .5f, size.height * .5f), style = stroke)
                listOf(.35f, .65f).forEach { p ->
                    drawLine(color, Offset(size.width * p, 0f), Offset(size.width * p, size.height * .25f), stroke.width)
                    drawLine(color, Offset(size.width * p, size.height * .75f), Offset(size.width * p, size.height), stroke.width)
                    drawLine(color, Offset(0f, size.height * p), Offset(size.width * .25f, size.height * p), stroke.width)
                    drawLine(color, Offset(size.width * .75f, size.height * p), Offset(size.width, size.height * p), stroke.width)
                }
            }
            SettingsCategory.TOOLS -> listOf(.08f, .58f).forEach { x ->
                listOf(.08f, .58f).forEach { y ->
                    drawRect(color, Offset(size.width * x, size.height * y), Size(size.width * .32f, size.height * .32f), style = stroke)
                }
            }
        }
    }
}

/** Details have no composition cost while closed. The summary never hides failure state. */
@Composable
internal fun WorkspaceDisclosure(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                    stateDescription = if (expanded) "펼쳐짐" else "접힘"
                },
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(if (expanded) "접기 −" else "상세 +", style = MaterialTheme.typography.labelMedium)
            }
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) content()
        }
    }
}
