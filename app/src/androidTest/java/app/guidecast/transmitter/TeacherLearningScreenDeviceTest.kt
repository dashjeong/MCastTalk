package app.guidecast.transmitter

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TeacherLearningScreenDeviceTest {
    @Test fun userApprovesComparativeEvidenceAndCanConfirmDeactivation(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "comparison-ui-${System.nanoTime()}.db"
        val memory = SentenceTranslationMemory(context, name)
        val report = TeacherLearningReport("d".repeat(64), "ko", "en", TranslationRegister.AUTO,
            "11시에 만나요", "We meet at 12.", "We meet at 11.", setOf(TeacherLearningSignal.NUMBERS), setOf(TeacherLesson.NUMBERS),
            TeacherReviewOutcome.PROPOSED, CloudReviewProvider.OPENAI, "synthetic-openai",
            comparison = ComparativeTeacherEvidence("만날 시간", "약속 안내", CloudReviewProvider.GOOGLE,
                "synthetic-gemini", "We meet at 11.", "We meet at 11.", "We meet at 11.", true, comparativeVersionHash(null)))
        memory.recordTeacherReport(report) { true }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync { activity.setContent { GuideCastTheme { TeacherLearningReportScreen(memory) {} } } }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.text("학습 전후 비교 리포트")), 5_000))
            reveal(device, "승인·적용"); device.clickTextControl("승인·적용")
            withTimeout(5_000) { while (memory.comparativeLesson(report.comparativeKey()) == null) delay(50) }
            assertNull(memory.lookup("ko", "en", TranslationRegister.AUTO, report.original))
            reveal(device, "이 문맥 교정 사용 해제"); device.clickTextControl("이 문맥 교정 사용 해제")
            assertTrue(device.wait(Until.hasObject(By.text("이 문맥의 교정을 끌까요?")), 5_000))
            device.clickTextControl("유지")
            assertNotNull(memory.comparativeLesson(report.comparativeKey()))
            device.clickTextControl("이 문맥 교정 사용 해제")
            assertTrue(device.wait(Until.hasObject(By.text("사용 해제")), 5_000))
            device.clickTextControl("사용 해제")
            withTimeout(5_000) { while (memory.comparativeLesson(report.comparativeKey()) != null) delay(50) }
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "synthetic-comparison-deactivated.png"))
        } finally { instrumentation.runOnMainSync { activity.finish() }; memory.close(); context.deleteDatabase(name) }
    }

    private fun reveal(device: UiDevice, text: String) {
        repeat(12) {
            if (device.hasObject(By.text(text))) return
            device.swipe(device.displayWidth * 9 / 10, device.displayHeight * 8 / 10,
                device.displayWidth * 9 / 10, device.displayHeight * 3 / 10, 25)
            device.wait(Until.hasObject(By.text(text)), 250)
        }
        assertTrue("Missing user action: $text", device.hasObject(By.text(text)))
    }
    @Test fun userReviewsBeforeAfterApprovesAndReturnsWithCorrectionSaved(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "teacher-ui-${System.nanoTime()}.db"
        val memory = SentenceTranslationMemory(context, name)
        val report = TeacherLearningReport("c".repeat(64), "ko", "en", TranslationRegister.AUTO,
            "11시에 만나요", "We meet at 12.", "We meet at 11.", setOf(TeacherLearningSignal.NUMBERS), setOf(TeacherLesson.NUMBERS),
            TeacherReviewOutcome.PROPOSED, CloudReviewProvider.OPENAI, "synthetic-model")
        memory.recordTeacherReport(report) { true }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var returned by mutableStateOf(false)
        try {
            instrumentation.runOnMainSync { activity.setContent { GuideCastTheme {
                if (returned) Text("\n\n실험실 복귀 확인") else TeacherLearningReportScreen(memory) { returned = true }
            } } }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.text("학습 전후 비교 리포트")), 5_000))
            reveal(device, "승인·적용")
            assertTrue(device.hasObject(By.textContains("We meet at 12.")))
            assertTrue(device.hasObject(By.textContains("We meet at 11.")))
            device.findObject(By.text("승인·적용")).click()
            assertTrue(device.wait(Until.hasObject(By.text("승인 후 적용됨")), 5_000))
            assertEquals(report.after, memory.lookup("ko", "en", TranslationRegister.AUTO, report.original)?.corrected)
            device.takeScreenshot(java.io.File(context.getExternalFilesDir(null), "synthetic-teacher-approved.png"))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.textContains("실험실 복귀 확인")), 5_000))
        } finally { instrumentation.runOnMainSync { activity.finish() }; memory.close(); context.deleteDatabase(name) }
    }

    @Test fun astraAvatarShowsGroundedRecoveryAndNavigatesOnlyOnUserAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        var destination by mutableStateOf<AssistantDestination?>(null)
        try {
            instrumentation.runOnMainSync { activity.setContent { GuideCastTheme {
                if (destination != null) Text("\n\n사용자 선택 입력 화면") else OperatorAssistantScreen(
                    BroadcastSnapshot(recognitionErrorMessage = "synthetic error"), TranslationModelUiState(),
                    onNavigate = { destination = it }, onBack = {})
            } } }
            val device = UiDevice.getInstance(instrumentation)
            assertTrue(device.wait(Until.hasObject(By.desc("아스트라 미니미 · MCastTalk 비서 아바타")), 5_000))
            assertNull(destination)
            reveal(device, "입력 화면으로")
            device.findObject(By.text("입력 화면으로")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("사용자 선택 입력 화면")), 5_000))
            assertEquals(AssistantDestination.INPUT, destination)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
