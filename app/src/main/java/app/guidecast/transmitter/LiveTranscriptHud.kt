package app.guidecast.transmitter

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.collect

// Purpose-specific high-contrast HUD roles; the operator screens retain their daylight theme.
private val HudBackground = Color.Black
private val HudSource = Color(0xFF8BE9FD)
private val HudTranslation = Color(0xFFFFE082)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveTranscriptHud(
    transcripts: List<TranslationTranscriptLine>,
    targetLanguageTags: List<String>,
    onBack: () -> Unit,
    onReconnect: (() -> Unit)? = null,
    recoveryMessage: String? = null,
    sourceLanguageTag: String? = null,
    initialShowSource: Boolean? = null,
    displayTargetFilter: List<String>? = null,
) {
    BackHandler(onBack = onBack)
    var controls by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("transcript_hud", android.content.Context.MODE_PRIVATE) }
    val languages = remember(transcripts, targetLanguageTags, displayTargetFilter) {
        transcriptHudLanguageTags(targetLanguageTags, transcripts).filter { displayTargetFilter == null || it in displayTargetFilter }
    }
    // Display preferences never mutate broadcast targets or request a translation.
    var showSource by remember {
        mutableStateOf(initialShowSource ?: preferences.getBoolean("show_source", "source" in
            preferences.getStringSet("languages", setOf("source")).orEmpty()))
    }
    val sourceVisible = showSource || (initialShowSource == null && languages.isEmpty())
    var size by remember { mutableStateOf(preferences.getInt("size", 32).takeIf { it in listOf(24, 32, 44) } ?: 32) }
    var selectedTranslationTag by rememberSaveable { mutableStateOf<String?>(null) }
    var languageMenu by remember { mutableStateOf(false) }
    val groups = remember(transcripts) { relayCaptionPresentation(transcripts).take(100) }
    val displayedTranslationLanguages = relayCaptionDisplayLanguages(languages, selectedTranslationTag)
    val rows = remember(groups, selectedTranslationTag, sourceVisible, displayedTranslationLanguages) {
        transcriptHudVisibleGroups(relayCaptionDisplayGroups(groups, selectedTranslationTag), sourceVisible,
            displayedTranslationLanguages)
    }
    LaunchedEffect(languages) {
        if (selectedTranslationTag != null && selectedTranslationTag !in languages) selectedTranslationTag = null
    }
    val list = rememberLazyListState()
    var followingScroll by remember { mutableStateOf(false) }
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
    LaunchedEffect(rows, follow, sourceVisible, size) {
        if (follow && rows.isNotEmpty()) {
            followingScroll = true
            try { list.scrollToItem(0) } finally { followingScroll = false }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress && !followingScroll }.collect { manuallyScrolling ->
            if (manuallyScrolling) follow = false
        }
    }
    Surface(Modifier.fillMaxSize().semantics { paneTitle = "실시간 스크립트 HUD" }, color = HudBackground) {
      BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val shortViewport = maxHeight < 360.dp
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!shortViewport) Text("스크립트", color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 12.dp).semantics { heading() })
                TextButton(onClick = onBack,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("hud-close")) {
                    Text("HUD 닫기", color = Color.White)
                }
                Box {
                    TextButton(onClick = { languageMenu = true },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("hud-language-picker")
                            .semantics {
                                contentDescription = "표시할 통역 언어 선택"
                                stateDescription = selectedTranslationTag?.let(::relayCaptionLanguageLabel) ?: "전체"
                            }) {
                        Text("언어 · ${selectedTranslationTag?.let(::relayCaptionLanguageLabel) ?: "전체"}", color = HudTranslation)
                    }
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        DropdownMenuItem(text = { Text("전체") }, onClick = { selectedTranslationTag = null; languageMenu = false })
                        languages.forEach { tag ->
                            DropdownMenuItem(text = { Text(relayCaptionLanguageLabel(tag)) },
                                onClick = { selectedTranslationTag = tag; languageMenu = false })
                        }
                    }
                }
                TextButton(onClick = { follow = !follow },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("hud-follow-toggle")
                        .semantics { stateDescription = if (follow) "자동 따라가기 켜짐" else "자동 따라가기 꺼짐" }) {
                    Text(if (follow) "따라가기 중지" else "실시간 따라가기", color = HudSource)
                }
                TextButton(onClick = { controls = true },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("hud-display-settings")
                        .semantics { contentDescription = "HUD 표시 설정 열기" }) {
                    Text("설정", color = Color.White)
                }
            }
            if (rows.isEmpty() || (!sourceVisible && displayedTranslationLanguages.isEmpty())) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (!sourceVisible && displayedTranslationLanguages.isEmpty()) "표시할 언어가 없습니다. HUD를 닫고 원문이나 통역 언어를 선택하세요."
                    else "아직 표시할 스크립트가 없습니다. 원문·통역문을 수신하면 여기에 표시합니다.",
                    color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
            } else LazyColumn(state = list, reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("hud-transcript-list").pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        follow = false
                    }
                }, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                items(rows, key = { it.id }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sourceVisible) {
                            Text("원문 · ${group.sourceLanguageTag ?: sourceLanguageTag ?: "언어 미확인"} · ${if (group.sourceText.isBlank()) "미확인" else group.sourceStatusLabel}",
                                color = HudSource, style = MaterialTheme.typography.labelLarge)
                            Text(group.sourceText.ifBlank { "원문 미확인" }, color = HudSource, fontSize = size.sp, lineHeight = (size * 1.4f).sp)
                        }
                        val rowLanguages = if (group.alignment == RelayCaptionAlignment.SHARED_UTTERANCE) displayedTranslationLanguages
                            else (group.segments.mapNotNull { it.liveSegmentLanguage } + group.translations.keys)
                                .distinct().filter { it in displayedTranslationLanguages }
                        rowLanguages.forEach { tag ->
                            val segment = group.segmentFor(tag)
                            val translated = group.translations[tag]
                            Text("번역 · ${relayCaptionLanguageLabel(tag)} · ${segment?.liveOutputState?.label ?: if (segment?.isFinal == true) "완료" else "처리 중"}",
                                color = HudTranslation, style = MaterialTheme.typography.labelLarge)
                            Text(translated?.takeIf { it.isNotBlank() } ?: segment?.nativeMissingCaptionLabel(tag) ?: "통역 자막 대기",
                                color = if (translated.isNullOrBlank()) Color.LightGray else HudTranslation,
                                fontSize = size.sp, lineHeight = (size * 1.4f).sp)
                        }
                        group.alignmentNotice?.let { Text(it, color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
      }
    }
    if (controls) AlertDialog(onDismissRequest = { controls = false }, title = { Text("HUD 표시 설정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("표시 언어와 글씨만 변경합니다. 번역 요청이나 음성 방송 설정은 바뀌지 않습니다.",
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sourceVisible, enabled = languages.isNotEmpty(),
                        onClick = { showSource = !showSource; preferences.edit().putBoolean("show_source", showSource).apply() },
                        label = { Text("원문 보기") }, modifier = Modifier.sizeIn(minHeight = 48.dp))
                    FilterChip(selected = follow, onClick = { follow = !follow },
                        label = { Text(if (follow) "따라가기 켜짐" else "따라가기 꺼짐") },
                        modifier = Modifier.sizeIn(minHeight = 48.dp).testTag("hud-follow-setting"))
                }
                Text("글씨 크기", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24 to "작게", 32 to "보통", 44 to "크게").forEach { (value, label) ->
                        FilterChip(selected = size == value,
                            onClick = { size = value; preferences.edit().putInt("size", value).apply() },
                            label = { Text(label) }, modifier = Modifier.sizeIn(minHeight = 48.dp))
                    }
                }
                if (onReconnect != null) TextButton(onClick = onReconnect) { Text("통역 다시 연결") }
                recoveryMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }, confirmButton = { TextButton(onClick = { controls = false }) { Text("닫기") } })
}

internal fun transcriptHudLanguageTags(targetLanguageTags: List<String>, transcripts: List<TranslationTranscriptLine>): List<String> =
    (targetLanguageTags + transcripts.flatMap { it.translations.keys }).filter { it.isNotBlank() }.distinct().sorted()

internal fun transcriptHudSelection(selected: Set<String>, languages: List<String>): Set<String> =
    selected.intersect((languages + "source").toSet()).ifEmpty { setOf("source") }

internal fun transcriptHudVisibleGroups(groups: List<RelayCaptionGroup>, showSource: Boolean,
    displayedTranslationLanguages: List<String>): List<RelayCaptionGroup> = groups.filter { group ->
    showSource || displayedTranslationLanguages.any { language ->
        group.alignment == RelayCaptionAlignment.SHARED_UTTERANCE || group.segmentFor(language) != null
    }
}
