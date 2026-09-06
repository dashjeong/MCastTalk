package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CorrectionEditorRequest(
    val entry: SpeechCorrectionEntry?,
    val profile: String,
    val languageTag: String,
    val recognizedText: String,
    val correctedText: String,
    val hint: String?,
    val sourceKey: String?,
)

private data class DirectRegistrationRequest(
    val profile: String,
    val languageTag: String,
)

private data class CorrectionImportPreview(
    val profile: String,
    val drafts: List<SpeechCorrectionDraft>,
)

private sealed interface CorrectionUndo {
    val description: String

    data class Save(
        val saved: SpeechCorrectionEntry,
        val previous: SpeechCorrectionEntry?,
    ) : CorrectionUndo {
        override val description = "저장한 교정 되돌리기"
    }

    data class Delete(val entry: SpeechCorrectionEntry) : CorrectionUndo {
        override val description = "삭제한 교정 복원"
    }

    data class Toggle(val entry: SpeechCorrectionEntry) : CorrectionUndo {
        override val description = "적용 상태 되돌리기"
    }
}

@Composable
internal fun SpeechCorrectionScreen(
    modifier: Modifier = Modifier,
    initialLanguageTag: String = "ko",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val repository = (context.applicationContext as GuideCastApplication).speechCorrections
    val scope = rememberCoroutineScope()
    val revision by repository.revision.collectAsState()
    val activeProfile by repository.activeProfile.collectAsState()
    var languageTag by rememberSaveable(initialLanguageTag) {
        mutableStateOf(resolveSourceLanguageTag(initialLanguageTag))
    }
    var query by rememberSaveable { mutableStateOf("") }
    var profiles by remember { mutableStateOf(listOf(SpeechCorrectionRepository.DEFAULT_PROFILE)) }
    var rows by remember { mutableStateOf(emptyList<SpeechCorrectionEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var operationGeneration by remember { mutableIntStateOf(0) }
    var queryGeneration by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmationError by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<CorrectionUndo?>(null) }
    var editor by remember { mutableStateOf<CorrectionEditorRequest?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var directRegistration by remember { mutableStateOf<DirectRegistrationRequest?>(null) }
    var deleteConfirmation by remember { mutableStateOf<SpeechCorrectionEntry?>(null) }
    var clearConfirmation by remember { mutableStateOf<String?>(null) }
    var newProfileDialog by remember { mutableStateOf(false) }
    var exportPrivacyProfile by remember { mutableStateOf<String?>(null) }
    var exportRequestProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var importRequestProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<CorrectionImportPreview?>(null) }

    BackHandler(onBack = onBack)

    fun launchOperation(
        fallbackMessage: String,
        onFailure: (String) -> Unit = { message = it },
        operation: suspend () -> Unit,
    ) {
        if (busy) return
        val generation = ++operationGeneration
        busy = true
        message = null
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(correctionFailureMessage(fallbackMessage, failure))
            } finally {
                if (generation == operationGeneration) busy = false
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val capturedProfile = exportRequestProfile
        exportRequestProfile = null
        if (uri != null && capturedProfile != null) {
            launchOperation("파일을 내보내지 못했습니다.") {
                val entries = repository.list(capturedProfile, null, "")
                withContext(Dispatchers.IO) {
                    val encoded = SpeechCorrectionArchive.encode(entries)
                    requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                        output.writer(Charsets.UTF_8).use { writer -> writer.write(encoded) }
                    }
                }
                message = "‘$capturedProfile’ 프로필의 교정 기록을 저장했습니다."
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val capturedProfile = importRequestProfile
        importRequestProfile = null
        if (uri != null && capturedProfile != null) {
            launchOperation("가져오기 파일을 확인하지 못했습니다.") {
                val validated = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        SpeechCorrectionArchive.decode(input, capturedProfile)
                    }
                }
                importPreview = CorrectionImportPreview(capturedProfile, validated)
            }
        }
    }

    LaunchedEffect(repository) {
        try {
            repository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rows = emptyList()
            message = "저장된 교정 기록을 불러오지 못했습니다. 방송과 음성인식은 계속 사용할 수 있습니다."
        }
    }

    LaunchedEffect(activeProfile, languageTag, query, revision) {
        val generation = ++queryGeneration
        loading = true
        rows = emptyList()
        try {
            delay(180)
            val nextRows = repository.list(activeProfile, languageTag, query)
            val nextProfiles = repository.profiles()
            if (generation == queryGeneration) {
                rows = nextRows
                profiles = (nextProfiles + activeProfile).distinct().sorted()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (generation == queryGeneration) {
                rows = emptyList()
                message = correctionFailureMessage("교정 기록을 조회하지 못했습니다.", failure)
            }
        } finally {
            if (generation == queryGeneration) loading = false
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .semantics { paneTitle = "인식 학습·보정" },
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(
                onClick = onBack,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("← 설정으로") }
            Text("인식 학습·보정", style = MaterialTheme.typography.headlineSmall)
            Text(
                "교정 기록과 인식 힌트를 관리합니다. 음성 모델 자체를 재학습하지 않습니다.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("동작 범위", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "이미 확정·송출된 원문, 번역문과 음성은 바꾸거나 다시 보내지 않습니다. " +
                            "교정문을 자동 적용하지 않으며 원음 저장, 클라우드 전송, 모델 훈련을 추가하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Android API 33 이상에서는 활성 힌트를 다음 인식 요청에 보낼 수 있지만 " +
                            "단말의 인식 서비스가 무시할 수 있습니다. 현재 Moonshine Tiny는 힌트를 지원하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "저장 한도 · 전체 500개 · 프로필/언어별 활성 힌트 32개 · 힌트 1개 80코드포인트 · " +
                            "프로필/언어별 활성 힌트 합계 1,000코드포인트",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        item {
            Text("현장 프로필", style = MaterialTheme.typography.titleMedium)
            Text(
                "휴대전화나 발화자를 자동 인식하지 않습니다. 운영자가 사용할 프로필을 직접 선택합니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            CorrectionProfileChoice(
                selected = activeProfile,
                profiles = profiles,
                enabled = !busy,
                onSelect = { selected ->
                    val capturedProfile = selected
                    launchOperation("프로필을 선택하지 못했습니다.") {
                        repository.selectProfile(capturedProfile)
                        query = ""
                        message = "‘$capturedProfile’ 프로필을 선택했습니다. 다음 인식부터 참고됩니다."
                    }
                },
            )
            OutlinedButton(
                onClick = { confirmationError = null; newProfileDialog = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("새 프로필 이름 직접 입력") }
        }

        item {
            CorrectionLanguageChoice(
                selected = languageTag,
                enabled = !busy,
                onSelect = { selected -> languageTag = selected; query = "" },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("인식문·교정문·힌트 검색") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${languageTag.sourceLanguageDisplayName()} · 검색 결과 ${rows.size}개",
                style = MaterialTheme.typography.labelMedium,
            )
        }

        item {
            val capturedProfile = activeProfile
            val capturedLanguage = languageTag
            Button(
                onClick = {
                    directRegistration = DirectRegistrationRequest(capturedProfile, capturedLanguage)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("교정 직접 등록") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportPrivacyProfile = capturedProfile },
                    enabled = !busy && !loading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("프로필 내보내기") }
                OutlinedButton(
                    onClick = {
                        importRequestProfile = capturedProfile
                        importer.launch(arrayOf("application/json", "text/json", "application/octet-stream"))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("프로필 가져오기") }
            }
            OutlinedButton(
                onClick = { confirmationError = null; clearConfirmation = capturedProfile },
                enabled = !busy,
                colors = destructiveOutlinedButtonColors(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("이 프로필 전체 초기화") }
        }

        item {
            if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            undo?.let { action ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(action.description, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                launchOperation("되돌리지 못했습니다.") {
                                    when (action) {
                                        is CorrectionUndo.Save -> {
                                            val previous = action.previous
                                            if (previous == null) {
                                                checkNotNull(repository.delete(action.saved.id))
                                            } else {
                                                repository.save(previous.toCorrectionDraft(), previous.id)
                                            }
                                        }
                                        is CorrectionUndo.Delete -> repository.restore(action.entry)
                                        is CorrectionUndo.Toggle -> checkNotNull(
                                            repository.setEnabled(action.entry.id, action.entry.enabled),
                                        )
                                    }
                                    undo = null
                                    message = "이전 상태로 되돌렸습니다."
                                }
                            },
                        ) { Text("되돌리기") }
                    }
                }
            }
        }

        items(rows, key = SpeechCorrectionEntry::id) { entry ->
            SpeechCorrectionRow(
                entry = entry,
                busy = busy,
                onEdit = {
                    editorError = null
                    editor = CorrectionEditorRequest(
                        entry = entry,
                        profile = entry.profile,
                        languageTag = entry.languageTag,
                        recognizedText = entry.recognizedText,
                        correctedText = entry.correctedText,
                        hint = entry.hint,
                        sourceKey = entry.sourceKey,
                    )
                },
                onToggle = {
                    val captured = entry
                    launchOperation("적용 상태를 바꾸지 못했습니다.") {
                        checkNotNull(repository.setEnabled(captured.id, !captured.enabled))
                        undo = CorrectionUndo.Toggle(captured)
                        message = if (captured.enabled) {
                            "교정 기록을 일시 해제했습니다."
                        } else {
                            "교정 기록을 다시 활성화했습니다. 다음 인식부터 참고됩니다."
                        }
                    }
                },
                onDelete = { confirmationError = null; deleteConfirmation = entry },
            )
        }

        item {
            if (rows.isEmpty() && !loading) {
                Text(
                    if (query.isBlank()) "이 언어에 저장된 교정 기록이 없습니다."
                    else "검색 결과가 없습니다. 목록을 비운 상태로 표시합니다.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    directRegistration?.let { request ->
        DirectRecognitionDialog(
            busy = busy,
            onDismiss = { directRegistration = null },
            onContinue = { recognizedText ->
                directRegistration = null
                editorError = null
                editor = CorrectionEditorRequest(
                    entry = null,
                    profile = request.profile,
                    languageTag = request.languageTag,
                    recognizedText = recognizedText,
                    correctedText = recognizedText,
                    hint = null,
                    sourceKey = null,
                )
            },
        )
    }

    editor?.let { request ->
        SpeechCorrectionEditor(
            recognizedText = request.recognizedText,
            initialCorrectedText = request.correctedText,
            initialHint = request.hint,
            profile = request.profile,
            languageLabel = request.languageTag.sourceLanguageDisplayName(),
            busy = busy,
            error = editorError,
            onDismiss = { if (!busy) { editor = null; editorError = null } },
            onSave = { correctedText, hint ->
                val captured = request
                launchOperation(
                    fallbackMessage = "교정 기록을 저장하지 못했습니다.",
                    onFailure = { editorError = it },
                ) {
                    val draft = SpeechCorrectionDraft(
                        profile = captured.profile,
                        languageTag = captured.languageTag,
                        recognizedText = captured.recognizedText,
                        correctedText = correctedText,
                        hint = hint,
                        enabled = captured.entry?.enabled ?: true,
                        sourceKey = captured.sourceKey,
                    )
                    SpeechCorrectionValidation.draft(draft)
                    val saved = repository.save(draft, captured.entry?.id)
                    undo = CorrectionUndo.Save(saved, captured.entry)
                    editor = null
                    editorError = null
                    message = "교정 기록을 저장했습니다. 이미 송출된 내용은 바뀌지 않습니다."
                }
            },
        )
    }

    deleteConfirmation?.let { entry ->
        AlertDialog(
            onDismissRequest = { if (!busy) { deleteConfirmation = null; confirmationError = null } },
            title = { Text("교정 기록 삭제") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("‘${entry.recognizedText.take(60)}’ 교정 기록을 삭제할까요? 삭제 직후 되돌릴 수 있습니다.")
                    confirmationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        val captured = entry
                        launchOperation(
                            fallbackMessage = "교정 기록을 삭제하지 못했습니다.",
                            onFailure = { confirmationError = it },
                        ) {
                            val deleted = checkNotNull(repository.delete(captured.id))
                            undo = CorrectionUndo.Delete(deleted)
                            deleteConfirmation = null
                            confirmationError = null
                            message = "교정 기록을 삭제했습니다."
                        }
                    },
                ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { deleteConfirmation = null; confirmationError = null },
                ) { Text("취소") }
            },
        )
    }

    clearConfirmation?.let { profile ->
        AlertDialog(
            onDismissRequest = { if (!busy) { clearConfirmation = null; confirmationError = null } },
            title = { Text("‘$profile’ 프로필 전체 초기화") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "이 프로필의 모든 언어 교정 기록을 영구 삭제합니다. 다른 프로필은 유지되지만, " +
                            "이 작업은 되돌릴 수 없습니다. 계속할까요?",
                    )
                    confirmationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        val capturedProfile = profile
                        launchOperation(
                            fallbackMessage = "프로필을 초기화하지 못했습니다.",
                            onFailure = { confirmationError = it },
                        ) {
                            repository.clear(capturedProfile)
                            undo = null
                            clearConfirmation = null
                            confirmationError = null
                            message = "‘$capturedProfile’ 프로필을 초기화했습니다. 다른 프로필은 유지했습니다."
                        }
                    },
                ) { Text("영구 삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { clearConfirmation = null; confirmationError = null },
                ) { Text("취소") }
            },
        )
    }

    if (newProfileDialog) {
        NewCorrectionProfileDialog(
            busy = busy,
            externalError = confirmationError,
            onInputChanged = { confirmationError = null },
            onDismiss = { newProfileDialog = false; confirmationError = null },
            onSelect = { profile ->
                val capturedProfile = profile
                launchOperation(
                    fallbackMessage = "프로필을 만들지 못했습니다.",
                    onFailure = { confirmationError = it },
                ) {
                    repository.selectProfile(capturedProfile)
                    newProfileDialog = false
                    confirmationError = null
                    query = ""
                    message = "‘$capturedProfile’ 프로필을 직접 선택했습니다."
                }
            },
        )
    }

    exportPrivacyProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { exportPrivacyProfile = null },
            title = { Text("개인정보 포함 파일 내보내기") },
            text = {
                Text(
                    "내보낸 파일에는 ‘$profile’ 프로필의 인식문, 교정문과 힌트가 평문으로 포함됩니다. " +
                        "원음은 포함하지 않고 앱이 클라우드로 전송하지도 않지만, 선택한 저장 위치와 " +
                        "그 위치에 접근할 수 있는 앱·사람은 내용을 읽을 수 있습니다.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        exportPrivacyProfile = null
                        exportRequestProfile = profile
                        exporter.launch("guidecast-corrections-${safeFilePart(profile)}.json")
                    },
                ) { Text("파일 위치 선택") }
            },
            dismissButton = {
                TextButton(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { exportPrivacyProfile = null },
                ) { Text("취소") }
            },
        )
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!busy) { importPreview = null; confirmationError = null } },
            title = { Text("가져오기 ${preview.drafts.size}개 확인") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "검증된 ${preview.drafts.size}개 기록으로 ‘${preview.profile}’ 프로필만 교체합니다. " +
                            "이 프로필의 기존 기록은 영구 삭제되며 다른 프로필은 그대로 유지됩니다. " +
                            "교체 후에는 되돌릴 수 없습니다.",
                    )
                    preview.drafts.take(3).forEach { draft ->
                        Text(
                            "${draft.languageTag} · ${draft.recognizedText.take(50)} → ${draft.correctedText.take(50)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (preview.drafts.size > 3) {
                        Text("외 ${preview.drafts.size - 3}개", style = MaterialTheme.typography.labelMedium)
                    }
                    confirmationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        val captured = preview
                        launchOperation(
                            fallbackMessage = "프로필 기록을 교체하지 못했습니다.",
                            onFailure = { confirmationError = it },
                        ) {
                            repository.replaceProfile(captured.profile, captured.drafts)
                            undo = null
                            importPreview = null
                            confirmationError = null
                            message = "‘${captured.profile}’ 프로필을 ${captured.drafts.size}개 기록으로 교체했습니다. 다른 프로필은 유지했습니다."
                        }
                    },
                ) { Text("이 프로필만 교체") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { importPreview = null; confirmationError = null },
                ) { Text("취소") }
            },
        )
    }
}

