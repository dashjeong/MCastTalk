package app.guidecast.transmitter

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.Direction
import app.guidecast.core.translation.GlossaryTerm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.StringWriter

@RunWith(AndroidJUnit4::class)
class GlossaryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as GuideCastApplication).glossary

    @Test fun bundledLanguagesMatchAndEachExportRoundTripsWithoutLoss(): Unit = runBlocking {
        for ((lang, count) in mapOf("en" to 48524, "ja" to 24361, "zh" to 24486)) {
            assertEquals(count, repository.count("ko", lang))
            val writer = StringWriter()
            repository.export("ko", lang, writer)
            val terms = GlossaryCsv.read(writer.toString().reader())
            assertEquals(count, terms.size)
            assertTrue(terms.all { it.sourceLanguage == "ko" && it.targetLanguage == lang })
            assertTrue(terms.any { !it.enabled })
        }
        assertEquals("1100goji Highland", repository.matching("1100고지에 도착했습니다.", "ko-KR", "en").single { it.sourceTerm == "1100고지" }.preferredTerm)
        assertTrue(repository.matching("1100고지에 도착했습니다.", "ko-KR", "nl").isEmpty())
        assertTrue(repository.matching("가령폭포를 봅니다.", "ko-KR", "ja").isNotEmpty())
        for ((lang, name) in listOf("en" to "남대문세무서", "zh" to "구룡포생활문화센터(아라예술촌)")) {
            val base = repository.search("ko", lang, name).single { it.term.sourceTerm == name }
            val candidates = org.json.JSONArray(base.alternatives)
            try {
                for (i in 0 until candidates.length()) {
                    val candidate = GlossaryCsv.spokenCandidate(candidates.getJSONObject(i).getString("value"))
                    repository.save(listOf(base.term.copy(preferredTerm = candidate, enabled = true)))
                    assertEquals(candidate, repository.search("ko", lang, name).single { it.term.sourceTerm == name }.term.preferredTerm)
                }
            } finally { repository.restore(base.term) }
            assertEquals(base.alternatives, repository.search("ko", lang, name).single { it.term.sourceTerm == name }.alternatives)
        }
        assertNull(repository.warning.value)
    }

    @Test fun overlayImportIsAtomicPersistentAndRestorable(): Unit = runBlocking {
        val base = repository.search("ko", "en", "1100고지").first { it.term.sourceTerm == "1100고지" }
        val edited = base.term.copy(preferredTerm = "Highland test label", replacement = "wrong highland")
        try {
            repository.save(listOf(edited))
            assertEquals(edited, repository.search("ko", "en", "1100고지").single { it.term.sourceTerm == "1100고지" }.term)
            val fresh = TranslationGlossaryRepository(context)
            assertEquals(edited.preferredTerm, fresh.matching("1100고지에", "ko-KR", "en").single().preferredTerm)
            try { repository.save(listOf(edited.copy(preferredTerm = "must not commit"), edited.copy(sourceTerm = ""))); fail("Invalid batch was accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals(edited.preferredTerm, repository.matching("1100고지에", "ko", "en").single().preferredTerm)
        } finally { repository.restore(edited) }
        assertEquals(base.term, repository.search("ko", "en", "1100고지").single { it.term.sourceTerm == "1100고지" }.term)
    }

    @Test fun operatorOpensSearchesEditsAndRestoresGlossaryFromSettings(): Unit = runBlocking {
        val device = UiDevice.getInstance(instrumentation)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            assertTrue(device.wait(Until.hasObject(By.text("설정")), 15_000))
            device.findObject(By.text("설정")).click()
            assertTrue(device.wait(Until.hasObject(By.text("번역 용어 사전 · 검색 / 수정 / 일괄 등록")), 10_000))
            device.findObject(By.text("번역 용어 사전 · 검색 / 수정 / 일괄 등록")).click()
            assertTrue(device.wait(Until.hasObject(By.text("원어·번역어 검색")), 10_000))
            device.findObject(By.clazz("android.widget.EditText")).text = "1100고지"
            assertTrue(findByScrolling(device, "1100goji Highland"))
            device.findObject(By.text("1100goji Highland")).click()
            assertTrue(device.wait(Until.hasObject(By.text("권장 번역어")), 5_000))
            device.findObjects(By.clazz("android.widget.EditText"))[1].text = "Highland UI test"
            device.findObject(By.text("저장")).click()
            assertTrue(findByScrolling(device, "Highland UI test"))
        } finally {
            repository.restore(GlossaryTerm("ko", "en", "1100고지", "ignored"))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun findByScrolling(device: UiDevice, text: String, contains: Boolean = false): Boolean {
        val selector = if (contains) By.textContains(text) else By.text(text)
        repeat(6) {
            if (device.wait(Until.hasObject(selector), 700)) return true
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, .8f)
        }
        device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "glossary-ui-failure.png"))
        device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "glossary-ui-failure.xml"))
        return false
    }

    @Test fun documentPickerImportsBatchAndExportsTemplateAndLanguage(): Unit = runBlocking {
        val device = UiDevice.getInstance(instrumentation)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val fixture = GlossaryTerm("ko", "en", "퀀텀 시험정원", "Verification Garden", "quantum garden", "시험", "자동시험")
        try {
            assertTrue(device.wait(Until.hasObject(By.text("설정")), 15_000))
            device.findObject(By.text("설정")).click()
            assertTrue(device.wait(Until.hasObject(By.text("번역 용어 사전 · 검색 / 수정 / 일괄 등록")), 10_000))
            device.findObject(By.text("번역 용어 사전 · 검색 / 수정 / 일괄 등록")).click()
            assertTrue(findByScrolling(device, "CSV 일괄 등록"))
            device.findObject(By.text("CSV 일괄 등록")).click()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10_000))
            device.findObject(By.desc("Show roots"))?.click()
            // The toolbar also says Downloads. Select the drawer's android:id/title item,
            // then wait for the drawer to close before touching the visible file behind it.
            // Android 15 may replace the drawer node while loading storage providers.
            // Reacquire only a stale node; never turn a missing/failed product action into a pass.
            var rootClicked = false
            for (attempt in 1..3) {
                device.waitForIdle()
                val downloads = device.wait(Until.findObject(By.res("android", "title").text("Downloads")), 3_000)
                assertNotNull("Downloads root missing", downloads)
                try {
                    downloads.click()
                    rootClicked = true
                    break
                } catch (e: androidx.test.uiautomator.StaleObjectException) {
                    if (attempt == 3) throw e
                }
            }
            assertTrue(rootClicked)
            assertTrue(device.wait(Until.gone(By.text("Open from")), 5_000))
            // Repeated export tests add newer files; the import fixture can be below the fold.
            assertTrue(findByScrolling(device, "guidecast-glossary-input.csv"))
            device.findObject(By.text("guidecast-glossary-input.csv")).click()
            assertTrue(device.wait(Until.hasObject(By.text("등록 적용")), 8_000))
            device.findObject(By.text("등록 적용")).click()
            assertTrue(findByScrolling(device, "1개 등록 완료", contains = true))
            assertEquals(fixture, repository.search("ko", "en", "퀀텀 시험정원").single().term)
            for ((button, name) in listOf("양식 내려받기" to "template", "이 언어 사전 내려받기" to "export")) {
                // Both actions are in the same tools block; return a little above when needed.
                device.findObject(By.scrollable(true))?.scroll(Direction.UP, .5f)
                assertTrue(findByScrolling(device, button))
                device.findObject(By.text(button)).click()
                assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 10_000))
                device.findObject(By.clazz("android.widget.EditText")).text = "guidecast-$name-${System.currentTimeMillis()}.csv"
                val save = device.wait(Until.findObject(By.res("android", "button1").text("SAVE")), 5_000)
                assertNotNull("DocumentsUI save button missing", save)
                save.click()
                assertTrue(findByScrolling(device, "CSV 저장 완료", contains = true))
            }
        } finally {
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "glossary-documents-state.png"))
            device.dumpWindowHierarchy(java.io.File(context.getExternalFilesDir(null), "glossary-documents-state.xml"))
            // A failed file-selection assertion must not leave a foreign picker covering
            // the next test's Activity. Cancel only this test's DocumentsUI flow.
            if (device.hasObject(By.pkg("com.android.documentsui"))) device.pressBack()
            repository.restore(fixture)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
