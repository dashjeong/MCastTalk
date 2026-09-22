package app.guidecast.transmitter

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic text only; these checks neither delete nor export the operator's saved scripts. */
class TranscriptScreensDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null

    @After
    fun finishActivity() {
        instrumentation.runOnMainSync { activity?.finish() }
    }

    @Test
    fun touchingLiveScriptPausesFollowUntilResumeAndBackPreservesRuntime() {
        val app = context.applicationContext as GuideCastApplication
        val originalPhase = app.broadcastRuntime.state.value.phase
        val originalInputPhase = app.broadcastRuntime.state.value.inputPhase
        var lines by mutableStateOf((1L..20L).map(::line))
        var returned by mutableStateOf(false)
        openActivity()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent {
                GuideCastTheme {
                    if (returned) Text("운영 화면 복귀 확인")
                    else LiveTranscriptScreen(lines, "ko-KR", listOf("en"), "화면 시험",
                        onBack = { returned = true })
                }
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("검증 문장 20")), 5_000L))
        device.findObject(By.text("검증 문장 20")).click()
        assertTrue(device.wait(Until.hasObject(By.textStartsWith("이전 내용 읽는 중")), 5_000L))
        device.swipe(device.displayWidth / 2, device.displayHeight * 35 / 100,
            device.displayWidth / 2, device.displayHeight * 65 / 100, 35)
        instrumentation.runOnMainSync { lines = (1L..30L).map(::line) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250L)
        assertNull("New text stole the manually selected scroll position", device.findObject(By.text("검증 문장 30")))
        device.findObject(By.text("실시간 따라가기")).click()
        assertTrue(device.wait(Until.hasObject(By.text("검증 문장 30")), 5_000L))
        device.pressBack()
        val returnedMarkerVisible = device.wait(Until.hasObject(By.text("운영 화면 복귀 확인")), 5_000L)
        instrumentation.runOnMainSync { assertTrue("Back did not return to the operator screen", returned) }
        assertTrue("Returned operator marker is not visible", returnedMarkerVisible)
        assertEquals(originalPhase, app.broadcastRuntime.state.value.phase)
        assertEquals(originalInputPhase, app.broadcastRuntime.state.value.inputPhase)
    }

    @Test
    fun archiveSessionFilterClearsPreviousPageSelection() {
        val sessions = listOf(
            ArchivedBroadcastSession(20L, 2_000L, "ko-KR", 1, listOf("en")),
            ArchivedBroadcastSession(10L, 1_000L, "ko-KR", 1, listOf("en")),
        )
        val rows = sessions.map { session ->
            ArchivedTranscriptLine(TranscriptArchiveKey(session.sessionId, 1L),
                session.startedAtEpochMillis, "ko-KR", line(session.sessionId).copy(sequence = 1L))
        }
        openActivity()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent {
                GuideCastTheme {
                    // A single-child viewport needs no Column/Arrangement singleton ABI from
                    // the independently optimized target APK. Match the other screen fixtures.
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BroadcastTranscriptArchivePanel(
                        archive = TranscriptArchiveSnapshot(sessions = sessions, revision = 1L),
                        loadPage = { filter ->
                            val visible = rows.filter { filter.sessionId == null || it.key.sessionId == filter.sessionId }
                            TranscriptArchivePage(visible, visible.size)
                        },
                        onDeleteSelected = { error("Selection test must not delete saved scripts") },
                        onDeleteSession = { error("Selection test must not delete saved sessions") },
                        onRetentionPolicyChange = {},
                    )
                    }
                }
            }
        }
        assertTrue(device.wait(Until.hasObject(By.text("현재 페이지 선택")), 5_000L))
        device.findObject(By.text("현재 페이지 선택")).click()
        assertTrue(device.wait(Until.hasObject(By.text("선택 2개 숨기기")), 5_000L))
        device.findObject(By.text("기간 내 전체 방송")).click()
        val olderLabel = "${formatArchiveSessionTime(1_000L)} · ko-KR · 1개"
        assertTrue(device.wait(Until.hasObject(By.text(olderLabel)), 5_000L))
        device.findObject(By.text(olderLabel)).click()
        assertTrue(device.wait(Until.hasObject(By.text("선택 0개 숨기기")), 5_000L))
        assertTrue("Filtered archive row must be reachable by scrolling",
            device.findTextByVerticalScroll("검증 문장 10") != null)
        assertNull(device.findObject(By.text("검증 문장 20")))
    }

    private fun openActivity() {
        activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            // The synthetic return screen has no Scaffold to consume edge-to-edge insets.
            requireNotNull(activity).findViewById<android.view.View>(android.R.id.content)
                .setPadding(0, (48 * context.resources.displayMetrics.density).toInt(), 0, 0)
        }
    }

    private fun line(sequence: Long) = TranslationTranscriptLine(sequence, "검증 문장 $sequence",
        sequence, true, translations = mapOf("en" to "Synthetic sentence $sequence"))
}
