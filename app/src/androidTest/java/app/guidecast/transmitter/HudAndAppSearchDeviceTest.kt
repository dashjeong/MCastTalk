package app.guidecast.transmitter

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class HudAndAppSearchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null
    private fun open() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
    }
    @After fun finish() { instrumentation.runOnMainSync { activity?.finish() } }

    @Test fun thousandAppListSearchesByNameAndPackageAndSelectsFilteredResult() {
        val apps = (1..1_000).map { PlaybackTargetApp("test.synthetic.app$it", it, "가상 앱 $it") } +
            PlaybackTargetApp("test.synthetic.video", 1_001, "희망 Video")
        var selected by mutableStateOf<String?>(null)
        open()
        instrumentation.runOnMainSync {
            activity!!.setContent { GuideCastTheme {
                if (selected == null) PlaybackAppPicker(apps, { selected = it }, {})
                else Text("\n\n\n선택 완료 $selected")
            } }
        }
        assertTrue(device.wait(Until.hasObject(By.text("출력 앱 검색")), 5_000))
        device.findObject(By.clazz("android.widget.EditText")).text = "없는항목"
        assertTrue(device.wait(Until.hasObject(By.text("검색 결과가 없습니다.")), 5_000))
        device.findObject(By.clazz("android.widget.EditText")).text = "SYNTHETIC VIDEO"
        assertTrue(device.wait(Until.hasObject(By.text("희망 Video")), 5_000))
        device.findObject(By.text("희망 Video")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("선택 완료 test.synthetic.video")), 5_000))
        assertEquals("test.synthetic.video", selected)
    }

    @Test fun hudShowsOnlyContentSelectsTranslationFollowsNewRowsAndReturnsWithoutStopping() {
        val runtime = (context.applicationContext as GuideCastApplication).broadcastRuntime
        val phase = runtime.state.value.phase
        val input = runtime.state.value.inputPhase
        fun lines(to: Long) = (1L..to).map { TranslationTranscriptLine(sequence = it,
            sourceText = "HUD 원문 $it", isFinal = true, capturedAtElapsedRealtimeNanos = it,
            translations = mapOf("en" to "HUD translation $it")) }
        var rows by mutableStateOf(lines(20))
        var returned by mutableStateOf(false)
        open()
        instrumentation.runOnMainSync {
            activity!!.setContent { GuideCastTheme {
                if (returned) Text("\n\n\nHUD 복귀 완료") else LiveTranscriptHud(rows, listOf("en"), { returned = true })
            } }
        }
        assertTrue(device.wait(Until.hasObject(By.text("HUD 원문 20")), 5_000))
        // Android's first immersive-mode education overlay intercepts app touches.
        device.wait(Until.findObject(By.text("Got it")), 1_500)?.click()
        device.waitForIdle()
        assertFalse(device.hasObject(By.text("HUD translation 20")))
        assertFalse(device.hasObject(By.text("HUD 닫기")))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-hud-source.png"))
        device.findObject(By.text("HUD 원문 20")).click()
        assertTrue(device.wait(Until.hasObject(By.text("번역 en")), 5_000))
        device.findObject(By.text("번역 en")).click()
        device.findObject(By.text("실시간 따라가기")).click()
        assertTrue(device.wait(Until.gone(By.text("HUD 닫기")), 5_000))
        instrumentation.runOnMainSync { rows = lines(21) }
        assertTrue(device.wait(Until.hasObject(By.text("HUD translation 21")), 5_000))
        device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-hud-translation.png"))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.textContains("HUD 복귀 완료")), 5_000))
        assertEquals(phase, runtime.state.value.phase)
        assertEquals(input, runtime.state.value.inputPhase)
    }
}
