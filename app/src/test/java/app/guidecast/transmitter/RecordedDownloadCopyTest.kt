package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class RecordedDownloadCopyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingSourceIsRejectedBeforeTheDestinationIsOpenedOrTruncated() {
        var destinationOpens = 0
        assertThrows(IllegalArgumentException::class.java) {
            copyPreparedRecordedDownload({ null }, { destinationOpens++; ByteArrayOutputStream() }, {})
        }
        assertEquals(0, destinationOpens)
    }

    @Test fun copiesMultipleChunksWithoutChangingBytesAndReportsTheRealMime() {
        val bytes = ByteArray(160_003) { (it % 251).toByte() }
        for (extension in listOf("mp3", "txt")) {
            val source = temporary.newFile("fixture.$extension").also { it.writeBytes(bytes) }
            val output = ByteArrayOutputStream()
            val mime = copyPreparedRecordedDownload({ source }, { output }, {})
            assertArrayEquals(bytes, output.toByteArray())
            assertEquals(if (extension == "mp3") "audio/mpeg" else "text/plain", mime)
        }
    }

    @Test fun cancellationBetweenChunksClosesThePartialOutputAndNeverReportsCompletion() {
        val source = temporary.newFile().also { it.writeBytes(ByteArray(160_003)) }
        var checks = 0
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        assertThrows(CancellationException::class.java) {
            copyPreparedRecordedDownload({ source }, { output }, { if (++checks == 3) throw CancellationException() })
        }
        assertTrue(closed)
        assertEquals(64 * 1024, output.size())
    }

    @Test fun destinationFailurePreservesTheFailureAndClosesTheStream() {
        val source = temporary.newFile().also { it.writeText("fixture") }
        var closed = false
        val error = IOException("synthetic failure")
        val output = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { throw error }
            override fun close() { closed = true }
        }
        assertSame(error, assertThrows(IOException::class.java) { copyPreparedRecordedDownload({ source }, { output }, {}) })
        assertTrue(closed)
    }
}
