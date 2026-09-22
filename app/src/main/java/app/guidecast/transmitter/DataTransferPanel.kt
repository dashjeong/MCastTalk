package app.guidecast.transmitter

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal data class DataTransferUiState(
    val busy: Boolean = false,
    val progress: DataTransferProgress? = null,
    val message: String? = null,
    val revision: Long = 0,
    val retryExportKind: DataTransferKind? = null,
)

internal class DataTransferViewModel private constructor(
    application: Application,
    private val exportOperation: suspend (DataTransferKind, (DataTransferProgress) -> Unit) -> DataTransferResult,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        DataTransferRepository(application as GuideCastApplication)::exportToDownloads)

    internal constructor(application: Application, prepareBackup: BackupPreparer, downloadStore: DownloadBackupStore) :
        this(application, { kind, progress ->
            savePreparedDownload(application as GuideCastApplication, kind, prepareBackup, downloadStore, progress)
        })

    private val app = application as GuideCastApplication
    private val repository = DataTransferRepository(app)
    private val mutableState = MutableStateFlow(DataTransferUiState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null

    init {
        viewModelScope.launch {
            combine(app.broadcastRuntime.state, app.localFileWorkActive, app.localModelWorkActive, app.localVoiceNoteWorkActive) { runtime, fileBusy, modelBusy, noteBusy ->
                runtime.dataTransferUnavailable() || fileBusy || modelBusy || noteBusy
            }.distinctUntilChanged().collect { busy ->
                if (busy) work?.cancel()
            }
        }
    }

    fun import(kind: DataTransferKind, uri: Uri) {
        if (mutableState.value.busy || app.dataTransferUnavailable()) return
        mutableState.value = mutableState.value.copy(busy = true, message = null, retryExportKind = null, progress = DataTransferProgress("백업을 열고 있습니다."))
        work = viewModelScope.launch {
            var applying = false
            val message = try {
                require(uri.scheme == "content") { "문서 선택기에서 백업 파일을 선택하세요." }
                repository.import(kind, uri) { progress ->
                    applying = applying || progress.applying
                    mutableState.value = mutableState.value.copy(progress = progress)
                }.message
            } catch (cancelled: CancellationException) {
                if (applying) "가져오기를 중지했습니다. 이미 병합된 항목은 유지됩니다. 같은 백업을 다시 선택하면 기존 항목을 보존하며 이어집니다."
                else "가져오기를 취소했습니다. 기존 자료는 변경하지 않았습니다."
            } catch (_: Exception) {
                if (applying) "가져오기를 마치지 못했습니다. 기존 자료와 완료된 병합은 유지됩니다. 저장공간과 파일 권한을 확인한 뒤 같은 백업을 다시 선택하세요."
                else "백업을 확인하지 못했습니다. 종류·파일 형식·저장공간·권한을 확인하고 다시 선택하세요. 기존 자료는 변경하지 않았습니다."
            }
            mutableState.value = DataTransferUiState(message = message, revision = mutableState.value.revision + 1)
        }
    }

    fun export(kind: DataTransferKind) {
        if (mutableState.value.busy || app.dataTransferUnavailable()) return
        mutableState.value = mutableState.value.copy(busy = true, message = null, retryExportKind = null,
            progress = DataTransferProgress("내보낼 백업을 준비하고 있습니다."))
        work = viewModelScope.launch {
            var succeeded = false
            val message = try {
                exportOperation(kind) { progress -> mutableState.value = mutableState.value.copy(progress = progress) }
                    .also { succeeded = true }.message
            } catch (_: CancellationException) {
                "내보내기를 중지했습니다. 기존 백업은 유지합니다. 완료 직전에 중지했다면 ${DownloadBackupStore.DISPLAY_DIRECTORY}에서 저장 여부를 확인하세요. 다시 시도할 수 있습니다."
            } catch (_: Exception) {
                "백업 내보내기를 완료하지 못했습니다. 기존 백업은 유지합니다. 기기 저장공간을 확인하고 다시 시도하세요."
            }
            mutableState.value = DataTransferUiState(message = message, revision = mutableState.value.revision + 1,
                retryExportKind = if (succeeded) null else kind)
        }
    }
    fun cancel() { work?.cancel() }
}

