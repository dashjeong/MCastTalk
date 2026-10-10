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
            Text("이 콘텐츠의 통역 언어를 준비합니다. 설치된 음성만 확인하며 새 모델을 자동으로 내려받지 않습니다.")
            if (state.isActive) Text("웹오디오방송을 중지한 뒤 준비하세요. 이미 지나간 문장은 방송을 다시 시작하면 들을 수 있습니다.")
            state.voiceSetupLanguageTags.forEach { tag ->
                SystemVoiceSetupActions(
                    languageLabel = java.util.Locale.forLanguageTag(tag).getDisplayLanguage(java.util.Locale.KOREAN).ifBlank { tag },
                    preference = app.speechSynthesisProvider.voicePreference(tag),
                    enabled = !checkingVoices && !state.isActive && !app.dataTransferUnavailable(),
                    onRecheck = {
                        if (!checkingVoices) scope.launch {
                            checkingVoices = true
                            voiceMessage = "설치된 음성을 확인하고 있습니다."
                            try { voiceMessage = app.menuBroadcast.recheckInstalledVoices(setOf(tag)) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { voiceMessage = "음성을 준비하지 못했습니다. 설치·설정을 확인한 뒤 다시 시도하세요." }
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
            Text("웹오디오방송", style = MaterialTheme.typography.titleMedium)
            Text(if (own && state.serverCloseRetryAvailable) "웹 서버 종료 실패 · 다시 시도해 주세요."
                else if (own && state.isStopping) "웹오디오방송 종료 중 · 잠시 기다려 주세요." else if (own) when (state.phase) {
                MenuBroadcastPhase.STARTING -> "청취 주소와 음성을 준비하고 있습니다."
                MenuBroadcastPhase.LIVE -> "방송 중"
                MenuBroadcastPhase.PAUSED -> "방송 일시정지 · 주소 유지"
                MenuBroadcastPhase.COMPLETED -> "재생 완료 · 웹에서 다시 듣기 가능"
                MenuBroadcastPhase.FAILED -> "방송 준비 또는 송출 실패"
                MenuBroadcastPhase.IDLE -> "방송 종료됨"
            } else "선택한 음원과 스크립트를 같은 Wi-Fi로 방송합니다.",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) Button(onClick = onStart,
                    enabled = canStart && !state.isActive && !app.webBroadcastOwnership.isOwned,
                    modifier = Modifier.testTag("service-broadcast-start")) { Text("웹오디오방송 시작") }
                else {
                    Button(onClick = {
                        if (app.menuBroadcast.state.value.generation != state.generation || !app.menuBroadcast.owns(origin))
                            return@Button
                        onStop()
                        if (app.menuBroadcast.state.value.generation == state.generation && app.menuBroadcast.owns(origin))
                            app.menuBroadcast.stop(state.generation)
                    }, enabled = state.canRequestStop,
                        modifier = Modifier.testTag("service-broadcast-stop")) {
                        Text(if (state.serverCloseRetryAvailable) "방송 종료 다시 시도" else "웹오디오방송 중지")
                    }
                    if (state.phase == MenuBroadcastPhase.LIVE) OutlinedButton(enabled = !state.isStopping, onClick = {
                        app.menuBroadcast.pause(state.generation)
                    }) { Text("웹오디오방송 일시정지") }
                    if (state.phase == MenuBroadcastPhase.PAUSED) OutlinedButton(enabled = !state.isStopping, onClick = {
                        app.menuBroadcast.resume(state.generation)
                    }) { Text("웹오디오방송 다시 시작") }
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!canStart && !active) Text(startUnavailableReason ?: "음원을 선택하거나 마이크 녹음 준비를 마친 뒤 시작하세요.", style = MaterialTheme.typography.bodySmall)
            if (state.isActive && !own) Text("다른 메뉴에서 방송 중입니다. 해당 방송을 종료한 뒤 시작하세요.")
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
