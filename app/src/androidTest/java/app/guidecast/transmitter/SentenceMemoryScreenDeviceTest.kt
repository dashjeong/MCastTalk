package app.guidecast.transmitter

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SentenceMemoryScreenDeviceTest {
    @Test fun aiSentenceCanBeConfirmedAndBackReturnsWithoutLosingSavedCorrection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "sentence-ui-synthetic-${System.nanoTime()}.db"
        val memory = SentenceTranslationMemory(context, name)
        var activity: MainActivity? = null
        try {
            memory.upsert(SentenceMemoryEntry(sourceLanguageTag = "ko", targetLanguageTag = "en",
                translationRegister = TranslationRegister.FORMAL, original = "합성 검증 문장입니다.",
                corrected = "This is a synthetic verification sentence.", origin = SentenceMemoryOrigin.AI))
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            var returned by mutableStateOf(false)
            instrumentation.runOnMainSync {
                // The synthetic return marker needs the inset normally supplied by the settings screen.
                requireNotNull(activity).findViewById<android.view.View>(android.R.id.content)
                    .setPadding(0, (48 * context.resources.displayMetrics.density).toInt(), 0, 0)
                requireNotNull(activity).setContent { GuideCastTheme {
                    if (returned) Text("설정 복귀 확인")
                    else SentenceMemoryScreen(memory) { returned = true }
                } }
            }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.text("내용 확인·적용")), 5_000))
            device.findObject(By.text("내용 확인·적용")).click()
            assertTrue(device.wait(Until.hasObject(By.text("사용자 확인 완료")), 5_000))
            assertEquals(SentenceMemoryOrigin.USER, memory.lookup("ko", "en", TranslationRegister.FORMAL,
                "합성 검증 문장입니다.")?.origin)
            device.pressBack()
            val returnedMarkerVisible = device.wait(Until.hasObject(By.text("설정 복귀 확인")), 5_000)
            instrumentation.runOnMainSync { assertTrue("Back did not return to settings", returned) }
            assertTrue("Returned settings marker is not visible", returnedMarkerVisible)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            memory.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun developerLabContentIsHiddenUntilMasterDisplayIsEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = (context.applicationContext as GuideCastApplication).developerLabSettings
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var enabled by mutableStateOf(false)
        try {
            instrumentation.runOnMainSync {
                activity.setContent { GuideCastTheme {
                    CompositionLocalProvider(LocalDeveloperInfo provides enabled) {
                        DeveloperLabPanel(settings, previewAllowed = false, onBack = {})
                    }
                } }
            }
            instrumentation.waitForIdleSync()
            val device = UiDevice.getInstance(instrumentation)
            assertFalse(device.hasObject(By.text("개발자 실험실")))
            instrumentation.runOnMainSync { enabled = true }
            assertTrue(device.wait(Until.hasObject(By.text("개발자 실험실")), 5_000))
            val consentLabel = By.text("온라인 전송·문장 검토 허용")
            var consentVisible = device.hasObject(consentLabel)
            // This screen also contains horizontal option rows. Scroll its vertical viewport
            // with a user gesture instead of selecting an arbitrary nested scrollable node.
            repeat(12) {
                if (!consentVisible) {
                    device.swipe(device.displayWidth * 9 / 10, device.displayHeight * 8 / 10,
                        device.displayWidth * 9 / 10, device.displayHeight * 3 / 10, 25)
                    consentVisible = device.wait(Until.hasObject(consentLabel), 500)
                }
            }
            val evidence = requireNotNull(context.getExternalFilesDir(null))
            device.takeScreenshot(java.io.File(evidence, "persona-developer-lab-scroll.png"))
            if (!consentVisible) device.dumpWindowHierarchy(java.io.File(evidence, "synthetic-lab-scroll.xml"))
            assertTrue("Online consent must be reachable by scrolling", consentVisible)
            instrumentation.runOnMainSync { enabled = false }
            instrumentation.waitForIdleSync()
            assertFalse(device.hasObject(By.text("개발자 실험실")))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
