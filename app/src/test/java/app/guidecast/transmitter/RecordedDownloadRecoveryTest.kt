package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class RecordedDownloadRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recordingId = "f9fc0b18-dfbb-4d43-bcbc-274b816e87da"

    @Test fun finishedExportRestoresByIdentityAcrossStoreRecreation() {
        val root = temporary.newFolder()
        val original = RecordedBroadcastShareStore(root, { 1_000L })
        for (extension in listOf("mp3", "txt")) {
            val staged = original.create(recordingId, extension) { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val restored = RecordedBroadcastShareStore(root, { 2_000L }).resolveDownload(staged.name)
            assertEquals(staged.canonicalFile, restored)
            assertArrayEquals(byteArrayOf(1, 2, 3), requireNotNull(restored).readBytes())
        }
    }

    @Test fun missingEmptyPartialAndOtherExportTypesCannotOpenADestination() {
        val root = temporary.newFolder()
        val store = RecordedBroadcastShareStore(root, { 1_000L })
        val staged = store.create(recordingId, "mp3") { it.writeText("fixture") }
        assertNull(store.resolveDownload(null))
        assertNull(store.resolveDownload(staged.name + ".partial"))
        assertNull(store.resolveDownload(store.create(recordingId, "zip") { it.writeText("fixture") }.name))
        staged.writeBytes(byteArrayOf())
        assertNull(store.resolveDownload(staged.name))
        staged.delete()
        assertNull(store.resolveDownload(staged.name))
    }

    @Test fun pathTraversalAndAbsolutePathsCannotRestoreOperatorFiles() {
        val root = temporary.newFolder()
        val staged = RecordedBroadcastShareStore(root, { 1_000L }).create(recordingId, "txt") { it.writeText("fixture") }
        val store = RecordedBroadcastShareStore(root, { 1_001L })
        for (name in listOf(staged.absolutePath, "../${staged.name}", "folder/${staged.name}", "..", ""))
            assertNull(store.resolveDownload(name))
    }

    @Test fun expiredPreparedFilesRemainUnavailableAtTheRetentionBoundary() {
        val root = temporary.newFolder()
        val staged = RecordedBroadcastShareStore(root, { 1_000L }).create(recordingId, "mp3") { it.writeText("fixture") }
        assertNotNull(RecordedBroadcastShareStore(root, { 1_000L + RecordedBroadcastShareStore.RETENTION_MILLIS - 1 }).resolveDownload(staged.name))
        assertNull(RecordedBroadcastShareStore(root, { 1_000L + RecordedBroadcastShareStore.RETENTION_MILLIS }).resolveDownload(staged.name))
    }

    @Test fun aSymlinkWithAValidExportNameCannotEscapeTheStagingDirectory() {
        val root = temporary.newFolder()
        val outside = temporary.newFile().also { it.writeText("outside fixture") }
        val link = File(root, "$recordingId-1000-${UUID.randomUUID()}.txt")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(RecordedBroadcastShareStore(root, { 1_001L }).resolveDownload(link.name))
    }
}
