package app.guidecast.transmitter

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// Purpose-specific high-contrast HUD roles; the operator screens retain their daylight theme.
private val HudBackground = Color.Black
private val HudSource = Color(0xFF8BE9FD)
private val HudTranslation = Color(0xFFFFE082)

@Composable
internal fun LiveTranscriptHud(
    transcripts: List<TranslationTranscriptLine>,
    targetLanguageTags: List<String>,
    onBack: () -> Unit,
    onReconnect: (() -> Unit)? = null,
    recoveryMessage: String? = null,
) {
    BackHandler(onBack = onBack)
    var controls by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    var size by rememberSaveable { mutableStateOf(32) }
    val rows = remember(transcripts) { liveTranscriptDisplayLines(transcripts) }
    val languages = remember(transcripts, targetLanguageTags) {
        (targetLanguageTags + transcripts.flatMap { it.translations.keys }).distinct().sorted()
    }
    val list = rememberLazyListState()
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val keptAwake = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldBehavior = bars?.systemBarsBehavior
        val wasStatusVisible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
        val wasNavigationVisible = androidx.core.view.ViewCompat.getRootWindowInsets(view)
            ?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (!keptAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (wasStatusVisible) bars?.show(WindowInsetsCompat.Type.statusBars())
            if (wasNavigationVisible) bars?.show(WindowInsetsCompat.Type.navigationBars())
            if (oldBehavior != null) bars.systemBarsBehavior = oldBehavior
        }
    }
    LaunchedEffect(rows, follow, language, size) {
        if (follow && rows.isNotEmpty()) list.scrollToItem(0)
    }
    LaunchedEffect(languages) { if (language !in languages) language = null }
    Surface(Modifier.fillMaxSize().semantics { paneTitle = "실시간 스크립트 HUD" }, color = HudBackground) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = list, reverseLayout = true,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        follow = false
                        controls = true
                    }
                }, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                items(rows, key = { it.sequence }) { row ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.sourceText, color = HudSource, fontSize = size.sp, lineHeight = (size * 1.4f).sp)
                        language?.let { selected -> row.translations[selected]?.let { translated ->
                            Text(translated, color = HudTranslation, fontSize = size.sp, lineHeight = (size * 1.4f).sp)
                        } }
                    }
                }
            }
            if (controls) Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding(),
                color = Color(0xFF202020), contentColor = Color.White) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onBack) { Text("HUD 닫기", color = Color.White) }
                        TextButton(onClick = { follow = true; controls = false }) { Text("실시간 따라가기", color = HudSource) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = language == null, onClick = { language = null }, label = { Text("원문만", color = if (language == null) Color.Black else Color.White) })
                        languages.forEach { tag ->
                            FilterChip(selected = language == tag, onClick = { language = tag },
                                label = { Text("번역 $tag", color = if (language == tag) Color.Black else Color.White) })
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24, 32, 44).forEach { value ->
                            FilterChip(selected = size == value, onClick = { size = value },
                                label = { Text("${value}sp", color = if (size == value) Color.Black else Color.White) })
                        }
                        TextButton(onClick = { controls = false }) { Text("읽기", color = Color.White) }
                    }
                    if (onReconnect != null) TextButton(onClick = onReconnect) { Text("통역 다시 연결", color = HudTranslation) }
                    recoveryMessage?.let { Text(it, color = HudTranslation, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
