package app.guidecast.transmitter

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.MotionEvent
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

/** Isolated synthetic settings; never reads or replaces the operator's API keys. */
class OnlineServiceSetupUiTest {
    @Test fun consentRetryAndKeyReplacementUseOneSimpleFlow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val prefix = "online-ui-${System.nanoTime()}-"
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(prefix + name, mode)
        }
        val settings = TranslationApiSettings(context)
        settings.configure(onlineServiceChoice(settings.state.value, true))
        settings.useSessionKey("synthetic-ui-key-not-real")
        val checks = AtomicInteger()
        val activity = instrumentation.startActivitySync(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ComponentActivity
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        var lastTap = "not started"
        val touches = CopyOnWriteArrayList<String>()
        val originalCallback = activity.window.callback
        val tapMode = InstrumentationRegistry.getArguments().getString("tapMode", "physical")
        require(tapMode in setOf("physical", "accessibility"))
        fun findText(text: String): UiObject2 {
            var item = device.wait(Until.findObject(By.text(text)), 1_000) ?: device.findObject(By.textStartsWith(text)) ?: device.findObject(By.textContains(text))
            for (attempt in 0..13) {
                val bounds = item?.visibleBounds
                val visible = bounds != null && !bounds.isEmpty
                if (visible && bounds!!.centerY() in device.displayHeight / 10..device.displayHeight * 9 / 10) break
                // A clipped node can sit behind Samsung's navigation buttons or the status bar.
                val down = if (visible) bounds!!.centerY() < device.displayHeight / 10 else attempt >= 7
                device.swipe(device.displayWidth / 2, if (down) device.displayHeight / 3 else device.displayHeight * 3 / 4,
                    device.displayWidth / 2, if (down) device.displayHeight * 3 / 4 else device.displayHeight / 3, 20)
                device.waitForIdle()
                item = device.findObject(By.text(text)) ?: device.findObject(By.textStartsWith(text)) ?: device.findObject(By.textContains(text))
            }
            assertNotNull(text, item)
            return requireNotNull(item)
        }
        fun click(text: String) {
            val node = findText(text)
            val bounds = node.visibleBounds
            touches.clear()
            lastTap = "$text at $bounds; display ${device.displayWidth}x${device.displayHeight}; focused=${activity.hasWindowFocus()}; mode=$tapMode; hold=200ms"
            // A bounded down/up gesture distinguishes a short injected tap from UI dispatch failure.
            // Keep the same coordinates and preserve the operator's device accessibility settings.
            if (tapMode == "accessibility") {
                val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
                check(root.packageName.toString() == target.packageName)
                // Compose virtual nodes need child traversal; native find-by-text can return
                // nothing even when UiAutomator's traversal found the visible label.
                val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
                pending.add(root)
                var action: AccessibilityNodeInfo? = null
                var visited = 0
                while (pending.isNotEmpty() && visited++ < 512) {
                    val candidate = pending.removeFirst()
                    if (candidate.text?.toString() == text) { action = candidate; break }
                    repeat(candidate.childCount) { index -> candidate.getChild(index)?.let(pending::addLast) }
                }
                while (action != null && !action.isClickable) action = action.parent
                assertNotNull("Clickable synthetic node: $text", action)
                assertTrue("Synthetic accessibility action failed", requireNotNull(action).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            } else assertTrue("Synthetic tap injection failed", device.swipe(bounds.centerX(), bounds.centerY(),
                bounds.centerX(), bounds.centerY(), 40))
            device.waitForIdle()
            lastTap += "; touches=${touches.take(24)}; provider=${settings.state.value.provider}; consent=${settings.state.value.allowOnline}; checks=${checks.get()}"
        }
        try {
            instrumentation.runOnMainSync {
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.window.callback = object : Window.Callback by originalCallback {
                    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                        val handled = originalCallback.dispatchTouchEvent(event)
                        if (event.actionMasked != MotionEvent.ACTION_MOVE || touches.none { it.startsWith("2@") }) {
                            if (touches.size == 24) touches.removeAt(0)
                            touches += "${event.actionMasked}@${event.x.toInt()},${event.y.toInt()}:raw=${event.rawX.toInt()},${event.rawY.toInt()}:handled=$handled:source=${event.source}:flags=${event.flags}"
                        }
                        return handled
                    }
                }
                OnlineSetupTestHost.show(activity, settings) {
                    checks.incrementAndGet()
                    OnlineConnectionResult.FAILED
                }
            }
            device.waitForIdle()
            device.takeScreenshot(java.io.File(target.getExternalFilesDir(null), "online-ux-fixture.png"))
            click("연결 확인")
            assertTrue(device.wait(Until.hasObject(By.text("온라인 통역 이용 동의")), 3_000))
            device.takeScreenshot(java.io.File(target.getExternalFilesDir(null), "online-consent-fixture.png"))
            if (InstrumentationRegistry.getArguments().getString("probeOnly") == "true") return
            click("동의하지 않음")
            assertEquals(0, checks.get()); assertTrue(settings.state.value.hasKey)
            click("연결 확인"); click("동의하고 연결 확인")
            assertNotNull(findText(OnlineConnectionResult.FAILED.message))
            assertEquals(1, checks.get()); assertTrue(settings.authorized(settings.state.value))
            click("연결 확인")
            assertEquals(2, checks.get())
            assertFalse(device.hasObject(By.text("온라인 통역 이용 동의")))
            click("API 키 변경"); click("취소")
            assertEquals("synthetic-ui-key-not-real", settings.key(settings.state.value))
            click("서비스 · Google Gemini"); click("OpenAI")
            assertFalse(settings.state.value.allowOnline)
            click("서비스 · OpenAI"); click("Gemini")
            assertTrue(settings.state.value.hasKey)
            click("연결 확인")
            assertTrue(device.wait(Until.hasObject(By.text("온라인 통역 이용 동의")), 3_000))
            click("동의하지 않음")
            // A text route in the same family must still allow choosing the advertised Live route.
            instrumentation.runOnMainSync {
                settings.configure(TranslationApiOptions(provider = TranslationApiProvider.GEMINI,
                    model = "gemini-3.5-flash-lite", baseUrl = "https://generativelanguage.googleapis.com/v1beta"))
            }
            device.waitForIdle()
            click("서비스 · Google Gemini")
            assertTrue(device.wait(Until.hasObject(By.text("통역 서비스 선택")), 3_000))
            click("Gemini")
            assertEquals(TranslationApiProvider.GEMINI_LIVE, settings.state.value.provider)
            click("말투를 지원하는 Live로 전환")
            assertEquals(GEMINI_LIVE_AGENT, settings.state.value.model)
            click("문어체")
            assertEquals(app.guidecast.core.translation.TranslationStyle.FORMAL, settings.state.value.tone)
            click("오프라인"); click("온라인")
            assertEquals(GEMINI_LIVE_AGENT, settings.state.value.model)
            assertEquals(app.guidecast.core.translation.TranslationStyle.FORMAL, settings.state.value.tone)
            assertFalse(settings.state.value.allowOnline)
        } catch (failure: Throwable) {
            device.takeScreenshot(java.io.File(target.getExternalFilesDir(null), "online-ui-failure-fixture.png"))
            throw AssertionError("Synthetic UI action: $lastTap", failure)
        } finally {
            instrumentation.runOnMainSync { activity.window.callback = originalCallback; activity.finish() }
            target.deleteSharedPreferences(prefix + "translation_api")
            target.deleteSharedPreferences(prefix + "translation_api_credentials")
        }
    }
}
