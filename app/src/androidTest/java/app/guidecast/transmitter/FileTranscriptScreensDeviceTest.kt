package app.guidecast.transmitter

import android.content.Intent
import android.os.SystemClock
import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/** UI contract checks use synthetic files and text; no real file or operator history is read. */
class FileTranscriptScreensDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: MainActivity? = null

    @After fun finish() { instrumentation.runOnMainSync { activity?.finish() } }

    @Test
    fun missingPlatformAutoRecognitionOffersManualAppPathAndAllowsConversion() {
        var state by mutableStateOf(FileTranslationUiState(selectedFileName = "synthetic.wav",
            automaticLanguageSupported = false, fileTranscriptionSupported = true,
            automaticLanguageUnavailableReason = "기기의 자동 파일 음성 인식을 사용할 수 없습니다. 원문 언어를 직접 선택하면 지원되는 앱 모델로 변환합니다."))
        var requested = false
        openActivity()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent { GuideCastTheme {
                FileTranslationScreen(state, onChooseFile = {},
                    onSourceLanguageChange = { state = state.copy(sourceLanguageTag = it,
                        recognitionSupportNotice = "한국어 · 앱의 음성 인식 모델로 변환합니다. 저장된 모델을 재사용합니다.") },
                    onTargetLanguageToggle = {}, onTranslationEngineChange = {}, onConvert = { requested = true }, onCancel = {},
                    onOpenEntry = {}, onDeleteEntry = {}, onBack = {})
            } }
        }
        assertTrue(device.wait(Until.hasObject(By.text("원문 언어를 선택하세요")), 5_000L))
        device.clickTextControl("원문 언어를 선택하세요")
        assertTrue(device.wait(Until.hasObject(By.text("자동 감지 · 현재 기기에서 사용 불가")), 5_000L))
        device.findObjects(By.text("한국어")).first().click()
        instrumentation.waitForIdleSync()
        assertEquals("ko-KR", state.sourceLanguageTag)
        assertTrue(device.wait(Until.hasObject(By.text("한국어 · 앱의 음성 인식 모델로 변환합니다. 저장된 모델을 재사용합니다.")), 5_000L))
        device.clickTextControl("변환하고 보관함에 저장")
        instrumentation.runOnMainSync { assertTrue("Manual source selection must enable the real convert callback", requested) }
    }

    @Test
    fun automaticLanguageIsDefaultAndManualFallbackIsSelectable() {
        var state by mutableStateOf(FileTranslationUiState(selectedFileName = "synthetic.wav"))
        openActivity()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent { GuideCastTheme {
                FileTranslationScreen(state, onChooseFile = {},
                    onSourceLanguageChange = { state = state.copy(sourceLanguageTag = it) },
                    onTargetLanguageToggle = {}, onTranslationEngineChange = {}, onConvert = {}, onCancel = {},
                    onOpenEntry = {}, onDeleteEntry = {}, onBack = {})
            } }
        }
        assertTrue(device.wait(Until.hasObject(By.text("자동 감지 (기본)")), 5_000L))
        device.findObject(By.text("자동 감지 (기본)")).click()
        assertTrue(device.wait(Until.hasObject(By.text("한국어")), 5_000L))
        device.findObjects(By.text("한국어")).first().click()
        instrumentation.waitForIdleSync()
        assertEquals("ko-KR", state.sourceLanguageTag)
        savePersonaScreenshot("persona-file-selection.png")
    }

    @Test
    fun fileScriptManualScrollDoesNotMoveTransportAndFocusedBackPreservesPlayback() {
        val segments = (0L..19L).map {
            FileSpeechSegment(it, it * 1_000L, (it + 1) * 1_000L, "원본 문장 $it", "ko-KR")
        }
        val entry = FileLibraryEntry(id = "synthetic", displayName = "검증 음성.wav",
            uri = "content://synthetic/file", sha256 = "0".repeat(64), durationMs = 20_000L,
            sourceLanguageTag = "ko-KR", createdAtMillis = 0L, segments = segments,
            translations = emptyMap(), qualityNotes = emptyList())
        var state by mutableStateOf(FilePlaybackUiState(entry, positionMs = 15_500L, isPlaying = true, isPrepared = true))
        var returned by mutableStateOf(false)
        openActivity()
        instrumentation.runOnMainSync {
            requireNotNull(activity).setContent { GuideCastTheme {
                if (returned) Text("파일 목록 복귀 확인")
                else FilePlaybackTranscriptScreen(state, onPlayPause = { state = state.copy(isPlaying = !state.isPlaying) },
                    onStop = { state = state.copy(positionMs = 0L, isPlaying = false) },
                    onSeek = { state = state.copy(positionMs = it) }, onPrevious = {}, onNext = {},
                    onRepeatChange = { state = state.copy(repeat = it) },
                    onSpeedChange = { state = state.copy(speed = it) },
                    onTranslationLanguageChange = { state = state.copy(translationLanguageTag = it) },
                    onRelinkFile = {}, onBack = { returned = true })
            } }
        }
        assertTrue(device.wait(Until.hasObject(By.text("원본 문장 15")), 5_000L))
        savePersonaScreenshot("persona-file-playback-default.png")
        device.findObject(By.text("원본 문장 15")).click()
        assertTrue(device.wait(Until.hasObject(By.text("현재 음성 따라가기")), 5_000L))
        instrumentation.runOnMainSync { state = state.copy(positionMs = 19_500L) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200L)
        assertNull(device.findObject(By.text("원본 문장 19")))
        assertTrue(device.hasObject(By.text("일시정지")))
        device.findObject(By.text("현재 음성 따라가기")).click()
        assertTrue(device.wait(Until.hasObject(By.text("원본 문장 19")), 5_000L))
        device.findObject(By.text("헤드업 화면")).click()
        assertTrue(device.wait(Until.hasObject(By.text("재생 화면으로")), 5_000L))
        savePersonaScreenshot("persona-file-headup.png")
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("검증 음성.wav")), 5_000L))
        assertEquals(19_500L, state.positionMs)
        assertTrue(state.isPlaying)
        device.pressBack()
        val returnedMarkerVisible = device.wait(Until.hasObject(By.text("파일 목록 복귀 확인")), 5_000L)
        instrumentation.runOnMainSync { assertTrue("Back did not return to the file list", returned) }
        assertTrue("Returned file-list marker is not visible", returnedMarkerVisible)
    }

    @Test
    fun constrainedPersonaHasReachableTransportSettingsAndDeveloperDetailsAreOptIn() {
        openActivity()
        // Simulated layouts on the emulator, not claims about a physical device or real users.
        for ((height, fontScale) in listOf(740 to 2f, 320 to 1f)) {
            val scriptText = if (height >= 500) "가상 사용자 원문" else "가상 검증용 원문"
            val entry = FileLibraryEntry("f".repeat(64), "가상 사용자 검증.wav", "content://synthetic/persona",
                "f".repeat(64), 10_000L, "ko-KR", 0L,
                segments = listOf(FileSpeechSegment(1L, 0L, 10_000L, scriptText, "ko-KR")))
            var state by mutableStateOf(FilePlaybackUiState(entry, positionMs = 500L, isPrepared = true))
            var developerInfo by mutableStateOf(false)
            val configuration = Configuration(context.resources.configuration).apply {
                screenWidthDp = 360; screenHeightDp = height; this.fontScale = fontScale
            }
            instrumentation.runOnMainSync {
                requireNotNull(activity).setContent {
                    CompositionLocalProvider(LocalConfiguration provides configuration,
                        LocalDensity provides Density(context.resources.displayMetrics.density, fontScale),
                        LocalDeveloperInfo provides developerInfo) {
                        GuideCastTheme {
                            Box(Modifier.size(360.dp, height.dp)) {
                                FilePlaybackTranscriptScreen(state,
                                    onPlayPause = { state = state.copy(isPlaying = !state.isPlaying) },
                                    onStop = { state = state.copy(isPlaying = false, positionMs = 0L) },
                                    onSeek = { state = state.copy(positionMs = it) }, onPrevious = {}, onNext = {},
                                    onRepeatChange = { state = state.copy(repeat = it) },
                                    onSpeedChange = { state = state.copy(speed = it) },
                                    onTranslationLanguageChange = {}, onRelinkFile = {}, onBack = {})
                            }
                        }
                    }
                }
            }
            // Shared transport text can still belong to the previous developer-enabled composition.
            assertTrue("The current persona composition did not become visible",
                device.wait(Until.hasObject(By.text(scriptText)), 5_000L))
            assertTrue(device.hasObject(By.text("재생")))
            assertTrue(device.hasObject(By.text("중지")))
            if (height >= 500) {
                val headUpControl = requireNotNull(device.findObject(By.text("헤드업 화면")))
                assertTrue("Head-up control must fit the viewport without a horizontal swipe",
                    headUpControl.visibleBounds.width() > 0 && headUpControl.visibleBounds.right <= device.displayWidth)
            }
            val script = requireNotNull(device.findObject(By.text(scriptText)))
            assertTrue("A complete script line must remain readable below the fixed controls",
                script.visibleBounds.height() >= (24 * fontScale * context.resources.displayMetrics.density).toInt())
            assertNull(device.findObject(By.textStartsWith("시각 추정")))
            assertNull(device.findObject(By.text(Pattern.compile("번역 \\d+ms"))))
            savePersonaScreenshot(if (fontScale == 2f) "persona-file-font2-360dp.png" else "persona-file-short-viewport.png")
            device.findObject(By.text("재생")).click()
            assertTrue(device.wait(Until.hasObject(By.text("일시정지")), 5_000L))
            device.findObject(By.text("중지")).click()
            assertTrue(device.wait(Until.hasObject(By.text("재생")), 5_000L))
            device.findObject(By.text("재생 설정")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("음성 재생 위치")), 5_000L))
            assertTrue(device.hasObject(By.text("닫기")))
            if (height >= 500) device.findObject(By.text("닫기")).click()
            device.findObject(By.text("헤드업 화면")).click()
            assertTrue(device.wait(Until.hasObject(By.text("재생 화면으로")), 5_000L))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("파일 목록")), 5_000L))
            instrumentation.runOnMainSync { developerInfo = true }
            assertTrue(device.wait(Until.hasObject(By.textStartsWith("시각 추정")), 5_000L))
        }
    }

    private fun openActivity() {
        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            // The synthetic return screen has no Scaffold to consume edge-to-edge insets.
            requireNotNull(activity).findViewById<android.view.View>(android.R.id.content)
                .setPadding(0, (48 * context.resources.displayMetrics.density).toInt(), 0, 0)
        }
    }

    private fun savePersonaScreenshot(name: String) {
        val directory = requireNotNull(context.getExternalFilesDir(null))
        assertTrue("Could not capture synthetic persona screenshot", device.takeScreenshot(File(directory, name)))
    }
}
