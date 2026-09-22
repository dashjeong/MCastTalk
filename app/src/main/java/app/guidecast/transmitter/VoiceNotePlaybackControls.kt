package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Reuses the shared file audio player; dragging sends one seek when the gesture finishes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceNotePlaybackControls(
    playback: FileAudioPlaybackState, enabled: Boolean,
    onSeek: (Long) -> Unit, onSkip: (Long) -> Unit, onSpeed: (Float) -> Unit,
) {
    var preview by remember(playback.durationMs) { mutableStateOf<Float?>(null) }
    Slider(value = (preview ?: playback.positionMs.toFloat()).coerceIn(0f, maxOf(1L, playback.durationMs).toFloat()),
        onValueChange = { preview = it },
        onValueChangeFinished = { preview?.let { onSeek(it.toLong()) }; preview = null },
        valueRange = 0f..maxOf(1L, playback.durationMs).toFloat(), enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "녹음 재생 위치" })
    Text("${voiceNoteTime(preview?.toLong() ?: playback.positionMs)} / ${voiceNoteTime(playback.durationMs)}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onSkip(-10_000) }, enabled = enabled) { Text("10초 뒤로") }
        OutlinedButton(onClick = { onSkip(10_000) }, enabled = enabled) { Text("10초 앞으로") }
    }
    Text("재생 속도", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0.75f, 1f, 1.25f, 1.5f).forEach { speed ->
            FilterChip(playback.speed == speed, onClick = { onSpeed(speed) }, enabled = enabled, label = { Text("${speed}배") })
        }
    }
}
