package app.guidecast.transmitter

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/** Installation belongs to the selected engine. Returning is a recheck, never proof of success. */
internal fun systemVoiceSetupIntents(enginePackage: String?): List<Intent> = buildList {
    if (enginePackage != null) {
        add(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(enginePackage))
    }
    add(Intent("com.android.settings.TTS_SETTINGS"))
}

@Composable
internal fun SystemVoiceSetupActions(
    languageLabel: String,
    preference: SpeechVoicePreference,
    enabled: Boolean,
    onRecheck: () -> Unit,
) {
    val context = LocalContext.current
    val currentRecheck by rememberUpdatedState(onRecheck)
    var refresh by remember { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh++
        message = "기기 설정에서 돌아왔습니다. 아래 준비 결과를 확인하세요. 설치 취소는 준비 완료가 아닙니다."
        currentRecheck()
    }
    val engines = remember(refresh, preference) {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }.distinct()
            .sortedWith(compareBy<String> { it != preference.enginePackage }.thenBy { it })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$languageLabel 음성팩은 Google·Samsung의 설치 화면에서 내려받습니다. 완료하거나 취소한 뒤 돌아오면 다시 확인합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
        engines.forEach { engine ->
            val label = when (engine) {
                GOOGLE_TTS_PACKAGE -> "Google"
                "com.samsung.SMT" -> "Samsung"
                else -> runCatching {
                    context.packageManager.getApplicationInfo(engine, 0).loadLabel(context.packageManager).toString()
                }.getOrDefault("기기")
            }
            OutlinedButton(
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    val opened = systemVoiceSetupIntents(engine).any { intent ->
                        runCatching { launcher.launch(intent); true }.getOrDefault(false)
                    }
                    message = if (opened) "$label 설정에서 $languageLabel 음성 데이터를 선택해 설치하세요."
                    else "음성 설치 화면을 열 수 없습니다. 휴대폰 설정의 글자 읽어주기(TTS)에서 음성 데이터를 설치한 뒤 다시 확인하세요."
                },
            ) { Text("$label 음성팩 설치 · 설정") }
        }
        if (GOOGLE_TTS_PACKAGE !in engines) {
            OutlinedButton(
                enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = {
                    val opened = runCatching {
                        launcher.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_TTS_PACKAGE")))
                        true
                    }.getOrDefault(false)
                    message = if (opened) "Google 음성 엔진을 설치한 뒤 이 화면에서 사용할 언어팩을 준비하세요."
                    else "Google 음성 엔진 설치 페이지를 열 수 없습니다. Play 스토어에서 Google 음성 서비스를 확인하세요."
                },
            ) { Text("Google 음성 엔진 설치") }
        }
        if (engines.isEmpty()) Text("설치된 기기 음성 엔진이 없습니다. 먼저 음성 엔진을 설치하세요.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(enabled = enabled, onClick = onRecheck, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("설치한 음성 다시 확인")
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
