package app.guidecast.transmitter

import android.content.Intent
import android.speech.tts.TextToSpeech
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

@Composable internal fun DiagnosticsAndVoiceSettings() {
    val context = LocalContext.current
    val application = context.applicationContext as GuideCastApplication
    val exportState by application.diagnosticExportState.collectAsState()
    val directory = remember { File(context.filesDir, "diagnostics") }
    var message by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) application.exportDiagnosticsTo(uri)
    }
    val engines = remember {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }.distinct().sorted()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("오프라인 대체 음성", style = MaterialTheme.typography.titleMedium)
        Text("Moonshine 전용이 아닙니다. 설치된 Samsung·Google 등 오프라인 TTS도 사용합니다. 대만 채널은 중국어 번체(zh-TW) 음성을 설치한 뒤 언어 준비를 다시 누르세요.")
        engines.forEach { engine ->
            OutlinedButton(onClick = {
                val install = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(engine)
                val opened = runCatching { context.startActivity(install); true }.getOrDefault(false)
                if (!opened) {
                    val settingsOpened = runCatching {
                        context.startActivity(Intent("com.android.settings.TTS_SETTINGS")); true
                    }.getOrDefault(false)
                    if (!settingsOpened) message = "휴대폰 설정에서 글자 읽어주기(TTS) 음성 데이터를 설치하세요."
                }
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(when (engine) {
                    "com.samsung.SMT" -> "Samsung 음성 다운로드 · 설정"
                    "com.google.android.tts" -> "Google 음성 다운로드 · 설정"
                    else -> "$engine 음성 설정"
                })
            }
        }
        if (engines.isEmpty()) Text("설치된 Android TTS 엔진이 없습니다. Moonshine 미지원 언어에는 별도 오프라인 TTS가 필요합니다.")
        HorizontalDivider()
        Text("진단 로그", style = MaterialTheme.typography.titleMedium)
        Text("저장 위치: ${directory.absolutePath}", style = MaterialTheme.typography.bodySmall)
        Text("앱 시작·모델 상태·오류·메모리 경고를 순환 저장합니다. 프로세스당 최대 384KiB이며 캐시 삭제로 없어지지 않습니다. 앱 삭제 시에는 제거됩니다.")
        OutlinedButton(onClick = { export.launch("guidecast-diagnostics-${System.currentTimeMillis()}.zip") },
            enabled = !exportState.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (exportState.busy) "로그 저장 중" else "진단 로그 내보내기")
        }
        exportState.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
