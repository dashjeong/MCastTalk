package app.guidecast.transmitter

import android.content.Intent
import android.Manifest
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    @Test fun recordingRemainsVisibleAndRecoverableWhenOpeningTheTestTab() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val device = UiDevice.getInstance(instrumentation)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        lateinit var model: VoiceNoteViewModel
        instrumentation.runOnMainSync { model = ViewModelProvider(activity)[VoiceNoteViewModel::class.java] }
        var savedId: String? = null
        try {
            device.openServiceWorkspace(MCastService.NOTES)
            withTimeout(35_000L) { model.state.first { !it.unavailable } }
            device.clickTextControl("녹음만")
            device.clickTextControl("녹음만 시작")
            withTimeout(10_000L) { model.state.first { it.recording } }
            val elapsed = model.state.value.elapsedMs
            device.clickTextControl("시험")
            val notice = "음성 노트 녹음 중 · 녹음을 종료·저장한 뒤 입력·방송을 시작하세요."
            assertTrue("The active microphone owner and next step must stay visible",
                device.wait(Until.hasObject(By.text(notice)), 5_000L))
            device.clickTextControl("입력 시작")
            assertEquals("Another input must not silently replace the note recording", InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
            assertTrue(device.hasObject(By.text(notice)))
            withTimeout(5_000L) { model.state.first { it.recording && it.elapsedMs > elapsed } }
            device.clickTextControl("진행 중인 작업으로 · 음성 노트")
            device.clickTextControl("녹음 종료·저장")
            val completed = withTimeout(15_000L) { model.state.first { !it.recording && !it.busy && it.selected != null } }
            savedId = requireNotNull(completed.selected).id
            assertTrue(requireNotNull(completed.selected).durationMs > 0L)
            device.clickTextControl("시험")
            assertTrue(device.wait(Until.gone(By.text(notice)), 5_000L))
            assertFalse(device.hasObject(By.text("진행 중인 작업으로 · 음성 노트")))
        } finally {
            instrumentation.runOnMainSync { model.cancel() }
            withTimeout(15_000L) { model.state.first { !it.recording && !it.busy } }
            val createdId = savedId ?: model.state.value.selected?.id
            instrumentation.runOnMainSync { activity.finish() }
            createdId?.let { VoiceNoteRepository(File(context.filesDir, "voice-notes")).delete(it) }
        }
    }

    @Test fun workspaceAndCommonTabNavigationPreserveStandaloneAndAudioPreferences() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val device = UiDevice.getInstance(instrumentation)
        val previous = app.operatorSettings.state.value
        try {
            for (translationEnabled in listOf(false, true)) {
                val expected = previous.copy(runMode = BroadcastRunMode.STANDALONE,
                    translationEnabled = translationEnabled, automaticPreparation = false)
                instrumentation.runOnMainSync { app.operatorSettings.restore(expected) }
                val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                try {
                    for (service in MCastService.entries) {
                        device.openServiceWorkspace(service)
                        for (label in listOf("운영", "시험", "설정")) {
                            assertTrue("${service.title} must expose $label", device.hasObject(By.text(label)))
                        }
                        device.clickTextControl("설정")
                        assertTrue(device.wait(Until.hasObject(By.text("언어·모델 설정")), 5_000L))
                        assertNotNull("Language settings must be available from ${service.title}", device.findTextByVerticalScroll("원문 언어"))
                        device.clickTextControl("시험")
                        assertTrue(device.wait(Until.hasObject(By.text("통번역 사전 점검")), 5_000L))
                        device.clickTextControl("운영")
                        instrumentation.waitForIdleSync()
                        assertEquals("Menu navigation must preserve standalone mode", expected.runMode, app.operatorSettings.state.value.runMode)
                        assertEquals("Menu navigation must preserve the audio choice", expected.translationEnabled, app.operatorSettings.state.value.translationEnabled)
                        assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
                        assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)
                        device.pressBack()
                        assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 5_000L))
                    }
                    // Opening settings directly from the home is also an app-level destination.
                    device.clickTextControl("설정")
                    assertNotNull(device.findTextByVerticalScroll("원문 언어"))
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 5_000L))
                } finally { instrumentation.runOnMainSync { activity.finish() } }
            }
        } finally { instrumentation.runOnMainSync { app.operatorSettings.restore(previous) } }
    }

    @Test fun bothBroadcastWorkspacesCanStartStandaloneWithoutTranslationOrInput() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val device = UiDevice.getInstance(instrumentation)
        val previous = app.operatorSettings.state.value
        instrumentation.runOnMainSync { app.operatorSettings.restore(previous.copy(
            runMode = BroadcastRunMode.STANDALONE, translationEnabled = false,
            targetLanguageTags = emptyList(), automaticPreparation = false)) }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            for (service in listOf(MCastService.MULTILINGUAL, MCastService.VOICE)) {
                device.openServiceWorkspace(service)
                device.clickTextControl("단독 사용 시작")
                withTimeout(15_000L) { app.broadcastRuntime.state.first { it.phase == BroadcastPhase.LIVE } }
                assertEquals(BroadcastRunMode.STANDALONE, app.broadcastRuntime.state.value.runMode)
                assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
                assertTrue(app.broadcastRuntime.state.value.translationChannels.isEmpty())
                device.clickTextControl("단독 사용 중지")
                withTimeout(10_000L) { app.broadcastRuntime.state.first { it.phase == BroadcastPhase.IDLE } }
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("오늘은 무엇을 할까요?")), 5_000L))
            }
        } finally {
            BroadcastService.stop(context)
            withTimeout(10_000L) { app.broadcastRuntime.state.first { it.phase == BroadcastPhase.IDLE } }
            instrumentation.runOnMainSync { activity.finish(); app.operatorSettings.restore(previous) }
        }
    }

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
