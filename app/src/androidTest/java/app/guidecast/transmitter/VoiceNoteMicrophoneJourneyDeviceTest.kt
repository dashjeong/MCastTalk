package app.guidecast.transmitter

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Real UI + AudioRecord path. The dedicated emulator receives the public FLEURS PCM through
 * its virtual microphone using EmulatorController.injectAudio; no production input hook.
 * This is not a physical microphone/acoustic test. Run only with the host fixture injector.
 * Platform/UI-only references allow the same test to demonstrate the withdrawn APK's failure.
 */
class VoiceNoteMicrophoneJourneyDeviceTest {
    @Test fun microphoneRecordingShowsAndSavesActualSpeechBeforeStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Requires the dedicated emulator audio injector", args.getString("microphoneFixture") == "fleurs-ko")
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val evidence = requireNotNull(context.getExternalFilesDir(null))
        val marker = File(evidence, "synthetic-note-microphone-ready.txt")
        marker.delete()
        device.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        val title = "마이크 경로 시험 ${System.nanoTime()}"
        try {
            assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 15_000))
            click(find(device, listOf("음성 노트", "녹톡")))
            requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)).text = title
            // The withdrawn UI has only '새 녹음 시작'. Exercising it exposes the missing live text.
            click(find(device, listOf("녹음·받아쓰기 시작", "새 녹음 시작")))
            val stop = device.wait(Until.findObject(By.text("녹음 종료·저장")), 120_000)
            assertNotNull("Recording did not begin after automatic preparation", stop)
            marker.writeText("ready")
            assertTrue("Actual microphone speech must appear while recording is still active",
                device.wait(Until.hasObject(By.textContains("표지판")), 90_000))
            assertTrue(device.wait(Until.hasObject(By.textContains("경고")), 20_000))
            assertTrue("The complete fixture must be recognized before stopping the microphone",
                device.wait(Until.hasObject(By.textContains("주의")), 30_000))
            assertTrue("At least one final sentence must be saved while recording continues",
                device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile("실시간 문장 · [1-9][0-9]*개 저장"))), 30_000))
            assertNotNull("Text arrived only after recording ended", device.findObject(By.text("녹음 종료·저장")))
            device.takeScreenshot(File(evidence, "synthetic-note-microphone-live.png"))
            click(find(device, listOf("녹음 종료·저장")))
            assertTrue(device.wait(Until.gone(By.text("녹음 종료·저장")), 20_000))
            assertNotNull(find(device, listOf("녹음 재생")))
            // The actual saved note opens from the library with its recognized text.
            click(find(device, listOf("보관함 (1)"), prefix = "보관함 ("))
            find(device, listOf(title))
            clickSavedNoteOpen(device, title)
            assertNotNull(find(device, emptyList(), contains = "표지판"))
            device.takeScreenshot(File(evidence, "synthetic-note-microphone-reopened.png"))
        } finally {
            marker.delete()
            device.findObject(By.text("녹음 종료·저장"))?.let(::click)
            device.findObject(By.text("작업 중지"))?.let(::click)
        }
    }

    private fun clickSavedNoteOpen(device: UiDevice, title: String) {
        var previous: Rect? = null
        var stable = 0
        val deadline = SystemClock.uptimeMillis() + 8_000L
        while (SystemClock.uptimeMillis() < deadline) {
            refreshTestAccessibilityCache()
            val bounds = try {
                var row = device.findObject(By.text(title))
                while (row != null && row.findObject(By.text("열기")) == null) row = row.parent
                row?.findObject(By.text("열기"))?.takeIf { it.isEnabled }?.visibleBounds
            } catch (_: StaleObjectException) { null }
            stable = if (bounds != null && !bounds.isEmpty && bounds == previous) stable + 1 else 0
            previous = bounds
            if (stable >= 6) {
                val ready = requireNotNull(bounds)
                assertTrue(device.click(ready.centerX(), ready.centerY()))
                device.waitForIdle()
                refreshTestAccessibilityCache()
                return
            }
            SystemClock.sleep(75L)
        }
        error("Saved note open control did not settle: $title")
    }

    private fun click(label: UiObject2) {
        var target = label
        while (!target.isClickable && target.parent != null) target = requireNotNull(target.parent)
        check(target.isClickable && target.isEnabled) { "Control is not actionable" }
        target.click()
    }

    private fun find(device: UiDevice, labels: List<String>, prefix: String? = null, contains: String? = null): UiObject2 {
        for (direction in listOf(Direction.UP, Direction.DOWN)) repeat(12) {
            device.waitForIdle()
            refreshTestAccessibilityCache()
            labels.firstNotNullOfOrNull { device.findObject(By.text(it)) }?.let { return it }
            if (prefix != null) device.findObject(By.textStartsWith(prefix))?.let { return it }
            if (contains != null) device.findObject(By.textContains(contains))?.let { return it }
            device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
                ?.scroll(direction, .65f)
        }
        error("User control unreachable: ${labels.joinToString()}")
    }
}
