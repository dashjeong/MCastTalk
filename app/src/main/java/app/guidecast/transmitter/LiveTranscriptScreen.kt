package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Presentation-only navigation. Opening, scrolling and returning never change input/broadcast. */
@Composable
internal fun LiveTranscriptScreen(
    transcripts: List<TranslationTranscriptLine>,
    sourceLanguageTag: String,
    targetLanguageTags: List<String>,
    statusText: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var followNewest by rememberSaveable { mutableStateOf(true) }
    var selectedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var largeText by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val displayed = remember(transcripts) { liveTranscriptDisplayLines(transcripts) }
    val languages = remember(targetLanguageTags, transcripts) {
        (targetLanguageTags + transcripts.flatMap { it.translations.keys }).distinct().sorted()
    }

    LaunchedEffect(languages) {
        if (selectedLanguage != null && selectedLanguage !in languages) selectedLanguage = null
    }

    LaunchedEffect(displayed, followNewest, selectedLanguage, largeText) {
        if (followNewest && displayed.isNotEmpty()) listState.scrollToItem(0)
    }

    Surface(modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = "실시간 스크립트 전체 화면" }) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onBack) { Text("운영 화면으로") }
                FilterChip(selected = largeText, onClick = { largeText = !largeText },
                    label = { Text("큰 글씨") })
            }
            Text("실시간 스크립트", modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(statusText, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = selectedLanguage == null, onClick = { selectedLanguage = null },
                    label = { Text("원문 + 전체 번역") })
                languages.forEach { language ->
                    FilterChip(selected = selectedLanguage == language,
                        onClick = { selectedLanguage = language },
                        label = { Text("원문 + ${language.uppercase(Locale.ROOT)}") })
                }
            }
            if (displayed.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text("음성이 인식되면 원문과 번역이 여기에 표시됩니다.",
                        style = MaterialTheme.typography.titleMedium)
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                        awaitEachGesture {
                            // Observe the first touch without consuming it: text selection and
                            // manual scrolling remain available immediately.
                            awaitFirstDown(requireUnconsumed = false)
                            followNewest = false
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(displayed, key = { it.sequence }) { line ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.medium) {
                            Column(Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "원문 · ${line.sourceLanguageTag ?: sourceLanguageTag}" +
                                        if (line.isFinal) " · 확정" else " · 인식 중",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SelectionContainer {
                                    Text(line.sourceText,
                                        style = if (largeText) MaterialTheme.typography.headlineSmall
                                        else MaterialTheme.typography.titleMedium)
                                }
                                val translations = line.translations.filterKeys {
                                    selectedLanguage == null || selectedLanguage == it
                                }
                                translations.forEach { (language, translation) ->
                                    Text("번역 · ${language.uppercase(Locale.ROOT)}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    SelectionContainer {
                                        Text(translation,
                                            style = if (largeText) MaterialTheme.typography.headlineSmall
                                            else MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium)
                                    }
                                }
                                if (translations.isEmpty() && targetLanguageTags.isNotEmpty()) {
                                    Text(if (line.isFinal) "번역 결과 대기" else "문장 인식 중",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    if (followNewest) "최신 내용을 따라가는 중 · 화면을 터치하면 이전 내용을 읽을 수 있습니다."
                    else "이전 내용 읽는 중 · 새 문장은 계속 쌓입니다.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { followNewest = true }, enabled = !followNewest,
                    modifier = Modifier.fillMaxWidth()) { Text("실시간 따라가기") }
                Text("최근 100개 문장 · 이전 방송은 스크립트 보관함에서 확인",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Reverse layout puts index zero at the bottom; source/translation updates share one row. */
internal fun liveTranscriptDisplayLines(lines: List<TranslationTranscriptLine>): List<TranslationTranscriptLine> =
    lines.associateBy { it.sequence }.values.sortedByDescending { it.sequence }.take(100)
