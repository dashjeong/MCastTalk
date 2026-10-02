package app.guidecast.transmitter

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.guidecast.core.translation.TranslationStyle
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Actual settings navigation and Android document picker on the minified distributable. */
class DomainCorpusUiDeviceTest {
    @Test fun settingsProfileActivationAndDocumentPickerAreReachable() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val device = UiDevice.getInstance(instrumentation)
        assertEquals("Must not interrupt active input", InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
        val oldOptions = app.operatorSettings.state.value
        val name = "UI 시험 ${System.nanoTime()}"
        val originals = app.domainCorpus.profiles().filter { it.active && it.sourceLanguageTag == "ko" && it.targetLanguageTag == "en" }
        val imported = app.domainCorpus.importTxt(ByteArrayInputStream("회의를 시작합니다.\tLet us begin.\n".toByteArray()),
            name, "공개 합성 시험문장", "ko", "en", TranslationStyle.FORMAL) as DomainImportResult.Success
        var activity: android.app.Activity? = null
        try {
            instrumentation.runOnMainSync { app.operatorSettings.restore(oldOptions.copy(automaticPreparation = false)) }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            device.openServiceWorkspace(MCastService.MULTILINGUAL)
            fun tap(text: String) {
                repeat(12) {
                    device.waitForIdle()
                    val node = device.findObject(By.text(text))
                    if (node != null) { node.click(); device.waitForIdle(); return }
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .6f)
                }
                error("Unreachable user control: $text")
            }
            tap("설정")
            tap("도구 · 정보")
            tap("도메인 학습·코퍼스")
            assertTrue(device.wait(Until.hasObject(By.text("템플릿 내려받기")), 10_000))
            val directory = File(context.getExternalFilesDir(null), "benchmark").apply { mkdirs() }
            device.takeScreenshot(File(directory, "domain-ui-header.png"))
            tap("신규 코퍼스 파일 가져오기 (.txt)")
            assertTrue("Android document picker must open", device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 5_000)
                || device.hasObject(By.pkg("com.android.documentsui")))
            device.pressBack()
            // A large-font user returns to the preserved scroll position, not necessarily the title.
            assertTrue(device.wait(Until.hasObject(By.text("신규 코퍼스 파일 가져오기 (.txt)")), 5_000))
            repeat(12) {
                if (device.hasObject(By.desc("도메인 프로필 $name 활성화"))) return@repeat
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .6f)
            }
            requireNotNull(device.findObject(By.desc("도메인 프로필 $name 활성화"))).click()
            withTimeout(5_000) { while (!app.domainCorpus.profiles().single { it.id == imported.profile.id }.active) delay(50) }
            assertEquals("Let us begin.", app.domainCorpus.match("회의를 시작합니다.", "ko-KR", "en", TranslationStyle.FORMAL).exactTranslation)
            device.takeScreenshot(File(directory, "domain-ui-profile.png"))
            requireNotNull(device.findObject(By.desc("도메인 프로필 $name 활성화"))).click()
            withTimeout(5_000) { while (app.domainCorpus.profiles().single { it.id == imported.profile.id }.active) delay(50) }
            device.pressBack()
            // The tools tab label can be above the viewport at 180% font size.
            repeat(12) {
                if (!device.hasObject(By.text("인식 학습·보정"))) {
                    device.findObject(By.scrollable(true))?.scroll(Direction.UP, .6f)
                }
            }
            assertTrue("Back must restore the tools settings page",
                device.hasObject(By.text("인식 학습·보정")))
            tap("도메인 학습·코퍼스")
            tap("← 운영 메뉴")
            device.openServiceWorkspace(MCastService.MULTILINGUAL)
            assertFalse("A new service entry must not restore the dismissed corpus overlay",
                device.hasObject(By.text("템플릿 내려받기")))
            assertTrue(device.wait(Until.hasObject(By.text("설정")), 5_000))
        } finally {
            instrumentation.runOnMainSync { activity?.finish(); app.operatorSettings.restore(oldOptions) }
            app.domainCorpus.remove(imported.profile.id)
            originals.forEach { app.domainCorpus.activate(it.id) }
        }
    }
}
