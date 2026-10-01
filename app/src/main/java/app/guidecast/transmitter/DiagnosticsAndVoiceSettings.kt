package app.guidecast.transmitter

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

data class DiagnosticExportState(val busy: Boolean = false, val message: String? = null)

@Composable internal fun DiagnosticsAndVoiceSettings(onRecheckSpeechVoices: () -> Unit) {
    val context = LocalContext.current
    val application = context.applicationContext as GuideCastApplication
    val exportState by application.diagnosticExportState.collectAsState()
    val developerInfo = LocalDeveloperInfo.current
    val directory = remember { File(context.filesDir, "diagnostics") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) application.exportDiagnosticsTo(uri)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("오프라인 대체 음성", style = MaterialTheme.typography.titleMedium)
        SystemVoiceSetupActions(
            languageLabel = "선택한 언어",
            preference = SpeechVoicePreference.AUTO,
            enabled = true,
            onRecheck = onRecheckSpeechVoices,
        )
        HorizontalDivider()
        Text("문제 해결 자료", style = MaterialTheme.typography.titleMedium)
        Text("오류가 생기면 진단 자료를 내보내 문제 확인에 사용할 수 있습니다. 개발자 정보 표시를 꺼도 필요한 진단 기록은 유지됩니다.")
        if (developerInfo) {
            Text("저장 위치: ${directory.absolutePath}", style = MaterialTheme.typography.bodySmall)
            Text("앱 시작·모델 상태·오류·메모리 경고를 순환 저장합니다. 프로세스당 최대 384KiB이며 캐시 삭제로 없어지지 않습니다. 앱 삭제 시에는 제거됩니다.")
        }
        OutlinedButton(onClick = { export.launch("guidecast-diagnostics-${System.currentTimeMillis()}.zip") },
            enabled = !exportState.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (exportState.busy) "로그 저장 중" else "진단 로그 내보내기")
        }
        exportState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
