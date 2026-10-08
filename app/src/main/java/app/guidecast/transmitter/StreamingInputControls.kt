package app.guidecast.transmitter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.guidecast.core.audio.AudioInputKind

@Composable
internal fun StreamingInputControls(
    broadcast: BroadcastSnapshot,
    inputKind: AudioInputKind?,
    inputLabel: String?,
    requestPending: Boolean = false,
    enabled: Boolean = true,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = if (inputKind in setOf(AudioInputKind.DEVICE_PLAYBACK, AudioInputKind.WEB_SPEAKER)) "음성 입력" else "마이크"
    val stopping = broadcast.inputStopping
    val starting = !stopping && (requestPending || broadcast.inputPhase == InputPhase.STARTING)
    val listening = broadcast.inputPhase == InputPhase.ACTIVE
    val canTurnOff = !stopping && (starting || listening)
    val state = when {
        stopping -> "$name 끄는 중"
        starting -> "$name 연결 중"
        listening -> "$name 켜짐"
        broadcast.inputPhase == InputPhase.FAILED -> "$name 확인 필요"
        else -> "$name 꺼짐"
    }
    val action = when {
        stopping -> "$name 끄는 중"
        starting -> "$name 켜기 취소"
        listening -> "$name 끄기"
        else -> "$name 켜기"
    }
    Surface(modifier.fillMaxWidth().testTag("streaming-input-controls"),
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "$state${inputLabel?.let { " · $it" }.orEmpty()}"
                    })
                TextButton(onClick = onOpenSettings,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("streaming-input-settings").semantics { contentDescription = "통번역 설정 열기" }) { Text("설정") }
            }
            if (broadcast.inputPhase == InputPhase.FAILED) broadcast.inputErrorMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Button(onClick = if (canTurnOff) onDisable else onEnable,
                enabled = !stopping && (canTurnOff || enabled),
                colors = if (canTurnOff) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp).testTag("streaming-input-toggle")) {
                Text(action, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
