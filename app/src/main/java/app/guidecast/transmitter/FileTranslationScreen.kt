package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

internal val FILE_LANGUAGE_OPTIONS = linkedMapOf(
    "ko-KR" to "한국어", "en-US" to "영어", "ja-JP" to "일본어", "zh-CN" to "중국어",
    "es-ES" to "스페인어", "fr-FR" to "프랑스어", "de-DE" to "독일어", "vi-VN" to "베트남어",
)

data class FileTranslationUiState(
    val library: List<FileLibraryEntry> = emptyList(),
    val selectedFileName: String? = null,
    val selectedFileSha256: String? = null,
    val sourceLanguageTag: String? = null,
    val detectedLanguageTag: String? = null,
    val languageNotice: String? = null,
    val targetLanguageTags: Set<String> = emptySet(),
    val isConverting: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Float? = null,
    val progressMessage: String = "",
    val errorMessage: String? = null,
    val languageOptions: Map<String, String> = FILE_LANGUAGE_OPTIONS,
    val translationEngine: FileTranslationEngine = FileTranslationEngine.MLKIT,
    val unavailableReason: String? = null,
    val selectedFiles: List<FileConversionItem> = emptyList(),
    val automaticLanguageSupported: Boolean = true,
    val fileTranscriptionSupported: Boolean = true,
    val automaticLanguageUnavailableReason: String? = null,
    val sourceLanguageUnavailableReason: String? = null,
    val recognitionSupportNotice: String? = null,
)