@Composable
private fun CorrectionProfileChoice(
    selected: String,
    profiles: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text("선택한 프로필 · $selected ▾") }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile) },
                    onClick = { expanded = false; onSelect(profile) },
                )
            }
        }
    }
}

@Composable
private fun CorrectionLanguageChoice(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            val label = SOURCE_LANGUAGE_OPTIONS.firstOrNull { it.languageTag == selected }?.label ?: selected
            Text("입력 언어 · $label ▾")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            SOURCE_LANGUAGE_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.label} (${option.languageTag})") },
                    onClick = { expanded = false; onSelect(option.languageTag) },
                )
            }
        }
    }
}

@Composable
private fun SpeechCorrectionRow(
    entry: SpeechCorrectionEntry,
    busy: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("인식문", style = MaterialTheme.typography.labelMedium)
            Text(entry.recognizedText, style = MaterialTheme.typography.bodyMedium)
            Text("교정문", style = MaterialTheme.typography.labelMedium)
            Text(entry.correctedText, style = MaterialTheme.typography.titleMedium)
            entry.hint?.let { Text("힌트 · $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                when {
                    !entry.enabled -> "기록됨 · 일시 해제"
                    entry.hint == null -> "기록됨 · 인식 힌트 사용 안 함"
                    else -> "기록됨 · 다음 인식 힌트 요청 대상"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (entry.enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (entry.enabled) "활성" else "일시 해제",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = entry.enabled,
                    onCheckedChange = { onToggle() },
                    enabled = !busy,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onEdit,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("수정") }
                TextButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun DirectRecognitionDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onContinue: (String) -> Unit,
) {
    var recognizedText by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("인식문 직접 등록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("잘못 인식된 원문을 입력한 뒤 교정문을 작성합니다. 인식 힌트는 기본으로 꺼져 있습니다.")
                OutlinedTextField(
                    value = recognizedText,
                    onValueChange = { recognizedText = it; error = null },
                    label = { Text("잘못 인식된 원문") },
                    minLines = 2,
                    maxLines = 6,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = { Text("최대 2,000코드포인트") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    val value = recognizedText.trim()
                    error = when {
                        value.isBlank() -> "인식문을 입력해 주세요."
                        value.codePointCount(0, value.length) > SpeechCorrectionValidation.MAX_TEXT_CODE_POINTS ->
                            "인식문은 최대 2,000코드포인트입니다."
                        else -> null
                    }
                    if (error == null) onContinue(value)
                },
            ) { Text("교정문 작성") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = onDismiss,
            ) { Text("취소") }
        },
    )
}

@Composable
private fun NewCorrectionProfileDialog(
    busy: Boolean,
    externalError: String?,
    onInputChanged: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var profile by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("현장 프로필 직접 선택") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("프로필은 발화자 자동 인식과 연결되지 않습니다. 운영 목적에 맞는 이름을 직접 입력하세요.")
                OutlinedTextField(
                    value = profile,
                    onValueChange = { profile = it; error = null; onInputChanged() },
                    label = { Text("프로필 이름") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null || externalError != null,
                    supportingText = { Text("최대 80코드포인트") },
                )
                (error ?: externalError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    val value = profile.trim()
                    error = when {
                        value.isBlank() -> "프로필 이름을 입력해 주세요."
                        value.codePointCount(0, value.length) > SpeechCorrectionValidation.MAX_PROFILE_CODE_POINTS ->
                            "프로필 이름은 최대 80코드포인트입니다."
                        else -> null
                    }
                    if (error == null) onSelect(value)
                },
            ) { Text("이 프로필 선택") }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = onDismiss,
            ) { Text("취소") }
        },
    )
}

