package app.guidecast.transmitter

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun phoneSpeakerStatus(broadcast: BroadcastSnapshot): String = when {
    broadcast.localMonitor.phase == LocalMonitorPhase.FAILED -> "스피커 재생을 시작하지 못했어요"
    broadcast.inputPhase == InputPhase.PAUSED || broadcast.inputStopping -> "마이크가 꺼져 있어요"
    else -> when (broadcast.localInterpretationPhase) {
        LocalInterpretationPhase.LISTENING -> "말씀하세요"
        LocalInterpretationPhase.PLAYING -> "통역이 나오는 중 · 잠시 뒤 말씀하세요"
        LocalInterpretationPhase.PREPARING, LocalInterpretationPhase.OFF -> "통역 준비 중"
    }
}

@Composable
internal fun PhoneSpeakerStatus(broadcast: BroadcastSnapshot) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Text(phoneSpeakerStatus(broadcast), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

internal fun broadcastLanguageStatus(channel: BroadcastChannelSnapshot, captionOnly: Boolean = false): String {
    val missed = maxOf(channel.synthesisFailures, channel.speechBacklogDrops)
    val detail = when {
        channel.lastTranslationError != null || channel.translationFailures > channel.translationRecoveries -> "일부 문장을 통역하지 못했어요"
        captionOnly -> "음성이 아직 없어요 · 글자만 나가요"
        missed > 0 -> "음성 ${missed}문장을 못 냈어요(자막은 나갔어요)"
        channel.lastSynthesisError != null -> "소리를 내지 못했어요 · 자막을 확인해 주세요"
        channel.publishedFrameCount > 0 -> "잘 나가고 있어요"
        else -> "첫 말씀을 기다리고 있어요"
    }
    return "${relayCaptionLanguageLabel(channel.languageTag)}: $detail"
}

@Composable
internal fun BroadcastLanguageStatusList(channels: List<BroadcastChannelSnapshot>, captionOnly: Set<String> = emptySet()) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        channels.filter { it.channelId != "source" }.forEach { channel ->
            Text(broadcastLanguageStatus(channel, channel.languageTag in captionOnly),
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun BroadcastQrScreen(broadcast: BroadcastSnapshot, admissionNumber: String = "", onClose: () -> Unit) {
    var selected by rememberSaveable(broadcast.recordingId) { mutableStateOf<String?>(null) }
    val channels = broadcast.translationChannels.filter { it.listenerUrl != null }
    val url = broadcastListenerUrl(broadcast, selected)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("방송접속 QR", style = MaterialTheme.typography.headlineSmall)
                Text("같은 와이파이나 핫스팟에 연결한 뒤 QR을 비춰 주세요.")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected == null, onClick = { selected = null }, label = { Text("모든 언어") }, modifier = Modifier.heightIn(min = 48.dp))
                    channels.forEach { channel -> FilterChip(selected == channel.channelId,
                        onClick = { selected = channel.channelId }, label = { Text(relayCaptionLanguageLabel(channel.languageTag)) },
                        modifier = Modifier.heightIn(min = 48.dp)) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (url != null) QrCode(url, "청취자 입장 QR", displaySize = minOf(maxWidth, maxHeight, 360.dp))
                    else Text("청취 주소를 준비하고 있어요. 스피커만 쓰는 방송에는 청취 주소가 없어요.")
                }
                url?.let { BroadcastAddressActions(it) }
                if (broadcast.accessMode == OperatorAccessMode.PIN) Text(
                    if (admissionNumber.isNotBlank()) "입장 번호 · $admissionNumber" else "방송 설정에서 정한 입장 번호를 알려 주세요",
                    style = MaterialTheme.typography.titleLarge)
                else Text("입장 번호 없이 들을 수 있어요")
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("방송으로 돌아가기") }
            }
        }
    }
}

/** QR·화면·복사·공유는 서버가 발급한 같은 주소를 사용한다. */
internal fun broadcastListenerUrl(broadcast: BroadcastSnapshot, channelId: String?): String? =
    broadcast.translationChannels.firstOrNull { it.channelId == channelId }?.listenerUrl
        ?.takeIf(String::isNotBlank) ?: broadcast.listenerUrl?.takeIf(String::isNotBlank)

@Composable
private fun BroadcastAddressActions(url: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(url, style = MaterialTheme.typography.bodySmall, softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("broadcast-listener-address"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(url)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("주소 복사") }
            TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
            }, "청취 주소 공유")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("공유") }
        }
    }
}

@Composable
internal fun BroadcastListenerAccess(broadcast: BroadcastSnapshot) {
    var selected by rememberSaveable(broadcast.recordingId) { mutableStateOf<String?>(null) }
    val url = broadcastListenerUrl(broadcast, selected) ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected == null, onClick = { selected = null }, label = { Text("모든 언어") }, modifier = Modifier.heightIn(min = 48.dp))
            broadcast.translationChannels.filter { !it.listenerUrl.isNullOrBlank() }.forEach { channel ->
                FilterChip(selected == channel.channelId, onClick = { selected = channel.channelId },
                    label = { Text(relayCaptionLanguageLabel(channel.languageTag)) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 280.dp) Column {
                QrCode(url, "청취자 입장 QR", displaySize = 88.dp)
                BroadcastAddressActions(url)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QrCode(url, "청취자 입장 QR", displaySize = 88.dp)
                Box(Modifier.weight(1f)) { BroadcastAddressActions(url) }
            }
        }
    }
}
