package app.guidecast.transmitter

import android.content.ContentValues
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Real DocumentsUI selection, decoder, recognizer, translation, library and media player. */
class FileConversionUserJourneyDeviceTest {
    @Test fun selectedKoreanWaveBecomesTranslatedScriptAndPlaysFromLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        val pcm = instrumentation.context.assets.open("fixtures/fleurs-ko-1959.pcm").use { it.readBytes() }
        val file = File(context.cacheDir, "synthetic-file-journey.wav")
        VoiceNoteWav(file).use { it.append(pcm, pcm.size) }
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val name = "MCastTalk-synthetic-${System.nanoTime()}.wav"
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        FileTranscriptLibrary(context).use { it.delete(hash) } // The fixture must undergo ASR, never reuse a cache.
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        lateinit var model: FileTranslationViewModel
        instrumentation.runOnMainSync {
            model = androidx.lifecycle.ViewModelProvider(activity as MainActivity)[FileTranslationViewModel::class.java]
        }
        try {
            device.openServiceWorkspace(MCastService.FILES)
            device.clickTextControl("음성 파일 선택")
            var document = device.wait(Until.findObject(By.text(name)), 8_000)
            if (document == null) {
                // AOSP DocumentsUI can start in another provider; choose the actual Downloads root.
                device.findObject(By.desc("Show roots"))?.click()
                    ?: device.findObject(By.desc("루트 표시"))?.click()
                (device.wait(Until.findObject(By.text("Downloads")), 2_000)
                    ?: device.findObject(By.text("다운로드")))?.click()
                document = device.wait(Until.findObject(By.text(name)), 8_000)
            }
            assertNotNull("Public WAV must be selectable in Android's document picker", document)
            requireNotNull(document).click()
            assertTrue(device.wait(Until.hasObject(By.text("선택한 파일 1개")), 10_000))
            device.clickTextControl("원문 언어를 선택하세요")
            device.clickTextControl("한국어")
            device.clickTextControl("영어")
            device.clickTextControl("1개 파일 순서대로 변환")
            // Observe persisted results from the real convert button, without injecting script rows.
            val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
            var saved: FileLibraryEntry? = null
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val failed = model.uiState.value.selectedFiles.firstOrNull { it.status == FileConversionStatus.FAILED }
                if (failed != null) {
                    // Production diagnostics include exception types/code locations only, never file content.
                    val diagnostic = File(context.filesDir, "diagnostics/main.log").takeIf { it.exists() }
                        ?.readLines()?.filter { it.contains("file_") }?.takeLast(4)?.joinToString("\n")
                    fail("Actual file conversion failed: ${failed.message}\n$diagnostic")
                }
                saved = FileTranscriptLibrary(context).use { it.load(hash) }
                if (saved?.translations?.get("en")?.any { it.isNotBlank() } == true) break
                Thread.sleep(250)
            }
            val note = requireNotNull(saved) { "The selected file never produced a saved script" }
            assertTrue(note.segments.joinToString(" ") { it.text }.contains("표지판"))
            assertTrue("A real English translation must be saved", note.translations["en"].orEmpty().all { it.isNotBlank() }
                && note.translations["en"].orEmpty().isNotEmpty())
            assertEquals(hash, note.sha256)
            device.clickTextControl("스크립트 재생")
            assertTrue(device.wait(Until.hasObject(By.textContains("표지판")), 10_000))
            device.clickTextControl("재생")
            assertTrue(device.wait(Until.hasObject(By.text("일시정지")), 10_000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-file-conversion-playback.png"))
            device.clickTextControl("일시정지")
            assertTrue(device.wait(Until.hasObject(By.text("재생")), 5_000))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            resolver.delete(uri, null, null)
            file.delete()
            FileTranscriptLibrary(context).use { it.delete(hash) }
        }
    }
}
