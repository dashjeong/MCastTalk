package app.guidecast.transmitter

import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class RecordedDownloadState(val active: Boolean = false, val message: String? = null,
    val savedUri: Uri? = null, val mime: String? = null, val recordingId: String? = null)

/** The document-picker result and copy stay attached to the activity across configuration changes. */
internal class RecordedDownloadViewModel : ViewModel() {
    private val mutable = MutableStateFlow(RecordedDownloadState())
    val state = mutable.asStateFlow()
    private var copyJob: Job? = null

    fun clearNotice() { if (!mutable.value.active) mutable.value = RecordedDownloadState() }
    fun cancelCopy() { copyJob?.cancel() }
    fun finish(app: GuideCastApplication, name: String?, destination: Uri?) {
        if (mutable.value.active) return
        mutable.value = RecordedDownloadState(active = true)
        copyJob = viewModelScope.launch {
            try {
                val mime = withContext(Dispatchers.IO) {
                    if (destination == null) {
                        app.recordings.restoreDownload(name)?.delete()
                        return@withContext null
                    }
                    val job = currentCoroutineContext()
                    copyPreparedRecordedDownload({ app.recordings.restoreDownload(name) }, {
                        requireNotNull(app.contentResolver.openOutputStream(destination, "wt"))
                    }, { job.ensureActive() })
                }
                mutable.value = if (destination == null) RecordedDownloadState(message = "저장을 취소했습니다.")
                    else RecordedDownloadState(message = "파일을 저장했습니다. 선택한 위치에서 열어보세요.",
                        savedUri = destination, mime = mime, recordingId = name?.take(36))
            } catch (failure: Exception) {
                val removed = withContext(NonCancellable + Dispatchers.IO) {
                    destination != null && RecordedBroadcastShareStore.isDownloadIdentity(name) &&
                        runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }.getOrDefault(false)
                }
                val notice = when {
                    failure is CancellationException -> "저장을 취소했습니다. 내려받기를 다시 선택할 수 있습니다."
                    failure is IllegalArgumentException -> "준비한 파일이 없거나 보관 시간이 지났습니다. 내려받기를 다시 선택해 주세요."
                    else -> "파일 저장 실패 · 저장 위치와 여유 공간을 확인하세요."
                }
                mutable.value = RecordedDownloadState(message = notice +
                    if (destination != null && !removed) " 저장 위치에 남은 불완전 파일을 확인하세요." else "")
                if (failure is CancellationException) throw failure
            } finally { copyJob = null }
        }
    }
}
