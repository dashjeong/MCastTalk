package app.guidecast.transmitter

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelayListenerAccessCard(
    listenerUrl: String?,
    broadcasting: Boolean,
    compact: Boolean = false,
    channels: List<BroadcastChannelSnapshot> = emptyList(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val translatedChannels = channels.filter { it.channelId != "source" && !it.listenerUrl.isNullOrBlank() }
    val defaultChannelId = translatedChannels.firstOrNull()?.channelId
    var selectedChannelId by rememberSaveable(channels.map { it.channelId }.joinToString(",")) {
        mutableStateOf(defaultChannelId)
    }
    val effectiveChannelId = if (selectedChannelId != null && translatedChannels.none { it.channelId == selectedChannelId }) defaultChannelId else selectedChannelId
    val currentUrl = (if (effectiveChannelId != null) channels.firstOrNull { it.channelId == effectiveChannelId }?.listenerUrl else null) ?: listenerUrl
    val channelLabel = effectiveChannelId?.let { relayCaptionLanguageLabel(it) }

    var enlarged by rememberSaveable(currentUrl) { mutableStateOf(false) }
    val share = {
        if (currentUrl != null) context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentUrl)
        }, "청취 링크 공유"))
    }
    if (compact && currentUrl != null) {
        val compactControls = workspaceUsesCompactControls(LocalDensity.current.fontScale, LocalConfiguration.current.screenHeightDp)
        if (compactControls) {
            OutlinedCard(onClick = { enlarged = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
                .testTag("service-broadcast-access-details").semantics { contentDescription = "청취 주소·QR 상세 열기. 복사와 공유" }) {
                Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (translatedChannels.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            translatedChannels.forEach { ch ->
                                FilterChip(
                                    selected = effectiveChannelId == ch.channelId,
                                    onClick = { selectedChannelId = ch.channelId },
                                    label = { Text(relayCaptionLanguageLabel(ch.languageTag)) },
                                    modifier = Modifier.sizeIn(minHeight = 36.dp)
                                )
                            }
                            FilterChip(
                                selected = effectiveChannelId == null,
                                onClick = { selectedChannelId = null },
                                label = { Text("공통") },
                                modifier = Modifier.sizeIn(minHeight = 36.dp)
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        QrCode(currentUrl, "${channelLabel?.let { "$it " } ?: "공통 "}청취 웹페이지 QR 코드 · 누르면 확대", displaySize = 72.dp)
                        Column(Modifier.weight(1f)) {
                            Text(if (currentUrl.contains("#token=")) "${channelLabel?.let { "$it " } ?: ""}방송 기기 주소" else "${channelLabel?.let { "$it " } ?: ""}청취 웹주소", style = MaterialTheme.typography.labelSmall)
                            SelectionContainer {
                                Text(listenerDisplayAddress(currentUrl), modifier = Modifier.horizontalScroll(rememberScrollState())
                                    .testTag("service-broadcast-listener-url"), style = MaterialTheme.typography.bodySmall,
                                    softWrap = false)
                            }
                            Text(if (currentUrl.contains("#token=")) "QR 또는 청취 링크 필요 · 숫자 주소만으로 입장 불가" else "숫자 주소 입력 또는 QR로 청취", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (translatedChannels.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    translatedChannels.forEach { ch ->
                        FilterChip(
                            selected = effectiveChannelId == ch.channelId,
                            onClick = { selectedChannelId = ch.channelId },
                            label = { Text(relayCaptionLanguageLabel(ch.languageTag)) },
                            modifier = Modifier.sizeIn(minHeight = 36.dp)
                        )
                    }
                    FilterChip(
                        selected = effectiveChannelId == null,
                        onClick = { selectedChannelId = null },
                        label = { Text("공통") },
                        modifier = Modifier.sizeIn(minHeight = 36.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column {
                    QrCode(currentUrl, "${channelLabel?.let { "$it " } ?: "공통 "}청취 웹페이지 QR 코드", displaySize = 88.dp)
                    TextButton(onClick = { enlarged = true }) { Text("QR 확대") }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (currentUrl.contains("#token=")) "${channelLabel?.let { "$it " } ?: ""}방송 기기 주소" else "${channelLabel?.let { "$it " } ?: ""}청취 웹주소", style = MaterialTheme.typography.labelSmall)
                    Text(if (currentUrl.contains("#token=")) "QR 또는 청취 링크 필요 · 숫자 주소만으로 입장 불가" else "숫자 주소 입력 또는 QR로 청취", style = MaterialTheme.typography.bodySmall)
                    SelectionContainer { Text(listenerDisplayAddress(currentUrl), modifier = Modifier.testTag("service-broadcast-listener-url"), style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(currentUrl)) }) { Text("청취 링크 복사") }
                        Button(onClick = share) { Text("공유") }
                    }
                }
            }
        }
        if (enlarged) AlertDialog(onDismissRequest = { enlarged = false },
            title = { Text("${channelLabel?.let { "$it " } ?: ""}청취 웹페이지 QR") },
            text = { BoxWithConstraints(Modifier.fillMaxWidth()) {
                val qrSize = minOf(224.dp, maxHeight)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (translatedChannels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        translatedChannels.forEach { ch ->
                            FilterChip(
                                selected = effectiveChannelId == ch.channelId,
                                onClick = { selectedChannelId = ch.channelId },
                                label = { Text(relayCaptionLanguageLabel(ch.languageTag)) },
                                modifier = Modifier.sizeIn(minHeight = 36.dp)
                            )
                        }
                        FilterChip(
                            selected = effectiveChannelId == null,
                            onClick = { selectedChannelId = null },
                            label = { Text("공통") },
                            modifier = Modifier.sizeIn(minHeight = 36.dp)
                        )
                    }
                }
                QrCode(currentUrl, "확대한 ${channelLabel?.let { "$it " } ?: "공통 "}청취 웹페이지 QR 코드", displaySize = qrSize)
                Text(if (currentUrl.contains("#token=")) "${channelLabel?.let { "$it " } ?: ""}방송 기기 주소" else "${channelLabel?.let { "$it " } ?: ""}청취 웹주소", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(listenerDisplayAddress(currentUrl), style = MaterialTheme.typography.bodyMedium) }
                Text(if (currentUrl.contains("#token=")) "QR 또는 청취 링크 필요 · 숫자 주소만으로 입장 불가" else "숫자 주소 입력 또는 QR로 청취", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(currentUrl)) }) { Text("청취 링크 복사") }
                    Button(onClick = share) { Text("공유") }
                }
            } } },
            confirmButton = { TextButton(onClick = { enlarged = false }) { Text("닫기") } })
        return
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("청취 웹페이지", style = MaterialTheme.typography.titleLarge)
            Text("같은 Wi-Fi·핫스팟의 청취자에게 QR 또는 청취 링크를 공유하세요.")
            if (currentUrl == null) {
                Text(if (broadcasting) "청취 웹페이지를 준비하고 있습니다." else "중계 시작 후 청취 주소가 만들어집니다.")
            } else {
                if (translatedChannels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        translatedChannels.forEach { ch ->
                            FilterChip(
                                selected = effectiveChannelId == ch.channelId,
                                onClick = { selectedChannelId = ch.channelId },
                                label = { Text(relayCaptionLanguageLabel(ch.languageTag)) },
                                modifier = Modifier.sizeIn(minHeight = 36.dp)
                            )
                        }
                        FilterChip(
                            selected = effectiveChannelId == null,
                            onClick = { selectedChannelId = null },
                            label = { Text("공통") },
                            modifier = Modifier.sizeIn(minHeight = 36.dp)
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    QrCode(currentUrl, description = "${channelLabel?.let { "$it " } ?: "공통 "}청취 웹페이지 QR 코드")
                }
                Text(if (currentUrl.contains("#token=")) "${channelLabel?.let { "$it " } ?: ""}방송 기기 주소" else "${channelLabel?.let { "$it " } ?: ""}청취 웹주소", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(listenerDisplayAddress(currentUrl), style = MaterialTheme.typography.titleMedium) }
                Text(if (currentUrl.contains("#token=")) "QR 또는 청취 링크 필요 · 숫자 주소만으로 입장 불가" else "숫자 주소 입력 또는 QR로 청취", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(currentUrl)) }, modifier = Modifier.weight(1f)) { Text("청취 링크 복사") }
                    Button(onClick = share, modifier = Modifier.weight(1f)) { Text("공유") }
                }
                Text("청취자는 접속한 뒤 듣기와 스크립트의 언어를 각각 고를 수 있습니다. 현재 방송에서 제공하는 언어만 표시합니다.")
                Text("QR·복사·공유에 접속 권한을 포함합니다. 같은 방송에서 마이크를 껐다 켜거나 송출을 재개해도 유지됩니다.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
