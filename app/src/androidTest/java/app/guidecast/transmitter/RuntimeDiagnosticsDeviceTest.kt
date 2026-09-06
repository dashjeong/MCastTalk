package app.guidecast.transmitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeDiagnosticsDeviceTest {
    @Test fun applicationOwnedExportSurvivesActivityRecreationAndReportsDestinationFailure() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as GuideCastApplication
        val exported = File(app.cacheDir, "diagnostic-export-fixture.zip")
        RuntimeDiagnosticLog.record("export_recreation_fixture", critical = true)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { app.exportDiagnosticsTo(Uri.fromFile(exported)) }
            activity.recreate()
            withTimeout(10_000) { while (app.diagnosticExportState.value.busy) delay(20) }
            assertTrue(app.diagnosticExportState.value.message.orEmpty().contains("저장했습니다"))
            ZipInputStream(exported.inputStream()).use { assertNotNull(it.nextEntry) }
            // A nonexistent parent represents a destination/storage failure, not a source log error.
            activity.onActivity { app.exportDiagnosticsTo(Uri.fromFile(File(app.cacheDir,
                "absent-${System.nanoTime()}/log.zip"))) }
            withTimeout(10_000) { while (app.diagnosticExportState.value.busy) delay(20) }
            assertTrue(app.diagnosticExportState.value.message.orEmpty().startsWith("로그 저장 실패"))
        }
    }

    @Test fun installedApplicationWritesAndExportsPrivatePersistentDiagnostics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.filesDir, "diagnostics")
        assertFalse(folder.path.startsWith(context.cacheDir.path))
        RuntimeDiagnosticLog.failure("diagnostic_fixture", IllegalStateException("PRIVATE_SPEECH_NOT_TO_LOG"))
        val output = ByteArrayOutputStream()
        RuntimeDiagnosticLog.export(folder, output)
        var found = false
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.name.contains('/'))
                val text = zip.readBytes().toString(Charsets.UTF_8)
                assertFalse(text.contains("PRIVATE_SPEECH_NOT_TO_LOG"))
                found = found || text.contains("diagnostic_fixture")
            }
        }
        assertTrue("Installed APK did not write diagnostic log", found)
        assertTrue(output.size() < 8 * 1024 * 1024)
    }
}
