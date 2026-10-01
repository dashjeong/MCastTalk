package app.guidecast.transmitter

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.speech.tts.TextToSpeech
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class SystemVoiceSetupDeviceTest {
    @Test fun actualSettingsRecheckExplainsMissingEngineAndPreservesOperatorControl() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("This failure-path fixture requires an emulator without a TTS engine",
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isEmpty())
        val app = context.applicationContext as GuideCastApplication
        val device = UiDevice.getInstance(instrumentation)
        val previous = app.operatorSettings.state.value
        val previousVoice = app.speechSynthesisProvider.voicePreference("zh")
        instrumentation.runOnMainSync {
            app.operatorSettings.restore(previous.copy(targetLanguageTags = listOf("zh"), automaticPreparation = false))
            app.speechSynthesisProvider.setVoicePreference("zh", SpeechVoicePreference.GOOGLE)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            lateinit var model: AudioInputViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(activity)[AudioInputViewModel::class.java] }
            withTimeout(40_000) { model.translationModelState.first { !it.isBusy && it.selectedLanguageTags == setOf("zh") } }
            device.clickTextControl("설정")
            device.clickTextControl("음성팩 설치 · 복구")
            assertNotNull(device.findTextByVerticalScroll("Google 음성 엔진 설치"))
            device.clickTextControl("설치한 음성 다시 확인")
            withTimeout(60_000) {
                model.translationModelState.first { !it.isBusy && it.ttsUnavailableReason("zh")?.contains("통역 음성 준비 실패") == true }
            }
            assertNotNull(device.findTextByVerticalScroll("통역 음성 · 음성 준비 실패 · 설치/설정 확인"))
            assertFalse(model.translationModelState.value.ttsUnavailableReason("zh")!!.contains("설치된 오프라인 음성 선택"))
            assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
            assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)
            device.takeScreenshot(File(context.getExternalFilesDir(null), "voice-setup-actual-settings.png"))
            device.pressBack()
            assertNotNull(device.findTextByVerticalScroll("언어·모델 설정"))
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                app.speechSynthesisProvider.setVoicePreference("zh", previousVoice)
                app.operatorSettings.restore(previous)
            }
        }
    }

    @Test fun installerIntentTargetsChosenEngineAndHasSettingsFallback() {
        for (engine in listOf("com.google.android.tts", "com.samsung.SMT")) {
            val routes = systemVoiceSetupIntents(engine)
            assertEquals(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, routes.first().action)
            assertEquals(engine, routes.first().`package`)
            assertEquals("com.android.settings.TTS_SETTINGS", routes.last().action)
            assertNull(routes.last().`package`)
        }
    }

    @Test fun canceledInstallerReturnsToRecheckWithoutClaimingReadyAtLargeFont() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val callbacks = AtomicInteger()
        // Register external-activity monitors only after our own explicit activity has launched.
        // An IntentFilter monitor can match an explicit intent with a null action.
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        // Intercept the external activity: proves Android result wiring, not a vendor download.
        val canceled = Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        val installMonitor = instrumentation.addMonitor(IntentFilter(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), canceled, true)
        val storeFilter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("https")
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val storeMonitor = instrumentation.addMonitor(storeFilter, canceled, true)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, 2f)) {
                        GuideCastTheme {
                            Box(Modifier.size(360.dp, 580.dp).verticalScroll(rememberScrollState())) {
                                SystemVoiceSetupActions("중국어(간체)", SpeechVoicePreference.GOOGLE, true) {
                                    callbacks.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            }
            instrumentation.waitForIdleSync()
            val action = device.findTextByVerticalScroll("Google 음성 엔진 설치")
                ?: device.findTextByVerticalScroll("Google 음성팩 설치 · 설정")
                ?: device.findTextByVerticalScroll("Samsung 음성팩 설치 · 설정")
            assertNotNull("An installed engine or Google installation must be actionable", action)
            device.clickTextControl(action!!.text)
            device.wait(Until.hasObject(By.textContains("기기 설정에서 돌아왔습니다")), 5_000)
            assertEquals("Cancellation still requires a real capability recheck", 1, callbacks.get())
            assertNull(device.findObject(By.text("기기 음성 준비됨")))
            assertTrue(installMonitor.hits + storeMonitor.hits >= 1)
            device.clickTextControl("설치한 음성 다시 확인")
            instrumentation.waitForIdleSync()
            assertEquals(2, callbacks.get())
            device.takeScreenshot(File(context.getExternalFilesDir(null), "voice-setup-large-font.png"))
        } finally {
            instrumentation.removeMonitor(installMonitor)
            instrumentation.removeMonitor(storeMonitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
