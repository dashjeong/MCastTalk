package app.guidecast.transmitter

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Enter through the public home, including cards below a short screen's fold. */
internal fun UiDevice.openServiceWorkspace(service: MCastService) {
    assertTrue(wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 15_000L))
    val selector = By.text(service.title)
    repeat(8) {
        val card = findObject(selector)
        if (card != null) {
            card.click()
            assertTrue(wait(Until.gone(By.text("오늘은 무엇을 할까요?")), 5_000L))
            return
        }
        findObject(By.scrollable(true))?.scroll(Direction.DOWN, .6f)
    }
    fail("Service card is unreachable: ${service.title}")
}

class ServiceHomeDeviceTest {
    @Test fun allWorkspacesReturnHomeWithoutStartingAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val device = UiDevice.getInstance(instrumentation)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 15_000L))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-service-home.png"))
            val runtime = app.broadcastRuntime.state.value
            for (service in listOf(MCastService.NOTES, MCastService.FILES, MCastService.VOICE, MCastService.MULTILINGUAL)) {
                device.openServiceWorkspace(service)
                assertTrue(device.wait(Until.hasObject(By.text(service.title)), 5_000L))
                assertEquals(runtime.phase, app.broadcastRuntime.state.value.phase)
                assertEquals(runtime.inputPhase, app.broadcastRuntime.state.value.inputPhase)
                assertFalse(app.localVoiceNoteWorkActive.value)
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 5_000L))
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
