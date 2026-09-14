package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DownloadedBackup(val uri: Uri, val displayName: String, val bytes: Long)

/** Narrow local MediaStore boundary. It accepts no arbitrary destination path or provider. */
internal interface DownloadBackupMediaStore {
    fun createPending(displayName: String): Uri
    fun openOutput(uri: Uri): OutputStream
    fun publish(uri: Uri): Boolean
    fun removePending(uri: Uri): Boolean
}

/** Called only by an explicit runtime export action, after the complete private ZIP was validated. */
internal class DownloadBackupStore internal constructor(
    private val media: DownloadBackupMediaStore,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val uniqueId: () -> String = { UUID.randomUUID().toString() },
) {
    constructor(context: Context) : this(AndroidDownloadBackupMediaStore(context.applicationContext))

    suspend fun save(backup: PreparedLocalBackup, onProgress: (written: Long, total: Long) -> Unit = { _, _ -> }): DownloadedBackup =
        withContext(Dispatchers.IO) {
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            require(backup.file.isFile)
            val total = backup.file.length()
            require(total in 1..DataTransferFormat.MAX_COMPRESSED_BYTES)
            val suffix = uniqueId().also { require(it.matches(Regex("[A-Za-z0-9-]{1,80}"))) }
            val time = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(clockMillis()))
            val name = "MCastTalk-${backup.kind.name.lowercase(Locale.ROOT)}-$time-$suffix.zip"
            var pending: Uri? = null
            var published = false
            try {
                val uri = media.createPending(name).also { pending = it }
                coroutine.ensureActive()
                onProgress(0, total)
                var written = 0L
                media.openOutput(uri).use { output ->
                    backup.file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutine.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            check(written <= total) { "백업 준비 파일이 변경되었습니다. 다시 시도하세요." }
                            output.write(buffer, 0, read)
                            onProgress(written, total)
                        }
                    }
                    check(written == total) { "백업 파일을 끝까지 저장하지 못했습니다." }
                    coroutine.ensureActive()
                    output.flush()
                    (output as? FileOutputStream)?.fd?.sync()
                }
                coroutine.ensureActive()
                check(media.publish(uri)) { "완료된 백업을 다운로드 폴더에 표시하지 못했습니다." }
                published = true
                DownloadedBackup(uri, name, written)
            } catch (failure: Throwable) {
                val uri = pending
                // The sole deletion target is this operation's new, unpublished item. No old
                // backup is queried by filename, overwritten, or removed during cancellation.
                if (uri != null && !published) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { check(media.removePending(uri)) { "미완료 백업 항목 정리에 실패했습니다." } }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }

    companion object {
        const val RELATIVE_DIRECTORY = "Download/MCastTalk/Backups/"
        const val DISPLAY_DIRECTORY = "다운로드/MCastTalk/Backups"
    }
}

/** minSdk 30: scoped MediaStore Downloads needs no broad storage permission or SAF fallback. */
internal class AndroidDownloadBackupMediaStore(context: Context) : DownloadBackupMediaStore {
    private val resolver = context.applicationContext.contentResolver
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun createPending(displayName: String): Uri = requireNotNull(resolver.insert(downloads, ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MCastTalk/Backups/")
        put(MediaStore.Downloads.IS_PENDING, 1)
    })) { "다운로드 폴더에 새 백업 파일을 만들지 못했습니다." }

    override fun openOutput(uri: Uri): OutputStream = requireNotNull(resolver.openOutputStream(uri, "w")) {
        "새 백업 파일을 열지 못했습니다."
    }

    override fun publish(uri: Uri): Boolean = resolver.update(uri, ContentValues().apply {
        put(MediaStore.Downloads.IS_PENDING, 0)
    }, "${MediaStore.Downloads.IS_PENDING}=?", arrayOf("1")) == 1

    override fun removePending(uri: Uri): Boolean {
        if (resolver.delete(uri, "${MediaStore.Downloads.IS_PENDING}=?", arrayOf("1")) > 0) return true
        // A provider may already have discarded the failed insertion; absence is also clean.
        return resolver.query(uri, arrayOf(MediaStore.Downloads._ID), null, null, null)?.use { !it.moveToFirst() } ?: true
    }
}