@Composable
internal fun FileTranslationScreen(
    state: FileTranslationUiState,
    onChooseFile: () -> Unit,
    onSourceLanguageChange: (String?) -> Unit,
    onTargetLanguageToggle: (String) -> Unit,
    onTranslationEngineChange: (FileTranslationEngine) -> Unit,
    onConvert: () -> Unit,
    onCancel: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseFiles: () -> Unit = onChooseFile,
    onChooseFolder: () -> Unit = onChooseFile,
    onRetryFailed: () -> Unit = {},
    onRetryFile: (String) -> Unit = {},
) {
    BackHandler(onBack = onBack)
    val developerInfo = LocalDeveloperInfo.current
    var languageExpanded by remember { mutableStateOf(false) }
    var deleteEntry by remember { mutableStateOf<FileLibraryEntry?>(null) }
    var showAllSelected by remember { mutableStateOf(false) }
    val busy = state.isConverting || state.isLoading
    val queued = state.selectedFiles
    val readyCount = queued.count { it.status == FileConversionStatus.READY || fileConversionCanRetry(it.status) }
    val retryCount = queued.count { fileConversionCanRetry(it.status) }
    val conversionPreflight = fileConversionPreflight(state)
    Surface(modifier.fillMaxSize().safeDrawingPadding().semantics { paneTitle = MCastService.FILES.title }) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) { Text("이전 화면으로") }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(MCastService.FILES.title, style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("음성 파일을 문장으로 변환하고, 번역을 나란히 보며 원본 음성을 재생합니다.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("1. 음성 파일 선택", fontWeight = FontWeight.SemiBold)
                            Text(if (queued.isNotEmpty()) "선택한 파일 ${queued.size}개" else state.selectedFileName ?: "아직 선택한 파일이 없습니다.",
                                style = MaterialTheme.typography.bodyLarge)
                            OutlinedButton(onClick = onChooseFile, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                                Text(if (state.selectedFileName == null) "음성 파일 선택" else "다른 파일 선택")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onChooseFiles, enabled = !busy, modifier = Modifier.weight(1f)) { Text("여러 파일 선택") }
                                OutlinedButton(onClick = onChooseFolder, enabled = !busy, modifier = Modifier.weight(1f)) { Text("폴더 선택") }
                            }
                            Text("스크립트와 번역은 파일 보관함에 최대 1,000개 파일까지 저장합니다. 방송 기록 보관함과 별개입니다.",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (queued.isNotEmpty()) {
                    item {
                        Text("저장 ${queued.count { it.status == FileConversionStatus.SAVED }}개 · " +
                            "확인 필요 ${retryCount}개 · 변환 대상 ${readyCount}개",
                            style = MaterialTheme.typography.labelMedium)
                        if (retryCount > 0) OutlinedButton(onClick = onRetryFailed,
                            enabled = !busy && conversionPreflight == null, modifier = Modifier.fillMaxWidth()) {
                            Text("실패 항목 재작업 (${retryCount}개)")
                        }
                    }
                    itemsIndexed(if (showAllSelected) queued else queued.take(5), key = { index, file -> "$index:${file.uri}" }) { _, file ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(file.displayName, fontWeight = FontWeight.Medium)
                            Text(fileConversionStatusLabel(file.status), style = MaterialTheme.typography.labelMedium,
                                color = if (file.status == FileConversionStatus.FAILED) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                            file.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            if (fileConversionCanRetry(file.status)) TextButton(onClick = { onRetryFile(file.uri) },
                                enabled = !busy && conversionPreflight == null) { Text("이 파일 재작업") }
                        }
                    }
                    if (queued.size > 5) item {
                        TextButton(onClick = { showAllSelected = !showAllSelected }) {
                            Text(if (showAllSelected) "선택 목록 접기" else "선택한 ${queued.size}개 파일 모두 보기")
                        }
                    }
                }
                item {
                    Text("2. 원문 언어", fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(onClick = { languageExpanded = true }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(fileSourceLanguageChoiceLabel(state))
                        }
                        DropdownMenu(expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                            DropdownMenuItem(text = { Text(if (state.automaticLanguageSupported) "자동 감지 (기본)" else "자동 감지 · 현재 기기에서 사용 불가") },
                                enabled = state.automaticLanguageSupported,
                                onClick = { onSourceLanguageChange(null); languageExpanded = false })
                            state.languageOptions.forEach { (tag, label) ->
                                DropdownMenuItem(text = { Text(label) },
                                    onClick = { onSourceLanguageChange(tag); languageExpanded = false })
                            }
                        }
                    }
                    state.detectedLanguageTag?.let {
                        Text("감지된 언어: ${fileLanguageLabel(it, state.languageOptions)}",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(state.recognitionSupportNotice ?: if (!state.automaticLanguageSupported)
                        state.automaticLanguageUnavailableReason ?: "기기 자동 감지를 사용할 수 없습니다. 원문 언어를 직접 선택하면 지원되는 앱 음성 인식 모델로 변환합니다."
                        else "자동 감지는 기기의 파일 음성 인식을 사용합니다. 감지 결과가 나오지 않으면 원문 언어를 직접 선택하세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.languageNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    Text("3. 번역할 언어", fontWeight = FontWeight.SemiBold)
                    Text("이 설정을 선택한 모든 파일에 적용합니다. 번역 언어를 선택하지 않으면 원문만 저장합니다.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.languageOptions.entries.distinctBy { fileBaseLanguage(it.key) }.forEach { (tag, label) ->
                            val target = fileBaseLanguage(tag)
                            FilterChip(selected = target in state.targetLanguageTags, enabled = !busy,
                                onClick = { onTargetLanguageToggle(target) }, label = { Text(label) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = state.translationEngine == FileTranslationEngine.MLKIT,
                            enabled = !busy, onClick = { onTranslationEngineChange(FileTranslationEngine.MLKIT) },
                            label = { Text(if (developerInfo) "Google ML Kit" else "기본 번역") })
                        FilterChip(selected = state.translationEngine == FileTranslationEngine.GEMMA,
                            enabled = !busy, onClick = { onTranslationEngineChange(FileTranslationEngine.GEMMA) },
                            label = { Text(if (developerInfo) "ML Kit + AI 검토" else "번역 + 추가 검토") })
                        FilterChip(selected = state.translationEngine == FileTranslationEngine.API,
                            enabled = !busy, onClick = { onTranslationEngineChange(FileTranslationEngine.API) }, label = { Text("설정한 API") })
                    }
                    Text("준비된 모델과 기기 성능에 따라 처리 시간과 지원 언어가 달라집니다.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    conversionPreflight?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    state.errorMessage?.takeUnless { it == conversionPreflight }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (busy) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(state.progressMessage.ifBlank { if (state.isLoading) "파일을 확인하는 중입니다." else "음성을 변환하는 중입니다." })
                            val progress = state.progress
                            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
                                Text("파일 처리 취소", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Text("일반 변환은 현재 설정으로 번역을 갱신합니다. 실패 항목 재작업은 완료된 언어와 문장을 보존하며 이어서 처리합니다.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onConvert,
                            enabled = (readyCount > 0 || (queued.isEmpty() && state.selectedFileName != null)) && conversionPreflight == null,
                            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp)) {
                            Text(if (queued.isNotEmpty()) "${readyCount}개 파일 순서대로 변환" else "변환하고 보관함에 저장")
                        }
                    }
                }
                item {
                    Text("저장된 파일 스크립트 (${state.library.size})", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() })
                    Text("자동 검사는 누락이나 시각 정보를 점검합니다. 인식·번역 정확도는 원본을 들으며 확인하세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.library.isEmpty()) item { Text("변환한 파일이 아직 없습니다.") }
                items(state.library.sortedByDescending { it.createdAtMillis }, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(entry.displayName, fontWeight = FontWeight.SemiBold)
                            Text("${formatArchiveSessionTime(entry.createdAtMillis)} · ${formatFilePosition(entry.durationMs)}",
                                style = MaterialTheme.typography.labelMedium)
                            Text(entry.sourceLanguageTag?.let { fileLanguageLabel(it, state.languageOptions) } ?: "원문 언어 확인 필요",
                                style = MaterialTheme.typography.labelMedium)
                            if (entry.qualityNotes.isNotEmpty()) Text("자동 검사 · 확인 사항 ${entry.qualityNotes.size}개",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onOpenEntry(entry.id) }, enabled = !busy,
                                    modifier = Modifier.weight(1f)) { Text("스크립트 재생") }
                                TextButton(onClick = { deleteEntry = entry }, enabled = !busy) {
                                    Text("삭제", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null }, title = { Text("파일 스크립트 삭제") },
            text = { Text("${entry.displayName}의 저장된 원문과 번역을 삭제합니다. 원본 음성 파일은 유지합니다.") },
            confirmButton = { TextButton(onClick = { onDeleteEntry(entry.id); deleteEntry = null }) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("취소") } })
    }
}

internal fun fileConversionStatusLabel(status: FileConversionStatus): String = when (status) {
    FileConversionStatus.READY -> "변환 대기"
    FileConversionStatus.READING -> "파일 확인 중"
    FileConversionStatus.CONVERTING -> "변환 중"
    FileConversionStatus.SAVED -> "저장 완료"
    FileConversionStatus.PARTIAL -> "원문 저장됨 · 일부 번역 확인 필요"
    FileConversionStatus.FAILED -> "확인 필요 · 변환 실패"
    FileConversionStatus.CANCELLED -> "취소됨"
}

internal fun fileConversionCanRetry(status: FileConversionStatus): Boolean = status in setOf(
    FileConversionStatus.PARTIAL, FileConversionStatus.FAILED, FileConversionStatus.CANCELLED,
)

internal fun fileBaseLanguage(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)

/** Checked by UI and VM before opening/reading audio bytes or computing its SHA-256. */
internal fun fileConversionPreflight(state: FileTranslationUiState): String? = listOfNotNull(
    if (!state.fileTranscriptionSupported) "이 기기에 사용할 수 있는 파일 음성 인식기가 없습니다. 저장된 스크립트 재생은 계속 사용할 수 있습니다." else null,
    state.unavailableReason,
    state.sourceLanguageUnavailableReason,
    if (state.fileTranscriptionSupported && !state.automaticLanguageSupported && state.sourceLanguageTag == null)
        "변환 전에 원문 언어를 직접 선택하세요. 자동 감지 없이 지원되는 앱 모델로 변환할 수 있습니다." else null,
).distinct().joinToString("\n").ifBlank { null }

internal fun fileSourceLanguageChoiceLabel(state: FileTranslationUiState): String =
    state.sourceLanguageTag?.let { fileLanguageLabel(it, state.languageOptions) }
        ?: if (state.automaticLanguageSupported) "자동 감지 (기본)" else "원문 언어를 선택하세요"

internal fun fileLanguageLabel(tag: String, options: Map<String, String> = FILE_LANGUAGE_OPTIONS): String =
    options[tag] ?: options.entries.firstOrNull { fileBaseLanguage(it.key) == fileBaseLanguage(tag) }?.value ?: tag

internal fun formatFilePosition(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    return if (seconds >= 3_600L) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3_600L, seconds / 60L % 60L, seconds % 60L)
    else "%d:%02d".format(Locale.ROOT, seconds / 60L, seconds % 60L)
}
