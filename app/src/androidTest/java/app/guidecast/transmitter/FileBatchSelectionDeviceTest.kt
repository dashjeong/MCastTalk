package app.guidecast.transmitter

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Local in-memory document provider: never enumerates a user's real files or storage. */
class FileBatchSelectionDeviceTest {
    private data class Document(val id: String, val mime: String, val name: String)

    @Test fun selectedTreeTraversesNestedAudioOnlyAndStopsDirectoryCycles(): Unit = runBlocking {
        val queried = mutableListOf<String>()
        val context = fixtureContext { directory ->
            queried += directory
            when (directory) {
                "selected" -> listOf(Document("audio", "audio/mpeg", "fixture.mp3"),
                    Document("text", "text/plain", "notes.txt"),
                    Document("nested", DocumentsContract.Document.MIME_TYPE_DIR, "nested"))
                "nested" -> listOf(Document("video", "video/mp4", "fixture.mp4"),
                    Document("audio", "audio/mpeg", "fixture.mp3"),
                    Document("selected", DocumentsContract.Document.MIME_TYPE_DIR, "cycle"))
                else -> error("Escaped selected tree")
            }
        }
        val documents = audioDocumentsInFolder(context, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "selected"))
        assertEquals(listOf("audio", "video"), documents.map(DocumentsContract::getDocumentId))
        assertEquals(listOf("selected", "nested"), queried)
    }

    @Test fun oversizedFolderReturnsActionableBoundedError(): Unit = runBlocking {
        val context = fixtureContext { List(MAX_FILE_BATCH_SIZE + 1) { Document("audio-$it", "audio/wav", "$it.wav") } }
        try {
            audioDocumentsInFolder(context, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "selected"))
            fail("A folder over the public batch limit must not enter an unbounded queue")
        } catch (expected: FileTranscriptionException) {
            assertTrue(expected.message.orEmpty().contains("500"))
        }
    }

    @Test fun deeplyNestedFolderStopsBeforeUnboundedTraversal(): Unit = runBlocking {
        var queries = 0
        val context = fixtureContext { id ->
            queries++
            listOf(Document((id.toInt() + 1).toString(), DocumentsContract.Document.MIME_TYPE_DIR, "nested"))
        }
        try {
            audioDocumentsInFolder(context, DocumentsContract.buildTreeDocumentUri(AUTHORITY, "0"))
            fail("Traversal must be bounded even if no audio is encountered")
        } catch (expected: FileTranscriptionException) {
            assertTrue(expected.message.orEmpty().contains("하위 폴더"))
            assertEquals(256, queries)
        }
    }

    private fun fixtureContext(children: (String) -> List<Document>): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val rows = children(DocumentsContract.getDocumentId(uri))
                return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME)).apply {
                    rows.forEach { addRow(arrayOf<Any>(it.id, it.mime, it.name)) }
                }
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(base, ProviderInfo().apply { authority = AUTHORITY; exported = false })
        val resolver = ContentResolver.wrap(provider)
        return object : ContextWrapper(base) { override fun getContentResolver(): ContentResolver = resolver }
    }

    private companion object { const val AUTHORITY = "guidecast.file.batch.fixture" }
}
