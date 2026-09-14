package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class FilePlaybackUiState(
    val entry: FileLibraryEntry,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isPrepared: Boolean = false,
    val speed: Float = 1f,
    val repeat: Boolean = false,
    val translationLanguageTag: String? = null,
    val errorMessage: String? = null,
    val isTranslating: Boolean = false,
    val statusMessage: String? = null,
    val languageOptions: Map<String, String> = FILE_LANGUAGE_OPTIONS,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FilePlaybackTranscriptScreen(
    state: FilePlaybackUiState,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRepeatChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onTranslationLanguageChange: (String?) -> Unit,
    onRelinkFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.entry
    val developerInfo = LocalDeveloperInfo.current
    var fullScreen by rememberSaveable(entry.id) { mutableStateOf(false) }
    var largeText by rememberSaveable { mutableStateOf(true) }
    var followPlayback by rememberSaveable(entry.id) { mutableStateOf(true) }
    var seekPreview by remember(entry.id) { mutableStateOf<Float?>(null) }
    var showPlaybackSettings by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val preferCompactChrome = fullScreen || configuration.screenHeightDp < 500 || configuration.fontScale > 1.4f
    val listState = rememberLazyListState()
    val orderedSegments = remember(entry.segments) { entry.segments.sortedBy { it.startMs } }
    val displayedSegments = remember(orderedSegments) { orderedSegments.asReversed() }
    val activeIndex = activeFileSpeechSegment(orderedSegments, state.positionMs)
    val lastStarted = orderedSegments.indexOfLast { it.startMs <= state.positionMs }.coerceAtLeast(0)
    val currentId = orderedSegments.getOrNull(activeIndex)?.id
    val translationIndices = remember(entry.segments) { entry.segments.mapIndexed { index, segment -> segment.id to index }.toMap() }
    BackHandler { if (fullScreen) fullScreen = false else onBack() }
    LaunchedEffect(entry.id, lastStarted, followPlayback, largeText, state.translationLanguageTag, fullScreen) {
        if (followPlayback && displayedSegments.isNotEmpty()) {
            listState.scrollToItem((displayedSegments.lastIndex - lastStarted).coerceAtLeast(0))
        }
    }

    Surface(modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = "파일 스크립트 재생" }) {
      BoxWithConstraints(Modifier.fillMaxSize()) {
        // Use the actual pane height, including split-screen and embedded short viewports.
        val shortViewport = maxHeight < 360.dp
        val compactChrome = preferCompactChrome || shortViewport
        Column(Modifier.fillMaxSize()) {
            // Transport controls remain fixed while only the script window scrolls.
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (fullScreen) fullScreen = false else onBack() }) {
                    Text(if (fullScreen) "재생 화면으로" else if (compactChrome) "파일 목록" else "파일 목록으로")
                }
                TextButton(onClick = { showPlaybackSettings = true }) { Text("재생 설정") }
                if (!shortViewport) TextButton(onClick = { fullScreen = !fullScreen }) { Text(if (fullScreen) "헤드업 해제" else "헤드업 화면") }
            }
            if (!compactChrome) {
                Text(entry.displayName, modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.translationLanguageTag == null,
                        onClick = { onTranslationLanguageChange(null) }, label = { Text("원문만") })
                    (state.languageOptions.keys.map(::fileBaseLanguage) + entry.translations.keys).distinct().forEach { tag ->
                        FilterChip(selected = state.translationLanguageTag == tag,
                            enabled = !state.isTranslating,
                            onClick = { onTranslationLanguageChange(tag) },
                            label = { Text("원문 + ${fileLanguageLabel(tag, state.languageOptions)}") })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!compactChrome) OutlinedButton(onClick = onPrevious) { Text("이전 파일") }
                Button(onClick = onPlayPause, enabled = state.isPrepared) { Text(if (state.isPlaying) "일시정지" else "재생") }
                OutlinedButton(onClick = onStop, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                    Text("중지", color = MaterialTheme.colorScheme.error)
                }
                if (!compactChrome) OutlinedButton(onClick = onNext) { Text("다음 파일") }
                if (!compactChrome) FilterChip(selected = state.repeat, onClick = { onRepeatChange(!state.repeat) }, label = { Text("반복") })
            }
            if (!compactChrome) {
              Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatFilePosition(seekPreview?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelMedium)
                Text(formatFilePosition(entry.durationMs), style = MaterialTheme.typography.labelMedium)
            }
            Slider(
                value = (seekPreview ?: state.positionMs.toFloat()).coerceIn(0f, entry.durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { seekPreview = it },
                onValueChangeFinished = { seekPreview?.let { onSeek(it.toLong()) }; seekPreview = null },
                valueRange = 0f..entry.durationMs.coerceAtLeast(1L).toFloat(), enabled = state.isPrepared && entry.durationMs > 0L,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).semantics { contentDescription = "음성 재생 위치" },
            )
            } else if (!shortViewport) {
                Text("${formatFilePosition(state.positionMs)} / ${formatFilePosition(entry.durationMs)} · ${state.speed}배",
                    Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium)
            }
            if (!compactChrome) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                        FilterChip(selected = state.speed == speed, onClick = { onSpeedChange(speed) },
                            label = { Text("${speed}배") })
                    }
                    FilterChip(selected = largeText, onClick = { largeText = !largeText }, label = { Text("큰 글씨") })
                }
            }
            state.errorMessage?.let {
              if (shortViewport) {
                TextButton(onClick = { showPlaybackSettings = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("오디오 확인 · 재생 설정", color = MaterialTheme.colorScheme.error)
                }
              } else {
                Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = if (compactChrome) 1 else 3)
                TextButton(onClick = onRelinkFile, modifier = Modifier.padding(horizontal = 8.dp)) { Text("원본 파일 다시 찾기") }
              }
            }
            if (!shortViewport) state.statusMessage?.let { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.labelMedium) }
            if (state.isTranslating) Text("선택한 언어로 번역하는 중입니다.", Modifier.padding(horizontal = 20.dp),
                style = MaterialTheme.typography.labelMedium)
            LazyColumn(
                state = listState, reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false); followPlayback = false }
                },
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = if (shortViewport) 4.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (shortViewport) 8.dp else 12.dp),
            ) {
                if (displayedSegments.isEmpty()) item { Text("저장된 스크립트가 없습니다.") }
                itemsIndexed(displayedSegments, key = { _, segment -> segment.id }) { _, segment ->
                    val active = currentId == segment.id
                    val originalIndex = translationIndices[segment.id]
                    val translated = state.translationLanguageTag?.let { tag ->
                        originalIndex?.let { entry.translations[tag]?.getOrNull(it) }
                    }
                    Surface(
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(if (shortViewport) 8.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(if (shortViewport) 4.dp else 8.dp)) {
                            Text("${formatFilePosition(segment.startMs)} · 원문" + if (active) " · 현재 재생" else "",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val wordRange = if (active) currentFileWordRange(segment, state.positionMs) else null
                            val highlightStyle = SpanStyle(
                                background = MaterialTheme.colorScheme.primary,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            val annotated = buildAnnotatedString {
                                append(segment.text)
                                wordRange?.let { range -> addStyle(highlightStyle, range.first, range.last + 1) }
                            }
                            SelectionContainer { Text(annotated,
                                style = if (largeText) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium) }
                            if (developerInfo) Text(when {
                                segment.timingEstimated -> "시각 추정 · 원본을 들으며 확인하세요"
                                segment.words.isEmpty() -> "문장 단위 표시 · 단어 시각 없음"
                                else -> "단어 시각 기준 표시"
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (state.translationLanguageTag != null) {
                                Text("번역 · ${fileLanguageLabel(state.translationLanguageTag, state.languageOptions)}",
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                SelectionContainer { Text(translated ?: "이 문장의 번역이 없습니다.",
                                    style = if (largeText) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
            }
            if (!shortViewport) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { followPlayback = true }, enabled = !followPlayback,
                    modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp)) {
                    Text(if (compactChrome) { if (followPlayback) "따라가는 중" else "따라가기" }
                        else if (followPlayback) "현재 음성 따라가는 중" else "현재 음성 따라가기")
                }
                TextButton(onClick = onRelinkFile) { Text(if (compactChrome) "파일 찾기" else "원본 파일 찾기") }
            }
        }
      }
    }
    if (showPlaybackSettings) {
        AlertDialog(onDismissRequest = { showPlaybackSettings = false }, title = { Text("재생 설정") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { fullScreen = !fullScreen; showPlaybackSettings = false }) {
                            Text(if (fullScreen) "헤드업 해제" else "헤드업 화면")
                        }
                        TextButton(onClick = { followPlayback = true; showPlaybackSettings = false }, enabled = !followPlayback) {
                            Text(if (followPlayback) "현재 음성 따라가는 중" else "현재 음성 따라가기")
                        }
                        TextButton(onClick = { showPlaybackSettings = false; onRelinkFile() }) { Text("원본 파일 찾기") }
                    }
                    Text("${formatFilePosition(seekPreview?.toLong() ?: state.positionMs)} / ${formatFilePosition(entry.durationMs)}")
                    Slider(value = (seekPreview ?: state.positionMs.toFloat()).coerceIn(0f, entry.durationMs.coerceAtLeast(1L).toFloat()),
                        onValueChange = { seekPreview = it },
                        onValueChangeFinished = { seekPreview?.let { onSeek(it.toLong()) }; seekPreview = null },
                        valueRange = 0f..entry.durationMs.coerceAtLeast(1L).toFloat(), enabled = state.isPrepared && entry.durationMs > 0L,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "음성 재생 위치" })
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPrevious) { Text("이전 파일") }
                        OutlinedButton(onClick = onNext) { Text("다음 파일") }
                        FilterChip(selected = state.repeat, onClick = { onRepeatChange(!state.repeat) }, label = { Text("반복") })
                    }
                    Text("번역 언어", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.translationLanguageTag == null,
                            onClick = { onTranslationLanguageChange(null) }, label = { Text("원문만") })
                        state.languageOptions.keys.map(::fileBaseLanguage).distinct().forEach { tag ->
                            FilterChip(selected = state.translationLanguageTag == tag, enabled = !state.isTranslating,
                                onClick = { onTranslationLanguageChange(tag) }, label = { Text(fileLanguageLabel(tag, state.languageOptions)) })
                        }
                    }
                    Text("재생 속도", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                            FilterChip(selected = state.speed == speed, onClick = { onSpeedChange(speed) }, label = { Text("${speed}배") })
                        }
                    }
                    FilterChip(selected = largeText, onClick = { largeText = !largeText }, label = { Text("큰 글씨") })
                    if (entry.qualityNotes.isNotEmpty()) {
                        Text("자동 검사 확인 사항", fontWeight = FontWeight.SemiBold)
                        entry.qualityNotes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { showPlaybackSettings = false }) { Text("닫기") } })
    }
}

internal fun activeFileSpeechSegment(segments: List<FileSpeechSegment>, positionMs: Long): Int =
    segments.indexOfLast { positionMs >= it.startMs && positionMs < it.endMs }

/** Match the engine's word text to the exact preserved source; never invent token boundaries. */
internal fun currentFileWordRange(segment: FileSpeechSegment, positionMs: Long): IntRange? {
    if (positionMs < segment.startMs || positionMs >= segment.endMs) return null
    val words = segment.words
    val active = words.indices.lastOrNull { index ->
        val word = words[index]
        val end = word.endMs ?: words.getOrNull(index + 1)?.startMs ?: segment.endMs
        positionMs >= word.startMs && positionMs < end
    } ?: return null
    var cursor = 0
    for (index in 0..active) {
        val text = words[index].text
        if (text.isEmpty()) return null
        val start = segment.text.indexOf(text, startIndex = cursor, ignoreCase = true)
        if (start < 0) return null
        if (index == active) return start until start + text.length
        cursor = start + text.length
    }
    return null
}
