package app.guidecast.transmitter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Only app-created sharing copies; recordings and user-selected SAF/recipient copies are separate. */
internal class RecordedBroadcastShareStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onRemove: (File) -> Unit = {},
) {
    @Synchronized fun create(recordingId: String, extension: String = "zip", write: (File) -> Unit): File {
        require(UUID.fromString(recordingId).toString() == recordingId)
        require(extension in setOf("zip", "mp3", "txt", "ndjson"))
        check(directory.mkdirs() || directory.isDirectory)
        cleanup()
        val name = "$recordingId-${clock()}-${UUID.randomUUID()}.$extension"
        val staged = File(directory, "$name.partial")
        val finished = File(directory, name)
        try { write(staged); check(staged.renameTo(finished)); return finished }
        catch (failure: Exception) { staged.delete(); throw failure }
    }
    @Synchronized fun deleteRecording(recordingId: String, uniqueLegacyStartMillis: Long? = null) {
        require(UUID.fromString(recordingId).toString() == recordingId)
        directory.listFiles().orEmpty().filter {
            val name = it.name.removeSuffix(".partial")
            (name.startsWith("$recordingId-") && SHARE_NAME.matches(name)) ||
                (uniqueLegacyStartMillis != null && name.startsWith("MCastTalk-$uniqueLegacyStartMillis-") && LEGACY_SHARE_NAME.matches(name))
        }
            .forEach(::remove)
    }
    @Synchronized fun cleanup() {
        directory.listFiles().orEmpty().filter { file ->
            val name = file.name.removeSuffix(".partial")
            (SHARE_NAME.matches(name) || LEGACY_SHARE_NAME.matches(name)) && expired(file, clock())
        }.forEach(::remove)
    }
    /** Restore only a finished, unexpired export identity from a document-picker round trip. */
    @Synchronized fun resolveDownload(name: String?): File? {
        if (!isDownloadIdentity(name)) return null
        val root = directory.canonicalFile
        val identity = requireNotNull(name)
        val file = File(root, identity).canonicalFile
        return file.takeIf { it.parentFile == root && it.name == identity && it.isFile && it.length() > 0 && !expired(it, clock()) }
    }
    private fun remove(file: File) {
        runCatching { onRemove(file) }
        check(!file.exists() || file.delete())
    }
    companion object {
        const val RETENTION_MILLIS = 24 * 60 * 60 * 1000L
        private const val ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val SHARE_NAME = Regex("$ID-([0-9]+)-$ID\\.(?:zip|mp3|txt|ndjson)")
        private val LEGACY_SHARE_NAME = Regex("MCastTalk-[0-9]+-$ID\\.zip")
        fun isDownloadIdentity(name: String?): Boolean = name != null && SHARE_NAME.matches(name) &&
            name.substringAfterLast('.') in setOf("mp3", "txt")
        fun expired(file: File, now: Long): Boolean {
            val name = file.name.removeSuffix(".partial")
            val created = SHARE_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
                ?: if (LEGACY_SHARE_NAME.matches(name)) file.lastModified() else return true
            return created < 0 || now >= created && now - created >= RETENTION_MILLIS
        }
    }
}

/** Expiration is enforced on reads even while the app's cleanup worker is not running. */
class RecordedBroadcastFileProvider : FileProvider() {
    private fun requireAvailable(uri: Uri) {
        val root = File(requireNotNull(context).cacheDir, "broadcast-share").canonicalFile
        val pieces = uri.pathSegments
        if (pieces.firstOrNull() != "broadcast_exports" || pieces.size < 2) throw FileNotFoundException("Shared recording unavailable")
        val file = File(root, pieces.drop(1).joinToString("/")).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || RecordedBroadcastShareStore.expired(file, System.currentTimeMillis()))
            throw FileNotFoundException("Shared recording unavailable")
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Shared recording read only")
        requireAvailable(uri)
        return requireNotNull(super.openFile(uri, mode))
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
        requireAvailable(uri)
        return super.query(uri, projection, selection, selectionArgs, sortOrder)
    }
}

internal fun recordedShareStore(context: Context) = RecordedBroadcastShareStore(File(context.cacheDir, "broadcast-share"), onRemove = { file ->
    context.revokeUriPermission(recordedBroadcastUri(context, file), Intent.FLAG_GRANT_READ_URI_PERMISSION)
})