private fun resolveSourceLanguageTag(initialLanguageTag: String): String {
    SOURCE_LANGUAGE_OPTIONS.firstOrNull {
        it.languageTag.equals(initialLanguageTag, ignoreCase = true)
    }?.let { return it.languageTag }
    val base = Locale.forLanguageTag(initialLanguageTag).language
    return SOURCE_LANGUAGE_OPTIONS.firstOrNull {
        Locale.forLanguageTag(it.languageTag).language.equals(base, ignoreCase = true)
    }?.languageTag ?: DEFAULT_SOURCE_LANGUAGE_TAG
}

private fun safeFilePart(profile: String): String = profile
    .map { character -> if (character.isLetterOrDigit()) character else '_' }
    .joinToString("")
    .take(40)
    .ifBlank { "profile" }

private fun SpeechCorrectionEntry.toCorrectionDraft() = SpeechCorrectionDraft(
    profile = profile,
    languageTag = languageTag,
    recognizedText = recognizedText,
    correctedText = correctedText,
    hint = hint,
    enabled = enabled,
    sourceKey = sourceKey,
)

private fun correctionFailureMessage(fallback: String, failure: Exception): String {
    val validation = failure as? SpeechCorrectionValidationException ?: return "$fallback 방송에는 영향이 없습니다."
    return when (validation.code) {
        SpeechCorrectionValidationCode.MAX_ENTRIES -> "교정 기록은 앱 전체에서 최대 500개까지 저장할 수 있습니다."
        SpeechCorrectionValidationCode.MAX_HINTS -> "활성 인식 힌트는 프로필과 언어별로 최대 32개입니다."
        SpeechCorrectionValidationCode.MAX_HINT_CHARACTERS ->
            "활성 인식 힌트 합계는 프로필과 언어별로 최대 1,000코드포인트입니다."
        SpeechCorrectionValidationCode.HINT_TOO_LONG -> "인식 힌트 1개는 최대 80코드포인트입니다."
        SpeechCorrectionValidationCode.HINT_EMPTY -> "인식 힌트를 입력하거나 힌트 사용을 꺼 주세요."
        SpeechCorrectionValidationCode.PROFILE_REQUIRED,
        SpeechCorrectionValidationCode.PROFILE_TOO_LONG -> "프로필 이름을 확인해 주세요. 최대 80코드포인트입니다."
        SpeechCorrectionValidationCode.RECOGNIZED_TEXT_REQUIRED,
        SpeechCorrectionValidationCode.RECOGNIZED_TEXT_TOO_LONG -> "인식문을 확인해 주세요. 최대 2,000코드포인트입니다."
        SpeechCorrectionValidationCode.CORRECTED_TEXT_REQUIRED,
        SpeechCorrectionValidationCode.CORRECTED_TEXT_TOO_LONG -> "교정문을 확인해 주세요. 최대 2,000코드포인트입니다."
        SpeechCorrectionValidationCode.CONTROL_CHARACTER,
        SpeechCorrectionValidationCode.INVALID_UNICODE,
        SpeechCorrectionValidationCode.TEXT_NOT_NFC -> "저장할 수 없는 문자 형식이 포함되어 있습니다. 입력 내용을 다시 확인해 주세요."
        SpeechCorrectionValidationCode.DUPLICATE_ENTRY -> "같은 프로필·언어·인식문의 교정이 중복되어 있습니다."
        SpeechCorrectionValidationCode.EMPTY_BATCH -> "가져올 교정 기록이 없습니다."
        SpeechCorrectionValidationCode.QUERY_TOO_LONG -> "검색어는 최대 2,000코드포인트입니다."
        SpeechCorrectionValidationCode.LANGUAGE_TAG_INVALID -> "지원되는 입력 언어를 선택해 주세요."
        else -> "$fallback 입력 내용과 파일 형식을 확인해 주세요. 방송에는 영향이 없습니다."
    }
}
