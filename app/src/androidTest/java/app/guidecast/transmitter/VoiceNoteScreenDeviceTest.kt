package app.guidecast.transmitter

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class VoiceNoteScreenDeviceTest {
    @Test fun exportsAndSpeakerEditorRemainReachableInLargeFontAndShortViewport() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val repository = VoiceNoteRepository(File(context.filesDir, "voice-notes"))
        val note = repository.create("합성 접근성 시험", "en-US", "ko-KR")
        repository.save(note.copy(interrupted = false, durationMs = 1_000,
            lines = listOf(VoiceNoteLine(0, 1_000, "Synthetic example", "en-US", "합성 예문"))))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        try {
            val model = withContext(Dispatchers.Main) { ViewModelProvider(activity)[VoiceNoteViewModel::class.java] }
            withTimeout(35_000) { model.state.first { !it.unavailable } }
            withContext(Dispatchers.Main) { model.open(note.id) }
            withTimeout(10_000) { model.state.first { it.selected?.id == note.id && !it.busy } }
            var expectedOriginal = "Synthetic example"
            for ((height, scale) in listOf(640 to 2f, 320 to 1f)) {
                instrumentation.runOnMainSync {
                    activity.setContent {
                        key(height, scale) {
                            CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, scale)) {
                                GuideCastTheme { Box(Modifier.size(360.dp, height.dp)) { VoiceNoteRoute(model, onBack = {}) } }
                            }
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                val originalText = device.findTextByVerticalScroll(expectedOriginal)
                assertNotNull("Original text must be visible in default view at $height dp / font $scale", originalText)
                assertNull("Speaker editor must not be reachable in default view", device.findObject(By.text("화자 이름")))
                assertNull("Sentence editor must not be reachable in default view", device.findObject(By.text("문장 수정")))
                assertNull("Play from position must not be reachable in default view", device.findObject(By.text("여기서 듣기")))
                val play = requireNotNull(device.findObject(By.text("녹음 재생"))) {
                    "Primary playback must remain visible while reading the transcript"
                }
                assertTrue("Playback must stay inside the viewport", play.visibleBounds.bottom <= device.displayHeight)
                assertNotNull("Overflow must have an accessible name", device.findObject(By.desc("옵션")))
                device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-note-basic-${height}-${scale}.png"))
                val translation = model.state.value.selected!!.lines.first().translation
                if (translation.isNotBlank()) {
                    assertNull("Translation starts hidden", device.findObject(By.text(translation)))
                    device.clickTextControl("⋮")
                    device.clickTextControl("번역문 표시")
                    assertNotNull("Chosen translation must appear below the original", device.findTextByVerticalScroll(translation))
                    device.clickTextControl("⋮")
                    device.clickTextControl("번역문 숨기기")
                    assertTrue(device.wait(Until.gone(By.text(translation)), 5_000))
                }
                device.clickTextControl("⋮")
                device.clickTextControl("노트 도구")
                for (label in listOf("노트 백업·가져오기", "TXT 내려받기", "SRT 내려받기", "Markdown 내려받기", "JSON 내려받기")) {
                    val control = device.findTextByVerticalScroll(label)
                    val bounds = requireNotNull(control) { "Unreachable $label at $height dp / font $scale" }.visibleBounds
                    assertTrue("Clipped control: $label", bounds.width() > 0 && bounds.left >= 0 && bounds.right <= device.displayWidth)
                }
                device.clickTextControl("←")
                assertNotNull("Returning via back must restore transcript text", device.findTextByVerticalScroll(expectedOriginal))
                device.clickTextControl("⋮")
                device.clickTextControl("노트 도구")
                device.clickTextControl("⋮")
                device.clickTextControl("문장 보기")
                device.clickTextControl("⋮")
                device.clickTextControl("상세보기")
                for (label in listOf("여기서 듣기", "화자 이름")) {
                    val control = device.findTextByVerticalScroll(label)
                    val bounds = requireNotNull(control) { "Unreachable $label at $height dp / font $scale" }.visibleBounds
                    assertTrue("Clipped control: $label", bounds.width() > 0 && bounds.left >= 0 && bounds.right <= device.displayWidth)
                }
                // setContent can be idle before Compose has drawn its first frame. Capture
                // after the actual controls were found, so evidence cannot show the old home.
                device.waitForIdle()
                device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-note-${height}-${scale}.png"))
                device.clickTextControl("화자 이름")
                assertTrue(device.wait(Until.hasObject(By.text("이 구간의 화자 이름")), 5_000))
                device.clickTextControl("취소")
                device.clickTextControl("문장 수정")
                requireNotNull(device.findTextByVerticalScroll("원문 수정"))
                val editor = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull()
                requireNotNull(editor) { "Original text editor must be reachable" }.text = "Edited synthetic $height"
                device.clickTextControl("수정 저장")
                withTimeout(10_000) { model.state.first { it.selected?.lines?.first()?.original == "Edited synthetic $height" && !it.busy } }
                assertEquals("Edited synthetic $height", repository.load(note.id).lines.first().original)
                assertEquals("", repository.load(note.id).lines.first().translation)
                expectedOriginal = "Edited synthetic $height"
                device.clickTextControl("⋮")
                device.clickTextControl("기본 보기")
            }
        } catch (error: Throwable) {
            device.takeScreenshot(File(context.getExternalFilesDir(null), "synthetic-note-failure.png"))
            device.dumpWindowHierarchy(File(context.getExternalFilesDir(null), "synthetic-note-failure.xml"))
            throw error
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            repository.delete(note.id)
        }
    }
}
