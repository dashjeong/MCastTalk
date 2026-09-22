package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Export preferences belong to this panel; the route only owns the system document picker. */
@Composable
internal fun VoiceNoteExportPanel(note: VoiceNote, transcriptQuery: String, enabled: Boolean,
    onExport: (String, VoiceNoteExportOptions) -> Unit) {
    var exportSettings by rememberSaveable { mutableStateOf(false) }
    var exportTranslations by rememberSaveable { mutableStateOf(true) }
    var exportTimestamps by rememberSaveable { mutableStateOf(true) }
    var exportSpeakers by rememberSaveable { mutableStateOf(true) }
    var exportSearchOnly by rememberSaveable { mutableStateOf(false) }
    var exportWindows by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(transcriptQuery.isBlank()) { if (transcriptQuery.isBlank()) exportSearchOnly = false }
    val exportOptions = VoiceNoteExportOptions(exportTranslations, exportTimestamps, exportSpeakers,
        if (exportSearchOnly && transcriptQuery.isNotBlank()) transcriptQuery else null, exportWindows)
    val matchingLines = remember(note.lines, transcriptQuery) { voiceNoteMatchingLines(note.lines, transcriptQuery) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { exportSettings = !exportSettings }) { Text(if (exportSettings) "내려받기 옵션 접기" else "내려받기 옵션") }
        if (exportSettings) {
            VoiceNoteExportToggle("번역문 포함", exportTranslations, enabled) { exportTranslations = it }
            VoiceNoteExportToggle("시각 포함 (TXT·Markdown)", exportTimestamps, enabled) { exportTimestamps = it }
            VoiceNoteExportToggle("화자 이름 포함", exportSpeakers, enabled) { exportSpeakers = it }
            VoiceNoteExportToggle("검색된 구간만 저장", exportSearchOnly && transcriptQuery.isNotBlank(), enabled && transcriptQuery.isNotBlank()) { exportSearchOnly = it }
            VoiceNoteExportToggle("Windows 호환 TXT (UTF-8 BOM)", exportWindows, enabled) { exportWindows = it }
            Text("SRT·JSON은 원음 기준 시각을 유지합니다. WAV는 전체 녹음입니다.", style = MaterialTheme.typography.bodySmall)
        }
        Text(if (exportOptions.search == null) "문서 저장 범위: 전체 ${note.lines.size}개 구간" else "문서 저장 범위: 검색 결과 ${matchingLines.size}개 구간")
        if (note.lines.any { it.translation.isBlank() }) Text("번역 대기 구간이 있습니다. 현재 저장된 원문과 번역만 내려받습니다.", style = MaterialTheme.typography.bodySmall)
        for ((kind, label) in listOf("txt" to "TXT", "srt" to "SRT", "md" to "Markdown", "json" to "JSON")) {
            OutlinedButton(onClick = { onExport(kind, exportOptions) }, enabled = enabled && (exportOptions.search == null || matchingLines.isNotEmpty()), modifier = Modifier.fillMaxWidth()) { Text("$label 내려받기") }
        }
    }
}

@Composable
private fun VoiceNoteExportToggle(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChecked, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
        Text(label, modifier = Modifier.weight(1f))
    }
}
