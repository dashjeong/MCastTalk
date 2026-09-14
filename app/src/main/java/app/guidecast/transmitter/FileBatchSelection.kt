package app.guidecast.transmitter

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class FileConversionStatus { READY, READING, CONVERTING, SAVED, PARTIAL, FAILED, CANCELLED }
data class FileConversionItem(
    val uri: String,
    val displayName: String,
    val sha256: String? = null,
    val status: FileConversionStatus = FileConversionStatus.READY,
    val message: String? = null,
)

internal const val MAX_FILE_BATCH_SIZE = 500

/** Enumerates only the tree explicitly selected in Android's document picker. */
internal suspend fun audioDocumentsInFolder(context: Context, tree: Uri): List<Uri> = withContext(Dispatchers.IO) {
    val directories = ArrayDeque<String>()
    directories.add(DocumentsContract.getTreeDocumentId(tree))
    val scheduled = hashSetOf(DocumentsContract.getTreeDocumentId(tree))
    val visited = hashSetOf<String>()
    val files = linkedSetOf<Uri>()
    while (directories.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val directory = directories.removeFirst()
        if (!visited.add(directory)) continue
        if (visited.size > 256) throw FileTranscriptionException("하위 폴더가 너무 많습니다. 변환할 하위 폴더를 직접 선택하세요.")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directory)
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            var scanned = 0
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                if (++scanned > 10_000) throw FileTranscriptionException("폴더의 파일이 너무 많습니다. 작은 하위 폴더를 선택하세요.")
                val id = cursor.getString(0)
                val mime = cursor.getString(1).orEmpty()
                val name = cursor.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (scheduled.add(id)) {
                        if (scheduled.size > 256) throw FileTranscriptionException("하위 폴더가 너무 많습니다. 변환할 하위 폴더를 직접 선택하세요.")
                        directories.addLast(id)
                    }
                }
                else if (isSupportedFileAudioDocument(mime, name)) {
                    files += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    if (files.size > MAX_FILE_BATCH_SIZE) throw FileTranscriptionException("한 번에 최대 500개 파일을 변환할 수 있습니다. 폴더를 나누어 선택하세요.")
                }
            }
        } ?: throw FileTranscriptionException("선택한 폴더의 파일 목록을 읽을 수 없습니다.")
    }
    if (files.isEmpty()) throw FileTranscriptionException("폴더와 하위 폴더에서 오디오·비디오 파일을 찾지 못했습니다.")
    files.toList()
}

internal fun isSupportedFileAudioDocument(mime: String, name: String): Boolean =
    mime.startsWith("audio/") || mime.startsWith("video/") ||
        name.substringAfterLast('.', "").lowercase() in setOf("mp3", "mp4", "m4a", "wav", "aac", "ogg", "opus", "flac", "amr", "3gp", "webm", "mkv")

internal fun retryableFileItem(item: FileConversionItem): Boolean = item.status in
    setOf(FileConversionStatus.FAILED, FileConversionStatus.PARTIAL, FileConversionStatus.CANCELLED)

internal fun requeueCompleted(items: List<FileConversionItem>): List<FileConversionItem> = items.map {
    if (it.status == FileConversionStatus.SAVED) it.copy(status = FileConversionStatus.READY, message = "변경한 설정으로 처리할 준비가 됐습니다.") else it
}
