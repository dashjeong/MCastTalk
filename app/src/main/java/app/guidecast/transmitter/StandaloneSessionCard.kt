package app.guidecast.transmitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun StandaloneSessionCard(
    broadcast: BroadcastSnapshot,
    paused: Boolean,
    onPauseOrResume: () -> Unit,
    onStop: () -> Unit,
    onPlayTestTone: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (paused) "단독 사용 일시정지" else "단독 사용 중", style = MaterialTheme.typography.titleMedium)
            Text("이 기기에서만 처리합니다. 외부 접속 주소나 방송 서버를 열지 않습니다.")
            Text("입력을 시작해 원문·번역을 확인하세요. 아래 '기기 출력'에서 언어와 출력 장치를 선택하면 음성을 들을 수 있습니다.")
            broadcast.translationWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPauseOrResume, modifier = Modifier.weight(1f)) {
                    Text(if (paused) "단독 사용 재개" else "단독 사용 일시정지")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                    Text("단독 사용 중지", color = MaterialTheme.colorScheme.error)
                }
            }
            OutlinedButton(onClick = onPlayTestTone, enabled = !paused, modifier = Modifier.fillMaxWidth()) {
                Text("시험음 확인")
            }
        }
    }
}
