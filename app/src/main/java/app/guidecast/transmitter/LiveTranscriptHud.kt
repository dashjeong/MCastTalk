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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("transcript_hud", android.content.Context.MODE_PRIVATE) }
    // Display preferences never mutate broadcast targets or request a translation.
    var selectedLanguages by remember {
        mutableStateOf(preferences.getStringSet("languages", setOf("source"))!!.toSet())
    }
    var size by remember { mutableStateOf(preferences.getInt("size", 32).takeIf { it in listOf(24, 32, 44) } ?: 32) }
    fun selectLanguage(tag: String, checked: Boolean) {
        val next = if (checked) selectedLanguages + tag else selectedLanguages - tag
        if (next.isNotEmpty()) {
            selectedLanguages = next
            preferences.edit().putStringSet("languages", next).apply()
        }
    }
    val rows = remember(transcripts) { liveTranscriptDisplayLines(transcripts) }
    val languages = remember(transcripts, targetLanguageTags) {
        (targetLanguageTags + transcripts.flatMap { it.translations.keys } + (selectedLanguages - "source")).distinct().sorted()
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
    LaunchedEffect(rows, follow, selectedLanguages, size) {
        if (follow && rows.isNotEmpty()) list.scrollToItem(0)
    }
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
                        row.liveSegmentLanguage?.let { tag ->
                            row.liveStatusLabel?.let { Text(it, color = Color.LightGray) }
                            Text("Live $tag 독립 구간 · 다른 언어와 발화 정렬 미확인", color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
                            if (row.sourceText.isBlank()) Text("원문 미확인 · 음성 출력만으로 번역 정확도를 확인할 수 없습니다", color = Color.LightGray)
                        }
                        if ("source" in selectedLanguages) {
                            Text("원문 · ${row.sourceLanguageTag ?: "언어 미확인"} · ${if (row.sourceText.isBlank()) "미확인" else if (row.isFinal) "확정" else "인식 중"}",
                                color = HudSource, style = MaterialTheme.typography.labelLarge)
                            Text(row.sourceText.ifBlank { "원문 미확인" }, color = HudSource, fontSize = size.sp, lineHeight = (size * 1.4f).sp)
                        }
                        languages.filter { it in selectedLanguages }.forEach { tag ->
                            Text("번역 · $tag", color = HudTranslation, style = MaterialTheme.typography.labelLarge)
                            val translated = row.translations[tag]
                            Text(translated ?: if (row.liveSegmentLanguage != null && tag != row.liveSegmentLanguage) "별도 Live 구간 · 동일 발화 여부 미확인" else if (tag in targetLanguageTags) "미완료 · 번역 결과 없음" else "미완료 · 방송 언어로 선택되지 않음",
                                color = if (translated == null) Color.LightGray else HudTranslation,
                                fontSize = size.sp, lineHeight = (size * 1.4f).sp)
                        }
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
                        (listOf("source") + languages).forEach { tag ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = tag in selectedLanguages,
                                    onCheckedChange = { selectLanguage(tag, it) },
                                    enabled = selectedLanguages.size > 1 || tag !in selectedLanguages)
                                Text(if (tag == "source") "원문" else "번역 $tag", color = Color.White)
                            }
                        }
                    }
                    Text("표시 언어만 변경합니다. 추가 번역 요청이나 음성 방송 언어 변경은 없습니다.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(24, 32, 44).forEach { value ->
                            FilterChip(selected = size == value, onClick = { size = value; preferences.edit().putInt("size", value).apply() },
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
