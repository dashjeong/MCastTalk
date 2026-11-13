package app.guidecast.transmitter

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class MainTab(val title: String) {
    BROADCAST("방송"), RECORDS("기록"), SETTINGS("설정")
}

internal typealias NavigationLeaveGuard = (() -> Unit) -> Unit

@Composable
internal fun RegisterNavigationLeaveGuard(register: (NavigationLeaveGuard?) -> Unit, guard: NavigationLeaveGuard) {
    val current by rememberUpdatedState(guard)
    DisposableEffect(register) {
        register { leave -> current(leave) }
        onDispose { register(null) }
    }
}

internal data class UiActionIdentity(val recordingId: String?, val inputEpoch: Long,
    val inputId: Int?, val playbackTarget: String?)

/** 이동·작업 교체 뒤의 권한 응답은 소비만 하고 새 작업을 시작하지 않는다. */
internal class UiPermissionGate {
    private var pending: UiActionIdentity? = null
    private var cancelled = false

    fun begin(identity: UiActionIdentity): Boolean {
        if (pending != null) return false
        pending = identity
        cancelled = false
        return true
    }

    fun cancel() { cancelled = true }

    fun consume(identity: UiActionIdentity): Boolean {
        val accepted = !cancelled && pending == identity
        pending = null
        return accepted
    }
}

internal fun activeMainService(broadcast: BroadcastSnapshot, streamingCleanupPending: Boolean,
    menu: MenuBroadcastState, notesActive: Boolean, filesActive: Boolean): MCastService? = when {
    (menu.isActive || menu.serverCloseRetryAvailable) && menu.origin != null -> when (menu.origin) {
        MenuBroadcastOrigin.NOTES -> MCastService.NOTES
        MenuBroadcastOrigin.FILES -> MCastService.FILES
        MenuBroadcastOrigin.HISTORY -> MCastService.HISTORY
    }
    broadcast.phase in setOf(BroadcastPhase.STARTING, BroadcastPhase.LIVE, BroadcastPhase.PAUSED) ||
        streamingCleanupPending || broadcast.broadcastFinishing || broadcast.inputStopping || broadcast.inputDraining ||
        broadcast.inputPhase in setOf(InputPhase.STARTING, InputPhase.ACTIVE, InputPhase.PAUSED) || broadcast.translationTestActive ->
        if (broadcast.isInterpreterRelay) MCastService.RELAY else MCastService.MULTILINGUAL
    notesActive -> MCastService.NOTES
    filesActive -> MCastService.FILES
    else -> null
}

@Composable
internal fun MainTabBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                MainTab.entries.forEach { tab ->
                    val color = if (selected == tab) MaterialTheme.colorScheme.primary else GuideCastMuted
                    Column(
                        Modifier.weight(1f).heightIn(min = 72.dp)
                            .selectable(selected == tab, role = Role.Tab, onClick = { onSelect(tab) })
                            .testTag("main-tab-${tab.name.lowercase()}").padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        Canvas(Modifier.size(24.dp)) {
                            val unit = size.width / 24f
                            val stroke = 2.dp.toPx()
                            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
                                color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), stroke, StrokeCap.Round)
                            when (tab) {
                                MainTab.BROADCAST -> {
                                    drawCircle(color, 2.5f * unit, style = Stroke(stroke))
                                    for (radius in listOf(6.5f, 10.5f)) {
                                        for (angle in listOf(-45f, 135f)) drawArc(color, angle, 90f, false,
                                            Offset((12 - radius) * unit, (12 - radius) * unit),
                                            Size(radius * 2 * unit, radius * 2 * unit), style = Stroke(stroke, cap = StrokeCap.Round))
                                    }
                                }
                                MainTab.RECORDS -> { line(5f, 6f, 19f, 6f); line(5f, 12f, 19f, 12f); line(5f, 18f, 14f, 18f) }
                                MainTab.SETTINGS -> {
                                    line(4f, 7f, 14f, 7f); line(18f, 7f, 20f, 7f)
                                    line(4f, 17f, 6f, 17f); line(10f, 17f, 20f, 17f)
                                    drawCircle(color, 2 * unit, Offset(16 * unit, 7 * unit), style = Stroke(stroke))
                                    drawCircle(color, 2 * unit, Offset(8 * unit, 17 * unit), style = Stroke(stroke))
                                }
                            }
                        }
                        Text(tab.title, color = color, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
