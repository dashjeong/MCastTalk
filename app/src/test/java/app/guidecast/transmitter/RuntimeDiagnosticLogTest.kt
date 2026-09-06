package app.guidecast.transmitter

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeDiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun destinationFailureIsNotReportedAsSuccessfulExport() {
        val dir = temporary.newFolder()
        RotatingDiagnosticFile(dir, "main").append("export_fixture")
        val failingDestination = object : OutputStream() {
            override fun write(value: Int) { throw IOException("destination unavailable") }
        }
        assertThrows(IOException::class.java) { RuntimeDiagnosticLog.export(dir, failingDestination) }
    }

    @Test fun emptyDirectoryDoesNotProduceFalseSuccess() {
        assertThrows(IllegalStateException::class.java) {
            RuntimeDiagnosticLog.export(temporary.newFolder(), ByteArrayOutputStream())
        }
    }

    @Test fun failedRotationKeepsSizeBoundAndNextWriteRecovers() {
        val dir = temporary.newFolder()
        val logger = RotatingDiagnosticFile(dir, "main", 1024)
        File(dir, "main.log").writeBytes(ByteArray(1020) { 65 })
        // Non-empty directory deterministically simulates a failed removal, without relying
        // on host permission semantics. Only this test's TemporaryFolder is modified.
        val obstruction = File(dir, "main.2.log").apply { mkdir() }
        val child = File(obstruction, "fixture").apply { writeText("x") }
        assertThrows(IllegalStateException::class.java) { logger.append("blocked") }
        assertEquals(1020L, File(dir, "main.log").length())
        assertTrue(child.delete())
        assertTrue(obstruction.delete())
        logger.append("recovered_next_event")
        assertTrue(File(dir, "main.log").readText().contains("recovered_next_event"))
        assertTrue(dir.listFiles()!!.all { it.length() <= 1024 })
    }

    @Test fun rotationIsBoundedAndRestartPreservesLogs() {
        val dir = temporary.newFolder()
        val logger = RotatingDiagnosticFile(dir, "main", 1024)
        repeat(100) { logger.append("event=$it " + "가".repeat(2000)) }
        assertTrue(dir.listFiles()!!.size <= 3)
        assertTrue(dir.listFiles()!!.all { it.length() <= 1024 })
        RotatingDiagnosticFile(dir, "main", 1024).append("restarted")
        assertTrue(File(dir, "main.log").readText().contains("restarted"))
    }

    @Test fun exportIncludesOnlyOwnedLogsAndDoesNotPersistExceptionText() {
        val dir = temporary.newFolder()
        RuntimeDiagnosticLog.initialize(dir, "test:tts_ja")
        RuntimeDiagnosticLog.failure("tts_failed", IllegalStateException("PRIVATE_TRANSCRIPT_PIN_1234"))
        File(dir, "secret.json").writeText("DO_NOT_EXPORT")
        val output = ByteArrayOutputStream()
        RuntimeDiagnosticLog.export(dir, output)
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("tts_ja.log", zip.nextEntry.name)
            val log = zip.readBytes().toString(Charsets.UTF_8)
            assertTrue(log.contains("IllegalStateException"))
            assertFalse(log.contains("PRIVATE_TRANSCRIPT"))
            assertNull(zip.nextEntry)
        }
    }
}
