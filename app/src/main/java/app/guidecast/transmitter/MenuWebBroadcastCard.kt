package app.guidecast.transmitter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MenuWebBroadcastCard(app: GuideCastApplication, origin: MenuBroadcastOrigin,
    canStart: Boolean, onStart: () -> Unit, onStop: () -> Unit = { app.menuBroadcast.stop() },
    startUnavailableReason: String? = null) {
    val state by app.menuBroadcast.state.collectAsStateWithLifecycle()
    val own = state.origin == origin
    val active = own && state.isActive
    var showVoiceSetup by remember { mutableStateOf(false) }
    var checkingVoices by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    if (showVoiceSetup) AlertDialog(
        onDismissRequest = { if (!checkingVoices) showVoiceSetup = false },
        title = { Text("통역 음성 준비") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("통역할 언어의 음성을 준비해요. 설치된 음성을 확인하고, 새 음성은 자동으로 내려받지 않아요.")
            if (state.isActive) Text("청취자에게 보내기를 끈 뒤 준비해 주세요. 방송을 다시 시작하면 지나간 문장도 들을 수 있어요.")
            state.voiceSetupLanguageTags.forEach { tag ->
                SystemVoiceSetupActions(
                    languageLabel = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.KOREAN).ifBlank { tag },
                    preference = app.speechSynthesisProvider.voicePreference(tag),
                    enabled = !checkingVoices && !state.isActive && !app.dataTransferUnavailable(),
                    onRecheck = {
                        if (!checkingVoices) scope.launch {
                            checkingVoices = true
                            voiceMessage = "설치된 음성을 확인하고 있어요."
                            try { voiceMessage = app.menuBroadcast.recheckInstalledVoices(setOf(tag)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { voiceMessage = "음성을 준비하지 못했어요. 설치와 설정을 확인한 뒤 다시 시도해 주세요." }
                            finally { checkingVoices = false }
                        }
                    },
                )
            }
            voiceMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        } },
        confirmButton = { TextButton(onClick = { showVoiceSetup = false }, enabled = !checkingVoices) { Text("닫기") } },
    )
    val cardLimit = (LocalConfiguration.current.screenHeightDp * 0.42f).coerceIn(160f, 340f).dp
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = cardLimit).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("청취자에게 보내기", style = MaterialTheme.typography.titleMedium)
            Text(if (own && state.serverCloseRetryAvailable) "방송을 끝내지 못했어요. 다시 시도해 주세요."
                else if (own && state.isStopping) "방송을 끝내고 있어요. 잠시 기다려 주세요." else if (own) when (state.phase) {
                MenuBroadcastPhase.STARTING -> "들을 주소와 음성을 준비하고 있어요."
                MenuBroadcastPhase.LIVE -> "방송 중"
                MenuBroadcastPhase.PAUSED -> "방송 일시정지 · 주소 유지"
                MenuBroadcastPhase.COMPLETED -> "재생 완료 · 웹에서 다시 듣기 가능"
                MenuBroadcastPhase.FAILED -> "방송을 준비하거나 보내지 못했어요"
                MenuBroadcastPhase.IDLE -> "방송 종료됨"
            } else "같은 와이파이에 연결한 사람에게 고른 음성과 글을 보내요.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) Button(onClick = onStart,
                    enabled = canStart && !state.isActive && !app.webBroadcastOwnership.isOwned,
                    modifier = Modifier.testTag("service-broadcast-start")) { Text("보내기 시작") }
                else {
                    Button(onClick = {
                        if (app.menuBroadcast.state.value.generation != state.generation || !app.menuBroadcast.owns(origin))
                            return@Button
                        onStop()
                        if (app.menuBroadcast.state.value.generation == state.generation && app.menuBroadcast.owns(origin))
                            app.menuBroadcast.stop(state.generation)
                    }, enabled = state.canRequestStop,
                        modifier = Modifier.testTag("service-broadcast-stop")) {
                        Text(if (state.serverCloseRetryAvailable) "끝내기 재시도" else "보내기 끝내기")
                    }
                    if (state.phase == MenuBroadcastPhase.LIVE) OutlinedButton(enabled = !state.isStopping, onClick = {
                        app.menuBroadcast.pause(state.generation)
                    }) { Text("잠시 멈추기") }
                    if (state.phase == MenuBroadcastPhase.PAUSED) OutlinedButton(enabled = !state.isStopping, onClick = {
                        app.menuBroadcast.resume(state.generation)
                    }) { Text("다시 보내기") }
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!canStart && !active) Text(startUnavailableReason ?: "음성을 고르거나 마이크 녹음을 준비한 뒤 시작해 주세요.", style = MaterialTheme.typography.bodySmall)
            if (state.isActive && !own) Text("다른 메뉴에서 방송하고 있어요. 그 방송을 끝낸 뒤 시작해 주세요.")
            if (own) {
                state.listenerUrl?.let { url ->
                    RelayListenerAccessCard(url, active, compact = true)
                }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.warningMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.voiceSetupLanguageTags.isNotEmpty()) OutlinedButton(
                    onClick = { voiceMessage = null; showVoiceSetup = true },
                    modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("menu-voice-prepare"),
                ) { Text("음성 준비") }
            }
            }
        }
    }
}
