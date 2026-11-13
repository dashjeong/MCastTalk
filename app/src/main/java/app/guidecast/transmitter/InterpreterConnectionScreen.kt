package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun interpreterStateLabel(state: String?): String = when (state) {
    "HUMAN" -> "사람 통역 중"
    "WAITING" -> "대기 · 읽던 문장을 마치는 중"
    "AI" -> "AI로 복귀"
    "READY" -> "대기 · 통역사 연결됨"
    else -> "대기"
}

@Composable
internal fun InterpreterConnectionEntry(broadcast: BroadcastSnapshot) {
    if (broadcast.listenerUrl == null || broadcast.translationChannels.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("통역사 연결") }
    if (open) InterpreterConnectionScreen(broadcast) { open = false }
}

@Composable
private fun InterpreterConnectionScreen(broadcast: BroadcastSnapshot, close: () -> Unit) {
    val channels = broadcast.translationChannels
    var language by remember(broadcast.recordingId) { mutableStateOf(channels.first().channelId) }
    var url by remember(language, broadcast.recordingId) { mutableStateOf(broadcast.interpreterInvite?.invoke(language)) }
    var showTrust by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    BackHandler(onBack = close)
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("통역사 연결", style = MaterialTheme.typography.headlineSmall)
                Text("통역사가 맡을 언어를 골라 QR을 보여 주세요. 나머지 언어는 AI가 통역합니다.")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    channels.forEach { channel ->
                        FilterChip(language == channel.channelId, onClick = { language = channel.channelId },
                            label = { Text(channel.displayName) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text(interpreterStateLabel(broadcast.interpreterStates[language]), style = MaterialTheme.typography.titleMedium)
                if (url != null) {
                    QrCode(url!!, "통역사 연결 QR", displaySize = 240.dp)
                    Text("5분 안에 한 번만 연결할 수 있어요. 같은 와이파이에서 접속하고 이어폰을 끼세요.")
                    TextButton(onClick = { showTrust = !showTrust }, modifier = Modifier.heightIn(min = 48.dp)) { Text("처음 연결할 때 · 보안 연결 안내") }
                    if (showTrust) broadcast.speakerUrl?.let { guide ->
                        Text("이 QR의 안내에 따라 보안 연결을 준비한 뒤, 위의 통역사 QR로 접속해 주세요.")
                        QrCode(guide, "보안 연결 준비 안내 QR", displaySize = 168.dp)
                    }
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url!!)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("주소 복사") }
                    OutlinedButton(onClick = { url = broadcast.interpreterInvite?.invoke(language) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("새 QR 만들기") }
                } else Text(if (broadcast.isInterpreterRelay) "직접 음성 통역에서는 통역사 연결을 준비 중입니다. 문장 통역 방송에서 연결해 주세요."
                    else "보안 연결을 준비하지 못했어요. 방송의 화자 마이크 안내에서 보안 연결을 확인해 주세요.")
                Button(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("방송으로 돌아가기") }
            }
        }
    }
}
