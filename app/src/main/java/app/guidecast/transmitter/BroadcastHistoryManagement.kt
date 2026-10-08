package app.guidecast.transmitter

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.guidecast.core.stream.RecordedBroadcast
import app.guidecast.core.stream.RECORDING_TITLE_MAX_CODE_POINTS
import app.guidecast.core.stream.normalizedRecordingTitle
import java.text.DateFormat
import java.util.Date

internal const val RECORDING_SELECTION_HOLD_MILLIS = 3_000L

internal fun recordingDisplayTitle(recording: RecordedBroadcast): String = recording.title.ifBlank {
    "방송 · ${DateFormat.getDateTimeInstance().format(Date(recording.startedAtMillis))}"
}

internal fun recordingCanBeDeleted(recording: RecordedBroadcast, activeId: String?): Boolean =
    recording.id != activeId && (recording.endedAtMillis != null || recording.state == "INTERRUPTED")

internal fun availableRecordingSelections(selected: Collection<String>, history: List<RecordedBroadcast>, activeId: String?): List<String> =
    history.filter { it.id in selected && recordingCanBeDeleted(it, activeId) }.map { it.id }

/** Movement, consumed input and release permanently cancel a pending selection gesture. */
internal class RecordingSelectionHold(private val startedAtMillis: Long) {
    private var canceled = false
    fun update(released: Boolean, consumed: Boolean, movedOutsideSlop: Boolean, multiplePointers: Boolean = false) {
        if (released || consumed || movedOutsideSlop || multiplePointers) canceled = true
    }
    fun canSelect(nowMillis: Long): Boolean = !canceled && nowMillis - startedAtMillis >= RECORDING_SELECTION_HOLD_MILLIS
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun BroadcastHistoryManagementBar(
    selecting: Boolean, selectedCount: Int, selectableCount: Int, busy: Boolean,
    onStartSelection: () -> Unit, onSelectAll: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    if (!selecting) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onStartSelection, enabled = !busy && selectableCount > 0) { Text("선택") }
        }
    } else {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("$selectedCount 개 선택", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy && selectableCount > 0 && selectedCount < selectableCount, onClick = onSelectAll) { Text("전체 선택") }
                    if (selectedCount == 1) TextButton(enabled = !busy, onClick = onRename) { Text("이름 변경") }
                    TextButton(enabled = !busy, onClick = onCancel) { Text("취소") }
                    TextButton(enabled = !busy && selectedCount > 0, onClick = onDelete) { Text("선택 삭제") }
                }
            }
        }
    }
}

@Composable internal fun RecordingHistoryRow(
    recording: RecordedBroadcast, selecting: Boolean, selected: Boolean, canDelete: Boolean,
    busy: Boolean, stateLabel: String, onOpen: () -> Unit, onSelect: () -> Unit, onRename: () -> Unit,
) {
    val click by rememberUpdatedState(if (selecting) onSelect else onOpen)
    val select by rememberUpdatedState(onSelect)
    val enabled by rememberUpdatedState(!busy)
    val tapAllowed by rememberUpdatedState(!busy && (!selecting || canDelete))
    val holdAllowed by rememberUpdatedState(!busy && canDelete)
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.Button
            stateDescription = if (selecting) { if (selected) "선택됨" else if (canDelete) "선택 안 됨" else "방송 운영 중 · 삭제할 수 없음" } else stateLabel
            onClick(if (selecting) "방송 선택" else "음성과 스크립트 열기") { if (enabled && (!selecting || canDelete)) { click(); true } else false }
            customActions = listOf(
                CustomAccessibilityAction("이름 변경") { if (enabled) { onRename(); true } else false },
                CustomAccessibilityAction("선택 모드로 전환") { if (holdAllowed) { select(); true } else false },
            )
        }.onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key in setOf(Key.Enter, Key.Spacebar) && tapAllowed) { click(); true } else false
        }.focusable().pointerInput(recording.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Child controls (for example the management menu) own their own gesture.
                if (down.isConsumed) return@awaitEachGesture
                val hold = RecordingSelectionHold(down.uptimeMillis)
                var tapped = false
                val beforeDeadline = withTimeoutOrNull(RECORDING_SELECTION_HOLD_MILLIS) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        val interrupted = change.isConsumed || moved || event.changes.count { it.pressed } > 1
                        hold.update(!change.pressed, change.isConsumed, moved, event.changes.count { it.pressed } > 1)
                        if (interrupted) return@withTimeoutOrNull false
                        if (!change.pressed) { tapped = true; change.consume(); return@withTimeoutOrNull false }
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                if (beforeDeadline == null && holdAllowed && hold.canSelect(SystemClock.uptimeMillis())) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    select()
                    waitForUpOrCancellation()
                } else if (tapped && tapAllowed) click()
            }
        },
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selecting) Checkbox(checked = selected, enabled = !busy && canDelete, onCheckedChange = { onSelect() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(recordingDisplayTitle(recording), style = MaterialTheme.typography.titleMedium)
                Text(DateFormat.getDateTimeInstance().format(Date(recording.startedAtMillis)), style = MaterialTheme.typography.bodySmall)
                Text(stateLabel)
                Text(if (selecting && !canDelete) "방송을 종료한 뒤 삭제할 수 있습니다." else "음성과 스크립트 확인 · 다시 듣기")
                if (recording.failure != null || recording.droppedRecordingFrames > 0) Text("녹음에 공백이 있습니다", color = MaterialTheme.colorScheme.error)
            }
            if (!selecting) Box {
                TextButton(onClick = { menu = true }, enabled = !busy) { Text("관리") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("선택") }, enabled = canDelete, onClick = { menu = false; onSelect() })
                }
            }
        }
    }
}

@Composable internal fun RecordingRenameDialog(recording: RecordedBroadcast, busy: Boolean, onCancel: () -> Unit, onSave: (String) -> Unit,
    errorMessage: String? = null, onDraftChanged: () -> Unit = {}) {
    var draft by remember(recording.id) { mutableStateOf(recordingDisplayTitle(recording)) }
    val normalized = normalizedRecordingTitle(draft)
    val length = draft.codePointCount(0, draft.length)
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("방송 이름 변경") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it; onDraftChanged() }, enabled = !busy, singleLine = true,
                    label = { Text("방송 제목") }, isError = normalized == null || errorMessage != null,
                    supportingText = { Text(if (normalized == null) "이름을 1~${RECORDING_TITLE_MAX_CODE_POINTS}자로 입력하세요." else "$length / $RECORDING_TITLE_MAX_CODE_POINTS") })
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && normalized != null, onClick = { normalized?.let(onSave) }) { Text(if (busy) "저장 중" else "저장") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("취소") } },
    )
}
