package app.guidecast.transmitter

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class CorrectionSelection(
    val original: String,
    val language: String,
    val profile: String,
    val sourceKey: String,
    val previous: SpeechCorrectionEntry?,
)

/** Only an explicit operator action writes a separate record; it cannot access the TTS queue. */
@Composable
internal fun SpeechCorrectionAction(
    line: TranslationTranscriptLine,
    languageTag: String,
    onDark: Boolean = false,
) {
    if (!line.isFinal) return
    val repository = (LocalContext.current.applicationContext as GuideCastApplication).speechCorrections
    val revision by repository.revision.collectAsState()
    val profile by repository.activeProfile.collectAsState()
    val sourceLanguage = line.sourceLanguageTag ?: languageTag
    val candidates = remember(revision, profile, line.sourceText, sourceLanguage) {
        repository.correctionCandidates(line.sourceText, sourceLanguage)
    }
    var selection by remember { mutableStateOf<CorrectionSelection?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val textColor = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    candidates.firstOrNull()?.let {
        Text("등록된 교정 후보 · ${it.correctedText}", color = textColor,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
    }
    TextButton(
        modifier = Modifier.sizeIn(minHeight = 48.dp),
        onClick = {
            // Capture this final source, not a moving reference to the newest live transcript.
            selection = CorrectionSelection(line.sourceText, sourceLanguage, profile,
                "${line.capturedAtElapsedRealtimeNanos}:${line.sequence}", candidates.firstOrNull())
            error = null
        },
    ) { Text("교정 선택", color = textColor) }
    selection?.let { selected ->
        SpeechCorrectionEditor(
            recognizedText = selected.original,
            initialCorrectedText = selected.previous?.correctedText ?: selected.original,
            initialHint = selected.previous?.hint,
            profile = selected.profile,
            languageLabel = selected.language,
            busy = busy,
            error = error,
            onDismiss = { if (!busy) selection = null },
            onSave = { corrected, hint ->
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            repository.save(SpeechCorrectionDraft(
                                profile = selected.profile, languageTag = selected.language,
                                recognizedText = selected.original, correctedText = corrected,
                                hint = hint, enabled = true, sourceKey = selected.sourceKey,
                            ), selected.previous?.id)
                            selection = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (invalid: SpeechCorrectionValidationException) {
                            error = invalid.message
                        } catch (_: Exception) {
                            error = "교정 기록을 저장하지 못했습니다. 저장 공간을 확인한 뒤 다시 시도하세요. 방송은 계속됩니다."
                        } finally { busy = false }
                    }
                }
            },
        )
    }
}
