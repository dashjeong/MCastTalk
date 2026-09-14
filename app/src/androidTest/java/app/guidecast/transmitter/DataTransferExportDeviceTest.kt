package app.guidecast.transmitter

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Only synthetic ZIPs are published; cleanup addresses only URIs created by this test instance. */
class DataTransferExportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val ownedUris = CopyOnWriteArrayList<Uri>()
    private val privateFiles = mutableListOf<File>()
    private var activity: MainActivity? = null
    private var uiModel: DataTransferViewModel? = null

    @After fun cleanup() {
        instrumentation.runOnMainSync { uiModel?.cancel(); activity?.finish() }
        runBlocking { withTimeout(5_000L) { while (uiModel?.state?.value?.busy == true) delay(10L) } }
        instrumentation.waitForIdleSync()
        ownedUris.forEach { uri ->
            resolver.delete(uri, null, null)
            assertFalse("Synthetic export was not cleaned up", exists(uri))
        }
        privateFiles.forEach(File::delete)
    }

    @Test fun allBackupKindsPublishOnlyInFixedFolderAndPassExistingZipValidator() = runBlocking {
        val media = RecordingMediaStore()
        val store = store(media)
        for (kind in DataTransferKind.entries) fixture(kind).use { backup ->
            val expectedDigest = digest(backup.file.readBytes())
            val saved = store.save(backup)
            assertPublished(saved, backup.file.length())
            assertEquals(expectedDigest, digest(requireNotNull(resolver.openInputStream(saved.uri)).use { it.readBytes() }))
            DataTransferStage(privateFile("synthetic-export-validation-", ".db")).use { stage ->
                requireNotNull(resolver.openInputStream(saved.uri)).use { input ->
                    stage.readZip(input, kind, EmptyCoroutineContext) { }
                }
                assertEquals(backup.records, stage.count)
            }
        }
        assertEquals(3, ownedUris.size)
    }

    @Test fun cancellationRemovesOnlyItsNewPendingRowAndPreservesPublishedBackup() = runBlocking {
        val media = RecordingMediaStore()
        val store = store(media)
        fixture(DataTransferKind.SETTINGS).use { backup ->
            val previous = store.save(backup)
            val originalDigest = digest(requireNotNull(resolver.openInputStream(previous.uri)).use { it.readBytes() })
            try {
                store.save(backup) { written, _ -> if (written > 0) throw CancellationException("Synthetic cancellation") }
                fail("Cancellation must not publish the new ZIP")
            } catch (_: CancellationException) { }
            assertEquals(2, ownedUris.size)
            val incomplete = ownedUris.last()
            assertNotEquals(previous.uri, incomplete)
            assertFalse(exists(incomplete))
            assertPublished(previous, backup.file.length())
            assertEquals(originalDigest, digest(requireNotNull(resolver.openInputStream(previous.uri)).use { it.readBytes() }))
            assertFalse("Cleanup must refuse a completed backup", media.removePending(previous.uri))
            assertTrue(exists(previous.uri))
        }
    }

    @Test fun partialWriteFailureCanBeRetriedWithoutReplacingAnExistingBackup() = runBlocking {
        val media = RecordingMediaStore()
        val store = store(media)
        fixture(DataTransferKind.DICTIONARY).use { backup ->
            val previous = store.save(backup)
            val previousDigest = digest(requireNotNull(resolver.openInputStream(previous.uri)).use { it.readBytes() })
            media.failNextWrite = true
            try {
                store.save(backup)
                fail("A partial write must be reported as failure")
            } catch (_: IOException) { }
            assertEquals(2, ownedUris.size)
            assertFalse(exists(ownedUris.last()))
            assertPublished(previous, backup.file.length())
            val retried = store.save(backup)
            assertNotEquals(previous.uri, retried.uri)
            assertNotEquals(previous.displayName, retried.displayName)
            assertPublished(retried, backup.file.length())
            assertPublished(previous, backup.file.length())
            assertEquals(previousDigest, digest(requireNotNull(resolver.openInputStream(previous.uri)).use { it.readBytes() }))
        }
    }

    @Test fun settingsExportUiShowsFailureThenRetriesSyntheticBackupIntoTheFixedFolder() {
        val media = RecordingMediaStore().apply { failNextWrite = true }
        val store = store(media)
        val model = DataTransferViewModel(context.applicationContext as GuideCastApplication,
            prepareBackup = { kind, _ ->
                assertEquals(DataTransferKind.SETTINGS, kind)
                // This seam never calls PortableSettings.capture or reads real app preferences.
                fixture(kind)
            }, downloadStore = store)
        uiModel = model
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.runOnMainSync {
            val host = requireNotNull(activity)
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    check(modelClass == DataTransferViewModel::class.java)
                    return model as T
                }
            }
            assertSame(model, ViewModelProvider(host, factory)[DataTransferViewModel::class.java])
            host.setContent { GuideCastTheme { DataTransferPanel(onBack = {}) } }
        }
        val device = UiDevice.getInstance(instrumentation)
        val export = By.text("설정 내보내기")
        assertTrue(device.wait(Until.hasObject(export), 5_000L))
        assertTrue(device.findObject(export).isEnabled)
        device.findObject(export).click()
        val failed = awaitMessage(model)
        assertTrue("The failed save must be explained", failed.contains("실패") || failed.contains("못"))
        assertTrue(device.wait(Until.hasObject(By.text(failed)), 5_000L))
        assertEquals(1, ownedUris.size)
        assertFalse(exists(ownedUris.single()))
        val retry = By.text("설정 내보내기 재작업")
        assertTrue(device.wait(Until.hasObject(retry), 5_000L))
        assertTrue(device.findObject(retry).isEnabled)
        device.findObject(retry).click()
        val succeeded = awaitMessage(model, previous = failed)
        assertTrue("The destination folder must be shown", succeeded.contains("MCastTalk/Backups"))
        assertTrue(device.wait(Until.hasObject(By.text(succeeded)), 5_000L))
        assertNull(model.state.value.retryExportKind)
        assertEquals(2, ownedUris.size)
        val successUri = ownedUris.last()
        assertTrue(exists(successUri))
        resolver.query(successUri, arrayOf(MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.RELATIVE_PATH),
            null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(EXPECTED_DIRECTORY, cursor.getString(1))
        }
    }

    private fun awaitMessage(model: DataTransferViewModel, previous: String? = null): String = runBlocking {
        withTimeout(10_000L) {
            while (model.state.value.busy || model.state.value.message == null || model.state.value.message == previous) delay(10L)
            requireNotNull(model.state.value.message)
        }
    }

    private inner class RecordingMediaStore : DownloadBackupMediaStore {
        private val delegate = AndroidDownloadBackupMediaStore(context)
        @Volatile var failNextWrite = false
        override fun createPending(displayName: String): Uri {
            assertTrue("Only synthetic exports may be created by tests", displayName.contains("synthetic"))
            return delegate.createPending(displayName).also(ownedUris::add)
        }
        override fun openOutput(uri: Uri): OutputStream {
            check(uri in ownedUris)
            val output = delegate.openOutput(uri)
            if (!failNextWrite) return output
            failNextWrite = false
            return object : FilterOutputStream(output) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    out.write(bytes, offset, minOf(length, 7))
                    throw IOException("Synthetic partial write failure")
                }
            }
        }
        override fun publish(uri: Uri): Boolean { check(uri in ownedUris); return delegate.publish(uri) }
        override fun removePending(uri: Uri): Boolean { check(uri in ownedUris); return delegate.removePending(uri) }
    }

    private fun store(media: DownloadBackupMediaStore) = DownloadBackupStore(media,
        clockMillis = { 1_700_000_000_000L }, uniqueId = { "synthetic-${UUID.randomUUID()}" })

    private fun assertPublished(saved: DownloadedBackup, expectedBytes: Long) {
        assertTrue(saved.uri in ownedUris)
        assertTrue(saved.displayName.contains("synthetic"))
        assertTrue(saved.displayName.endsWith(".zip"))
        assertFalse(saved.displayName.contains('/'))
        assertFalse(saved.displayName.contains('\\'))
        assertEquals(expectedBytes, saved.bytes)
        resolver.query(saved.uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.SIZE),
            null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(saved.displayName, cursor.getString(0))
            assertEquals(EXPECTED_DIRECTORY, cursor.getString(1))
            assertEquals("application/zip", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            assertEquals(expectedBytes, cursor.getLong(4))
        }
    }

    private fun exists(uri: Uri): Boolean = resolver.query(uri, arrayOf(MediaStore.Downloads._ID),
        null, null, null)?.use { it.moveToFirst() } ?: false

    private fun fixture(kind: DataTransferKind): PreparedLocalBackup {
        val rows = when (kind) {
            DataTransferKind.SETTINGS -> listOf(JSONObject().put("type", "settings").put("developerInfo", false))
            DataTransferKind.DICTIONARY -> listOf(JSONObject().put("type", "memory").put("sourceLanguage", "ko")
                .put("targetLanguage", "en").put("register", "FORMAL").put("original", "합성 백업 검증 문장입니다.")
                .put("corrected", "This is a synthetic backup fixture.").put("origin", "USER").put("updated", 1))
            DataTransferKind.SCRIPTS -> listOf(
                JSONObject().put("type", "session").put("id", 100).put("started", 1).put("language", "ko"),
                JSONObject().put("type", "broadcast").put("session", 100).put("sequence", 0).put("started", 1)
                    .put("language", "ko").put("source", "합성 방송 검증 문장입니다.").put("captured", 0)
                    .put("translations", JSONObject().put("en", "This is a synthetic broadcast fixture.")),
            )
        }
        val records = rows.joinToString(separator = "\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        val file = privateFile("synthetic-export-fixture-", ".zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("records.jsonl")); zip.write(records); zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(DataTransferFormat.manifest(kind, rows.size.toLong(), digest(records)).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return PreparedLocalBackup(file, kind, rows.size.toLong())
    }

    private fun privateFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, context.cacheDir).also(privateFiles::add)

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object { const val EXPECTED_DIRECTORY = "Download/MCastTalk/Backups/" }
}
