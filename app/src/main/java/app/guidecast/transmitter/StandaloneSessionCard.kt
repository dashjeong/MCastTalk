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
            Text("이 기기에서 들으며 사용해요. 다른 기기의 청취 접속은 열지 않아요.")
            Text("말한 내용과 통역문을 확인해 주세요. 스피커로 들으려면 방송 탭에서 청취 방법과 스피커로 낼 소리를 골라 주세요.")
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
