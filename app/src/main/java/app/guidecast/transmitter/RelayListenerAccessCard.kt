package app.guidecast.transmitter

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@Composable
internal fun RelayListenerAccessCard(listenerUrl: String?, broadcasting: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
                SelectionContainer { Text(listenerUrl) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(listenerUrl)) }, modifier = Modifier.weight(1f)) { Text("주소 복사") }
                    Button(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, listenerUrl)
                        }, "청취 주소 공유"))
                    }, modifier = Modifier.weight(1f)) { Text("공유") }
                }
                Text("청취자는 접속한 뒤 듣기와 스크립트의 언어를 각각 고를 수 있습니다. 현재 방송에서 제공하는 언어만 표시합니다.")
            }
        }
    }
}
