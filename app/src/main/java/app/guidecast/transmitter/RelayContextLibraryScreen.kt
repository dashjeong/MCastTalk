package app.guidecast.transmitter

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

internal fun readReferenceDocument(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        require(bytes.size() + size <= 256_000) { "Reference file exceeds size limit" }
        bytes.write(buffer, 0, size)
    }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    require(text.isNotBlank() && text.length <= MAX_REFERENCE_DOCUMENT_CHARS)
    return text
}

@Composable
internal fun RelayContextLibraryScreen(app: GuideCastApplication, onBack: () -> Unit,
    settingsRequest: Int = 0, onSettingsRequestHandled: () -> Unit = {}, onOpenSettings: () -> Unit = onBack) {
    var corpus by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val revision by app.domainCorpus.referenceRevision.collectAsState()
    var documents by remember { mutableStateOf<List<DomainReferenceDocument>>(emptyList()) }
    var more by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<Long?>(null) }
    var creationRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ReferenceDocumentKind.LECTURE) }
    var body by rememberSaveable { mutableStateOf("") }
    var originalTitle by rememberSaveable { mutableStateOf("") }
    var originalKind by rememberSaveable { mutableStateOf(ReferenceDocumentKind.LECTURE) }
    var originalBodyDigest by rememberSaveable { mutableStateOf(referenceEditorBodyDigest("")) }
    var pendingEditorAction by rememberSaveable { mutableStateOf<String?>(null) }
    var saveRequested by rememberSaveable { mutableStateOf(false) }
    var afterSaveAction by rememberSaveable { mutableStateOf("CLOSE_EDITOR") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DomainReferenceDocument?>(null) }
    val controlsEnabled = !loading && !saveRequested

    fun finishEditorAction(action: String) {
        editing = false; pendingEditorAction = null
        editId = null; creationRequestId = null
        title = ""; kind = ReferenceDocumentKind.LECTURE; body = ""
        originalTitle = ""; originalKind = ReferenceDocumentKind.LECTURE
        originalBodyDigest = referenceEditorBodyDigest("")
        message = null
        if (action == "BACK_LIBRARY") onBack()
        else if (action == "OPEN_SETTINGS") { corpus = false; onOpenSettings() }
    }
    fun requestEditorAction(action: String) {
        when (referenceNavigationDecision(loading, saveRequested, editing) {
            title != originalTitle || kind != originalKind || referenceEditorBodyDigest(body) != originalBodyDigest
        }) {
            ReferenceNavigationDecision.BUSY -> return
            ReferenceNavigationDecision.CONFIRM -> pendingEditorAction = action
            ReferenceNavigationDecision.LEAVE -> finishEditorAction(action)
        }
    }
    fun requestSave(action: String) {
        if (loading || saveRequested || title.isBlank() || body.isBlank()) return
        if (editId == null && creationRequestId == null) creationRequestId = UUID.randomUUID().toString()
        afterSaveAction = action; pendingEditorAction = null; message = null
        saveRequested = true
    }
    fun beginEditor(id: Long?, name: String, documentKind: ReferenceDocumentKind, text: String) {
        if (saveRequested) return
        editId = id; creationRequestId = if (id == null) UUID.randomUUID().toString() else null
        title = name; kind = documentKind; body = text
        originalTitle = if (id == null) "" else name
        originalKind = if (id == null) ReferenceDocumentKind.LECTURE else documentKind
        originalBodyDigest = referenceEditorBodyDigest(if (id == null) "" else text)
        pendingEditorAction = null; message = null; editing = true
    }
    fun work(action: suspend () -> Unit) { scope.launch {
        try { action(); message = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "자료를 처리하지 못했습니다. 제목·내용·파일 크기를 확인해 주세요." }
    } }

    LaunchedEffect(saveRequested) {
        if (!saveRequested) return@LaunchedEffect
        val requestedId = editId
        val requestedToken = if (requestedId == null) creationRequestId else null
        val requestedTitle = title
        val requestedKind = kind
        val requestedBody = body
        val requestedAction = afterSaveAction
        try {
            require(requestedId != null || requestedToken != null)
            app.domainCorpus.saveReferenceDocument(requestedId, requestedTitle, requestedKind,
                requestedBody, creationRequestId = requestedToken)
            saveRequested = false
            finishEditorAction(requestedAction)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ReferenceDocumentWasRemovedException) {
            saveRequested = false
            message = "이미 삭제된 자료여서 저장하지 않았습니다. 필요한 입력은 복사해 보관한 뒤 새 자료로 추가해 주세요."
        } catch (_: Exception) {
            saveRequested = false
            message = "저장하지 못했습니다. 내용과 크기를 확인해 주세요. API 키는 자료에 넣지 마세요."
        }
    }
    LaunchedEffect(settingsRequest) {
        if (settingsRequest > 0) {
            if (controlsEnabled) requestEditorAction("OPEN_SETTINGS")
            else message = "자료를 처리 중입니다. 완료한 뒤 중계 설정을 다시 열어 주세요."
            onSettingsRequestHandled()
        }
    }
    if (corpus) { DomainCorpusScreen(app.domainCorpus, onBack = { corpus = false }); return }
    BackHandler { requestEditorAction("BACK_LIBRARY") }
    LaunchedEffect(revision) {
        try { documents = app.domainCorpus.referenceDocuments(); more = documents.size == 50 }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "자료를 불러오지 못했습니다. 다시 열어 주세요." }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !loading && !saveRequested) {
            loading = true
            work { try {
                val text = withContext(Dispatchers.IO) {
                    requireNotNull(app.contentResolver.openInputStream(uri)).use(::readReferenceDocument)
                }
                beginEditor(null, "가져온 자료", ReferenceDocumentKind.LECTURE, text)
            } finally { loading = false } }
        }
    }
    if (editing && pendingEditorAction == null) AlertDialog(
        onDismissRequest = { requestEditorAction("CLOSE_EDITOR") },
        title = { Text(if (editId == null) "자료 추가" else "자료 읽기·수정") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { if (!saveRequested) title = it.take(80) }, enabled = !saveRequested, label = { Text("자료 이름") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(body, { if (!saveRequested && it.length <= MAX_REFERENCE_DOCUMENT_CHARS) body = it }, enabled = !saveRequested,
                label = { Text("대본·참고 내용") }, placeholder = { Text("강의 대본, 참고 글, 전문 용어를 입력하거나 붙여넣으세요.") }, minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth())
            Text("${body.length} / $MAX_REFERENCE_DOCUMENT_CHARS 자 · 저장만으로 외부에 보내지 않습니다.")
            ServiceExperienceChoices("자료 종류", ReferenceDocumentKind.entries.map { ExperienceChoice(it.name, it.label, "") }, kind.name, !saveRequested) {
                if (!saveRequested) kind = ReferenceDocumentKind.valueOf(it)
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saveRequested && title.isNotBlank() && body.isNotBlank(), onClick = {
            requestSave("CLOSE_EDITOR")
        }) { Text(if (saveRequested) "저장 중" else "저장") } },
        dismissButton = { TextButton(enabled = !saveRequested, onClick = { requestEditorAction("CLOSE_EDITOR") }) { Text("취소") } })
    pendingEditorAction?.let { action -> AlertDialog(
        onDismissRequest = { pendingEditorAction = null },
        title = { Text("입력한 자료를 저장할까요?") },
        text = { Text("아직 저장하지 않은 자료가 있습니다. 저장하거나 입력을 버린 뒤 계속할 수 있습니다.") },
        confirmButton = { TextButton(enabled = title.isNotBlank() && body.isNotBlank(), onClick = {
            requestSave(action)
        }) { Text("저장하고 계속") } },
        dismissButton = { Column {
            TextButton(onClick = { finishEditorAction(action) }) { Text("입력 버리고 계속") }
            TextButton(onClick = { pendingEditorAction = null }) { Text("편집 계속") }
        } }) }
    deleting?.let { document -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("자료 삭제") },
        text = { Text("${document.title} 자료를 기기에서 삭제합니다.") },
        confirmButton = { TextButton(onClick = { deleting = null; work { app.domainCorpus.removeReferenceDocument(document.id) } }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
    LazyColumn(Modifier.fillMaxSize().semantics { paneTitle = "전문 통역 자료" },
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(enabled = controlsEnabled, onClick = { requestEditorAction("BACK_LIBRARY") }) { Text("통역 중계 설정으로") }
            Text("전문 통역 자료", style = MaterialTheme.typography.headlineMedium)
            Text("강의 대본·참고 글·용어 메모를 기기에 보관합니다. 사용할 자료를 켠 뒤 중계 설정에서 참고 자료 전송에 동의하세요.")
            Text("지원 모델에는 용어·예문·대본의 짧은 발췌만, 전체 합계 최대 600자로 전달합니다. 긴 대본 전체를 보낸다는 뜻은 아닙니다. 변경은 다음 중계 시작부터 반영됩니다.")
            if (loading) Text("자료를 읽고 있습니다.")
            message?.let { ServiceConnectionFeedback(it) }
            Button(enabled = controlsEnabled, onClick = { if (!loading && !saveRequested) beginEditor(null, "", ReferenceDocumentKind.LECTURE, "") }, modifier = Modifier.fillMaxWidth()) { Text("자료 직접 입력") }
            OutlinedButton(enabled = controlsEnabled, onClick = { if (!loading && !saveRequested) importer.launch(arrayOf("text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("TXT 자료 가져오기") }
            OutlinedButton(enabled = controlsEnabled, onClick = { if (!loading && !saveRequested) corpus = true }, modifier = Modifier.fillMaxWidth()) { Text("전문 용어·번역 예문 관리") }
        }
        if (documents.isEmpty()) item { Text("저장된 대본·참고 글이 없습니다. 자료를 직접 입력하거나 TXT 파일을 가져오세요.") }
        items(documents, key = { it.id }) { document ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(document.title, style = MaterialTheme.typography.titleMedium)
                Text("${document.kind.label} · ${document.characters}자 보관")
                ServiceExperienceToggle("이 자료 참고", "다음 중계 준비 때 짧은 발췌 후보에 포함합니다. 전체 내용이 전달되지는 않습니다.", document.enabled, controlsEnabled) {
                    work { app.domainCorpus.enableReferenceDocument(document.id, it) }
                }
                TextButton(enabled = controlsEnabled, onClick = {
                    if (!loading && !saveRequested) {
                        loading = true
                        work { try {
                            val text = app.domainCorpus.referenceDocumentText(document.id)
                            beginEditor(document.id, document.title, document.kind, text)
                        } finally { loading = false } }
                    }
                }) { Text("내용 읽기·수정") }
                TextButton(enabled = controlsEnabled, onClick = { deleting = document }) { Text("삭제") }
            } }
        }
        if (more) item { OutlinedButton(enabled = controlsEnabled, onClick = { work {
            val page = app.domainCorpus.referenceDocuments(documents.lastOrNull()?.id)
            documents = documents + page; more = page.size == 50
        } }, modifier = Modifier.fillMaxWidth()) { Text("이전 자료 더 보기") } }
    }
}

private fun referenceEditorBodyDigest(text: String): String {
    val bytes = ByteArray(text.length * 2)
    text.forEachIndexed { index, character ->
        bytes[index * 2] = (character.code ushr 8).toByte()
        bytes[index * 2 + 1] = character.code.toByte()
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

internal enum class ReferenceNavigationDecision { BUSY, CONFIRM, LEAVE }

internal fun referenceNavigationDecision(loading: Boolean, saving: Boolean, editing: Boolean,
    isDirty: () -> Boolean): ReferenceNavigationDecision = when {
    loading || saving -> ReferenceNavigationDecision.BUSY
    editing && isDirty() -> ReferenceNavigationDecision.CONFIRM
    else -> ReferenceNavigationDecision.LEAVE
}