/** Standalone settings route. Export always uses the device's fixed Downloads backup folder. */
@Composable
fun DataTransferPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    unavailableReason: String? = null,
    onExport: ((DataTransferKind) -> Unit)? = null,
) {
    val model: DataTransferViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val displaySettings = LocalUiDisplaySettings.current
    var requestedKind by rememberSaveable { mutableStateOf(DataTransferKind.SETTINGS.name) }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri?.toString() }
    BackHandler(onBack = onBack)
    LaunchedEffect(state.revision) {
        if (state.revision > 0) displaySettings?.setDeveloperInfo(context.getSharedPreferences("ui_display", Context.MODE_PRIVATE).getBoolean("developer_info", false))
    }
    // Operator navigation remains available. Only the data operation waits for an idle input.
    LaunchedEffect(unavailableReason) { if (unavailableReason != null && state.busy) model.cancel() }
    LazyColumn(modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정으로 돌아가기") }
            Text("데이터 가져오기 · 내보내기", style = MaterialTheme.typography.headlineSmall)
            Text("이관 시 기존 자료와 확정 수정은 유지하고 새 항목을 추가합니다.", style = MaterialTheme.typography.bodyMedium)
            Text("내보내기 저장 위치: 기기 ${DownloadBackupStore.DISPLAY_DIRECTORY}", style = MaterialTheme.typography.bodyMedium)
        }
        if (unavailableReason != null) item { Text(unavailableReason, color = MaterialTheme.colorScheme.error) }
        if (state.busy) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.progress?.message.orEmpty())
                    state.progress?.records?.takeIf { it > 0 }?.let { Text("${it}개 레코드") }
                    OutlinedButton(onClick = model::cancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("작업 취소") }
                    Text("화면을 나가도 작업은 계속됩니다. 입력·방송·파일·모델 작업을 시작하면 백업 작업을 중지합니다.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
        state.retryExportKind?.let { kind -> item {
            OutlinedButton(onClick = { model.export(kind) }, enabled = !state.busy && unavailableReason == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("${kind.label} 내보내기 재작업") }
        } }
        DataTransferKind.entries.forEach { kind -> item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(kind.label, style = MaterialTheme.typography.titleLarge)
                    Text(when (kind) {
                        DataTransferKind.SETTINGS -> "원문·번역 언어·송출 방식·잡음 처리·화면 표시·음성 선택·사전 프로필·보관 방식과 실험 옵션을 이관합니다. 암호·PIN·인증서·API 키는 제외하며 클라우드 전송 동의는 다시 받아야 합니다."
                        DataTransferKind.DICTIONARY -> "수정한 용어, 인식 교정, 사용자 확정·AI 학습 문장을 포함합니다. 기본 사전은 앱에 포함되어 있습니다. 개인 문장이 포함될 수 있습니다."
                        DataTransferKind.SCRIPTS -> "전체 방송 기록·일일 백업·파일 스크립트와 녹톡 노트·직접 수정·화자·녹음 원음을 포함합니다. 녹톡은 가져오기에서 원음까지 복원합니다. 외부 파일 음원은 별도로 보관하고 ‘파일 찾기’로 연결하세요. 저장 공간과 최대 2GB 압축·8GB 복원 한도를 확인하세요."
                    }, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { requestedKind = kind.name; picker.launch(arrayOf("application/zip", "application/octet-stream")) },
                        enabled = !state.busy && unavailableReason == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("${kind.label} 가져오기")
                    }
                    OutlinedButton(onClick = { if (onExport != null) onExport(kind) else model.export(kind) }, enabled = !state.busy && unavailableReason == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (kind == DataTransferKind.SETTINGS) "설정 내보내기" else "${kind.label} 백업 (내보내기)")
                    }
                }
            }
        } }
    }
    importUri?.let { value ->
        val kind = DataTransferKind.valueOf(requestedKind)
        AlertDialog(onDismissRequest = { importUri = null }, title = { Text("${kind.label} 가져오기") },
            text = { Text("백업 전체를 검사한 뒤 기존 자료를 유지하며 추가합니다. 같은 항목의 현재 확정 수정이 우선합니다. 취소하거나 오류가 발생하면 같은 백업을 다시 선택할 수 있습니다.") },
            confirmButton = { TextButton(onClick = { importUri = null; model.import(kind, Uri.parse(value)) }, enabled = unavailableReason == null) { Text("검사 후 가져오기") } },
            dismissButton = { TextButton(onClick = { importUri = null }) { Text("취소") } })
    }
}
