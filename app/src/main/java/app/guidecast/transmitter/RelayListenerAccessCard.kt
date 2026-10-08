package app.guidecast.transmitter

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelayListenerAccessCard(listenerUrl: String?, broadcasting: Boolean, compact: Boolean = false) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var enlarged by rememberSaveable(listenerUrl) { mutableStateOf(false) }
    val share = {
        if (listenerUrl != null) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, listenerUrl)
        }, "청취 주소 공유"))
    }
    if (compact && listenerUrl != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                QrCode(listenerUrl, "공통 청취 웹페이지 QR 코드", displaySize = 88.dp)
                TextButton(onClick = { enlarged = true }) { Text("QR 확대") }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer { Text(listenerDisplayAddress(listenerUrl), modifier = Modifier.testTag("service-broadcast-listener-url"), style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(listenerUrl)) }) { Text("주소 복사") }
                    Button(onClick = share) { Text("공유") }
                }
            }
        }
        if (enlarged) AlertDialog(onDismissRequest = { enlarged = false },
            title = { Text("청취 웹페이지 QR") },
            text = { QrCode(listenerUrl, "확대한 청취 웹페이지 QR 코드") },
            confirmButton = { TextButton(onClick = { enlarged = false }) { Text("닫기") } })
        return
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("청취 웹페이지", style = MaterialTheme.typography.titleLarge)
            Text("같은 Wi-Fi·핫스팟의 청취자에게 아래 주소를 공유하세요.")
            if (listenerUrl == null) {
                Text(if (broadcasting) "청취 웹페이지를 준비하고 있습니다." else "중계 시작 후 청취 주소가 만들어집니다.")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    QrCode(listenerUrl, description = "공통 청취 웹페이지 QR 코드")
                }
                SelectionContainer { Text(listenerDisplayAddress(listenerUrl), style = MaterialTheme.typography.titleMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(listenerUrl)) }, modifier = Modifier.weight(1f)) { Text("주소 복사") }
                    Button(onClick = share, modifier = Modifier.weight(1f)) { Text("공유") }
                }
                Text("청취자는 접속한 뒤 듣기와 스크립트의 언어를 각각 고를 수 있습니다. 현재 방송에서 제공하는 언어만 표시합니다.")
                Text("QR·복사·공유에 접속 권한을 포함합니다. 같은 방송에서 마이크를 껐다 켜거나 송출을 재개해도 유지됩니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
