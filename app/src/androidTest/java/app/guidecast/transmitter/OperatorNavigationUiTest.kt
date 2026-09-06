package app.guidecast.transmitter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import app.guidecast.provider.gemma.translation.GemmaModelManager
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals

/**
 * User-path regressions for the operator information architecture.
 *
 * These checks intentionally use UIAutomator against the minified alpha build. They protect the
 * paths a Galaxy operator actually sees instead of relying on composable implementation details.
 */
@RunWith(AndroidJUnit4::class)
class OperatorNavigationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private var activity: Activity? = null

    @Before
    fun setUp() {
        grantRuntimePermissions()
        activity = instrumentation.startActivitySync(
            Intent(targetContext, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        instrumentation.waitForIdleSync()
        assertTrue(
            "운영자 화면이 열리지 않았습니다.",
            device.wait(Until.hasObject(By.text("DMZ 평화걷기 안내 방송")), UI_TIMEOUT_MILLIS),
        )
    }

    @After
    fun tearDown() {
        val app = targetContext.applicationContext as? GuideCastApplication
        try {
            BroadcastService.stop(targetContext)
            if (app != null) {
                runBlocking {
                    withTimeout(5_000L) {
                        app.broadcastRuntime.state.first {
                            it.phase == BroadcastPhase.IDLE && it.inputPhase == InputPhase.IDLE
                        }
                    }
                }
            }
        } finally {
            targetContext.stopService(Intent(targetContext, BroadcastService::class.java))
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test
    fun translationPreferenceSurvivesLanguageEditsWithoutStartingInput() {
        val app = targetContext.applicationContext as GuideCastApplication
        lateinit var vm: AudioInputViewModel
        instrumentation.runOnMainSync {
            vm = ViewModelProvider(requireNotNull(activity) as MainActivity)[AudioInputViewModel::class.java]
        }
        assertNotNull(scrollDownUntilText("공개"))
        assertTrue(waitUntilTextAncestorChecked("공개"))
        fun verifyPreference(expected: Boolean) {
            assertTrue(device.wait(Until.hasObject(By.text("DMZ 평화걷기 안내 방송")), UI_TIMEOUT_MILLIS))
            runBlocking { withTimeout(5_000L) {
                vm.translationModelState.first { !it.isBusy && it.broadcastTranslationEnabled == expected }
            } }
            assertTrue(vm.gemmaState.value.useForTranslation)
            assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
            assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)
        }
        verifyPreference(true)
        instrumentation.runOnMainSync { vm.clearTranslationLanguages(); vm.toggleTranslationLanguage("en") }
        verifyPreference(true)
        instrumentation.runOnMainSync { vm.selectAllTranslationLanguages() }
        verifyPreference(true)
        instrumentation.runOnMainSync { vm.selectSourceLanguage("en-US") }
        verifyPreference(true)
        instrumentation.runOnMainSync { vm.setTranslationBroadcastEnabled(false); vm.selectAllTranslationLanguages() }
        verifyPreference(false) // An explicit original-audio choice must also remain intact.
    }

    @Test
    fun correctionSavesSeparatelyAndRequestedBroadcastDefaultsAreVisible() {
        val app = targetContext.applicationContext as GuideCastApplication
        val profile = "UI 회귀 전용"
        val previousProfile = app.speechCorrections.activeProfile.value
        val line = TranslationTranscriptLine(42, "디엠지 평아 걷기입니다", 123456789,
            isFinal = true, translations = mapOf("en" to "DMZ peace walk"), sourceLanguageTag = "ko-KR")
        runBlocking { app.speechCorrections.selectProfile(profile); app.speechCorrections.clear(profile) }
        try {
            assertNotNull(scrollDownUntilText("공개"))
            assertTrue("새 운영 화면의 공개 기본값이 적용되어야 합니다.", waitUntilTextAncestorChecked("공개"))
            openSection(tabLabel = "운영", heading = "안내방송 운영")
            lateinit var vm: AudioInputViewModel
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(requireNotNull(activity) as MainActivity)[AudioInputViewModel::class.java]
                app.broadcastRuntime.update { it.copy(transcripts = listOf(line)) }
            }
            assertTrue(vm.translationModelState.value.broadcastTranslationEnabled)
            assertTrue(vm.gemmaState.value.useForTranslation)
            assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)
            assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
            tap(requireNotNull(scrollDownUntilText("교정 선택")))
            assertNotNull(device.wait(Until.findObject(By.text("음성인식 문장 교정")), UI_TIMEOUT_MILLIS))
            val field = requireNotNull(device.wait(Until.findObject(By.desc("인식 교정 정답")), UI_TIMEOUT_MILLIS))
            tap(field)
            device.waitForIdle()
            val editable = requireNotNull(
                field.findObject(By.clazz("android.widget.EditText"))
                    ?: device.findObject(By.clazz("android.widget.EditText").focused(true)),
            )
            // ACTION_SET_TEXT did not change this AVD's field in the preceding run.
            // Exercise the real editor's select-all / paste path with a synthetic fixture instead.
            val clipboard = targetContext.getSystemService(android.content.ClipboardManager::class.java)
            instrumentation.runOnMainSync {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("UI test", "디엠지 평화 걷기입니다"))
            }
            try {
                editable.click()
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_A, android.view.KeyEvent.META_CTRL_ON)
                device.pressKeyCode(android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.META_CTRL_ON)
                assertTrue(device.wait(Until.hasObject(By.text("디엠지 평화 걷기입니다")), UI_TIMEOUT_MILLIS))
            } finally {
                instrumentation.runOnMainSync { clipboard.clearPrimaryClip() }
            }
            device.pressBack() // keyboard only
            captureScreen("correction-editor")
            // Dialog actions are fixed below the main-screen scroll helper's safe viewport.
            tap(requireNotNull(device.wait(Until.findObject(By.text("교정 저장")), UI_TIMEOUT_MILLIS)))
            assertTrue(device.wait(Until.gone(By.text("음성인식 문장 교정")), UI_TIMEOUT_MILLIS))
            val saved = runBlocking { app.speechCorrections.list(profile, "ko-KR", "") }.single()
            assertEquals(line.sourceText, saved.recognizedText)
            assertEquals("디엠지 평화 걷기입니다", saved.correctedText)
            assertNull(saved.hint) // Opt-in stays OFF: saving is not automatically training.
            assertEquals(line, app.broadcastRuntime.state.value.transcripts.single())
            assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
            captureScreen("correction-saved")
            openSection(tabLabel = "설정", heading = "언어·모델 설정")
            tap(requireNotNull(scrollDownUntilText("도구 · 정보")))
            tap(requireNotNull(scrollDownUntilText("인식 학습·보정")))
            assertNotNull(device.wait(Until.findObject(By.text("인식 학습·보정")), UI_TIMEOUT_MILLIS))
            captureScreen("correction-manager")
            tap(requireNotNull(scrollDownUntilText("삭제")))
            assertNotNull(device.wait(Until.findObject(By.text("교정 기록 삭제")), UI_TIMEOUT_MILLIS))
            tap(device.findObjects(By.text("삭제")).last())
            assertTrue(device.wait(Until.gone(By.text("교정 기록 삭제")), UI_TIMEOUT_MILLIS))
            assertTrue(runBlocking { app.speechCorrections.list(profile, "ko-KR", "") }.isEmpty())
            val beforeUndo = app.speechCorrections.revision.value
            tap(requireNotNull(scrollDownUntilText("되돌리기")))
            runBlocking { withTimeout(5_000L) { app.speechCorrections.revision.first { it > beforeUndo } } }
            assertEquals(saved, runBlocking { app.speechCorrections.list(profile, "ko-KR", "") }.single())
            assertEquals(line, app.broadcastRuntime.state.value.transcripts.single())
            device.pressBack()
            assertNotNull(device.wait(Until.findObject(By.text("도구 · 정보")), UI_TIMEOUT_MILLIS))
        } finally {
            runBlocking { app.speechCorrections.clear(profile); app.speechCorrections.selectProfile(previousProfile) }
            app.broadcastRuntime.update { it.copy(transcripts = emptyList()) }
        }
    }

    @Test
    fun settingsCategoriesKeepLanguageSelectionAndGlossaryBackNavigation() {
        captureScreen("operation")
        openSection(tabLabel = "시험", heading = "통번역 사전 점검")
        captureScreen("test")
        openSection(tabLabel = "설정", heading = "언어·모델 설정")
        captureScreen("languages")
        lateinit var viewModel: AudioInputViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(requireNotNull(activity) as MainActivity)[AudioInputViewModel::class.java]
        }
        val selected = viewModel.translationModelState.value.selectedLanguageTags
        val applied = (targetContext.applicationContext as GuideCastApplication).gemmaTranslationProvider.modelManager.selectedVariant
        tap(requireNotNull(scrollDownUntilText("AI 모델")))
        captureScreen("models")
        assertNotNull(scrollDownUntilText(GemmaModelVariant.STANDARD.label))
        repeat(6) { scrollBackward() }
        tap(requireNotNull(scrollDownUntilText("도구 · 정보")))
        captureScreen("tools")
        tap(requireNotNull(scrollDownUntilText("번역 용어 사전 · 검색 / 수정 / 일괄 등록")))
        assertNotNull(device.wait(Until.findObject(By.text("번역 용어 사전")), UI_TIMEOUT_MILLIS))
        device.pressBack()
        assertNotNull(device.wait(Until.findObject(By.text("도구 · 정보")), UI_TIMEOUT_MILLIS))
        tap(requireNotNull(scrollDownUntilText("언어 · 음성")))
        assertNotNull(scrollDownUntilText("일본어 · 日本語"))
        assertEquals(selected, viewModel.translationModelState.value.selectedLanguageTags)
        assertEquals(applied, (targetContext.applicationContext as GuideCastApplication).gemmaTranslationProvider.modelManager.selectedVariant)
    }

    @Test
    fun coldStartModelInspectionFinishesAndLanguageSelectionWorks() {
        openSection(tabLabel = "설정", heading = "언어·모델 설정")
        assertNotNull(scrollDownUntilText("일본어 · 日本語"))
        val deadline = SystemClock.uptimeMillis() + 35_000L
        var selectable: UiObject2? = null
        while (SystemClock.uptimeMillis() < deadline && selectable == null) {
            var candidate = device.findObject(By.text("일본어 · 日本語"))
            while (candidate != null) {
                if (candidate.isCheckable && candidate.isEnabled) {
                    selectable = candidate
                    break
                }
                candidate = candidate.parent
            }
            if (selectable == null) SystemClock.sleep(100)
        }
        assertNotNull("초기 모델 확인이 끝나지 않아 언어 선택이 잠겼습니다.", selectable)
        val originallyChecked = requireNotNull(selectable).isChecked
        tap(requireNotNull(selectable))
        assertTrue(waitUntilTextAncestorChecked("일본어 · 日本語", !originallyChecked))
        tap(requireNotNull(device.findObject(By.text("일본어 · 日本語"))))
        assertTrue(waitUntilTextAncestorChecked("일본어 · 日本語", originallyChecked))
    }

    @Test
    fun fiveLanguagePreparationCanBeStoppedAndSelectionRecovers() {
        openSection(tabLabel = "설정", heading = "언어·모델 설정")
        val selectAll = requireNotNull(scrollDownUntilText("추천 5개 선택"))
        assertTrue(device.wait(Until.hasObject(By.text("추천 5개 선택").enabled(true)), 35_000L))
        tap(selectAll)
        val prepare = requireNotNull(scrollDownUntilText("번역 · 음성인식 · Moonshine 음성 준비"))
        lateinit var viewModel: AudioInputViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(requireNotNull(activity) as MainActivity)[AudioInputViewModel::class.java]
        }
        assertTrue("이번 준비 버튼 동작을 이전 준비 결과와 구분할 수 있어야 합니다.",
            viewModel.translationModelState.value.operationLabel != "통번역 준비 중")
        fun preparationFinished(): Boolean = viewModel.translationModelState.value.let {
            !it.isBusy && it.operationLabel == "통번역 준비 중"
        }
        tap(prepare)
        // tap waits for UI idle; cached models can finish before the transient label is read.
        // Require evidence from this activity's ViewModel so an ignored tap cannot pass.
        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (!device.hasObject(By.text("통번역 준비 중")) && !preparationFinished() &&
            SystemClock.uptimeMillis() < deadline
        ) SystemClock.sleep(100)
        assertTrue("준비 단계가 초기 확인과 구분되어야 합니다.",
            device.hasObject(By.text("통번역 준비 중")) || preparationFinished())
        if (!preparationFinished()) {
            val stop = scrollDownUntilText("준비 중지 · 선택 변경")
            if (stop != null && !preparationFinished()) {
                try {
                    tap(stop)
                } catch (stale: StaleObjectException) {
                    if (!preparationFinished()) throw stale
                }
            } else assertTrue("중지 버튼 없이 준비가 계속 진행 중입니다.", preparationFinished())
        }
        // The button changes to "중지 처리 중" before native cleanup finishes. Its old text
        // disappearing (or scrolling off screen) is not evidence of the operation ending.
        val stopDeadline = SystemClock.uptimeMillis() + 15_000L
        while (viewModel.translationModelState.value.isBusy && SystemClock.uptimeMillis() < stopDeadline) {
            SystemClock.sleep(100)
        }
        assertTrue("중지 후 준비 소유권이 반환되어야 합니다: ${viewModel.translationModelState.value}",
            !viewModel.translationModelState.value.isBusy)
        repeat(3) { scrollBackward(); device.waitForIdle() }
        assertNotNull(scrollDownUntilText("일본어 · 日本語"))
        fun languageChoice(): UiObject2? {
            var node = device.findObject(By.text("일본어 · 日本語"))
            while (node != null && !node.isCheckable) node = node.parent
            return node
        }
        var candidate = languageChoice()
        val renderDeadline = SystemClock.uptimeMillis() + 5_000L
        while (candidate?.isEnabled != true && SystemClock.uptimeMillis() < renderDeadline) {
            SystemClock.sleep(100)
            candidate = languageChoice()
        }
        assertTrue("중지 완료 후 일본어 선택이 활성화되어야 합니다.", candidate?.isEnabled == true)
        tap(requireNotNull(candidate))
        assertTrue(waitUntilTextAncestorChecked("일본어 · 日本語", false))
    }

    @Test
    fun settingsLicenseOpensAndBothBackPathsReturnToSettings() {
        openSection(tabLabel = "설정", heading = "언어·모델 설정")

        openLicenseScreen()
        assertNotNull(
            "라이선스 화면에 명시적인 이전 화면 버튼이 없습니다.",
            device.wait(Until.findObject(By.text("← 이전 화면")), UI_TIMEOUT_MILLIS),
        )

        device.pressBack()
        assertSettingsScreenVisible("Android 뒤로가기")

        openLicenseScreen()
        tap(requireNotNull(device.wait(Until.findObject(By.text("← 이전 화면")), UI_TIMEOUT_MILLIS)))
        assertSettingsScreenVisible("화면의 설정 복귀 버튼")
    }

    @Test
    fun testTabShowsInputControlBeforeTranslationTestPanel() {
        openSection(tabLabel = "시험", heading = "통번역 사전 점검")

        val inputControl = requireNotNull(
            device.wait(Until.findObject(By.text("입력 제어")), UI_TIMEOUT_MILLIS),
        ) { "시험 탭 첫 화면에 입력 제어가 없습니다." }
        assertTrue(
            "시험 탭 입력 제어에 현재 상태에 맞는 입력 동작이 없습니다.",
            INPUT_ACTION_LABELS.any { label -> device.hasObject(By.text(label)) },
        )

        val testPanel = device.findObject(By.text("통번역 시험"))
        if (testPanel != null) {
            assertTrue(
                "시험 탭에서 통번역 시험 패널이 입력 제어보다 위에 배치됐습니다. " +
                    "입력=${inputControl.visibleBounds}, 시험=${testPanel.visibleBounds}",
                inputControl.visibleBounds.top < testPanel.visibleBounds.top,
            )
        } else {
            // A compact viewport may compose only the first LazyColumn item. Seeing input control
            // before the first downward scroll, then the test panel after it, proves the same order.
            val revealedTestPanel = scrollDownUntilText("통번역 시험")
            assertNotNull(
                "입력 제어 아래에서 통번역 시험 패널을 찾지 못했습니다.",
                revealedTestPanel,
            )
        }
    }

    @Test
    fun switchingTabsStartsAtTheNewSectionHeading() {
        assertNotNull(
            "운영 화면을 아래로 스크롤하지 못했습니다.",
            scrollDownUntilText("송출 음원"),
        )
        openSection(tabLabel = "설정", heading = "언어·모델 설정")
        assertNotNull(
            "설정 화면을 아래로 스크롤하지 못했습니다.",
            // License now belongs to Tools; exercise the actual language-page scroll here.
            scrollDownUntilText("베트남어 · Tiếng Việt"),
        )
        openSection(tabLabel = "운영", heading = "안내방송 운영")
    }

    @Test
    fun selectedOpenAccessModeSurvivesLiveControlMove() {
        val openMode = scrollDownUntilText("공개")
        assertNotNull("공개 접속 방식을 찾지 못했습니다.", openMode)
        tap(requireNotNull(openMode))
        assertTrue(
            "공개 접속 방식의 선택 상태가 접근성 트리에 표시되지 않습니다.",
            waitUntilTextAncestorChecked("공개"),
        )

        // PIN is opt-in. Exercise both choices and return to the default before starting.
        val micPinToggle = scrollDownUntilText("강사 웹 마이크 PIN 사용")
        assertNotNull("강사 웹 마이크 PIN 선택 옵션을 찾지 못했습니다.", micPinToggle)
        assertTrue("강사 PIN 기본값은 해제여야 합니다.",
            waitUntilTextAncestorChecked("강사 웹 마이크 PIN 사용", expected = false))
        tap(requireNotNull(micPinToggle))
        assertTrue("강사 PIN을 선택할 수 없습니다.",
            waitUntilTextAncestorChecked("강사 웹 마이크 PIN 사용"))
        assertNotNull("PIN 선택 후 입력란이 없습니다.",
            scrollDownUntilText("강사 마이크 PIN (4~8자리)"))
        tap(requireNotNull(scrollDownUntilText("강사 웹 마이크 PIN 사용")))
        assertTrue("강사 PIN을 해제할 수 없습니다.",
            waitUntilTextAncestorChecked("강사 웹 마이크 PIN 사용", expected = false))

        val start = scrollDownUntilText("방송 시작")
        assertNotNull("방송 시작 버튼을 찾지 못했습니다.", start)
        tap(requireNotNull(start))
        assertTrue(
            "방송 카드로 이동한 뒤 공개 접속 방식이 유지되지 않았습니다.",
            device.wait(Until.hasObject(By.text("접속 방식 · 공개")), UI_TIMEOUT_MILLIS),
        )
        val application = targetContext.applicationContext as GuideCastApplication
        val expectedCaFingerprint = application.broadcastRuntime.state.value.caSha256Fingerprint
        assertNotNull(
            "실행 중 서버의 사설 CA 지문이 운영 상태에 전달되지 않았습니다.",
            expectedCaFingerprint,
        )
        val speakerQr = scrollDownUntilText("강사 웹 마이크 연결 QR 열기")
        assertNotNull("강사 웹 마이크 인증서 안내를 열 수 없습니다.", speakerQr)
        tap(requireNotNull(speakerQr))
        // The expanded content may start outside the viewport. Find it with bounded scrolling;
        // never toggle the button again merely because an off-screen node is not yet visible.
        assertNotNull(
            "송출기 화면에 사설 CA 지문 안내가 표시되지 않았습니다.",
            scrollDownUntilText("이 송출기의 설치별 사설 CA · SHA-256"),
        )
        assertTrue(
            "송출기 화면의 지문이 실행 중 서버 지문과 다릅니다.",
            device.wait(
                Until.hasObject(By.text(requireNotNull(expectedCaFingerprint))),
                UI_TIMEOUT_MILLIS,
            ),
        )
        assertTrue(
            "다운로드 인증서 불일치 시 설치 금지 안내가 없습니다.",
            device.wait(
                Until.hasObject(By.textContains("다르면 변조되었거나 다른 송출기용")),
                UI_TIMEOUT_MILLIS,
            ),
        )
        val stop = scrollDownUntilText("방송 중지")
        assertNotNull("활성 방송 카드의 중지 버튼을 찾지 못했습니다.", stop)
        tap(requireNotNull(stop))
    }

    @Test
    fun catalogOfflineDocumentsArePackagedAndReadable() {
        val assets = GUIDECAST_LICENSE_CATALOG.mapNotNull { it.offlineDocumentAsset }.distinct()
        assertTrue("오프라인 라이선스 문서 매핑이 비어 있습니다.", assets.isNotEmpty())
        assets.forEach { assetPath ->
            val byteCount = targetContext.assets.open(assetPath).use { input ->
                var total = 0L
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
            assertTrue("오프라인 라이선스 문서가 비었습니다: $assetPath", byteCount > 0L)
        }
    }

    @Test
    fun licenseDetailOpensPackagedOfflineDocument() {
        openLicenseScreen()
        val search = device.wait(
            Until.findObject(By.clazz("android.widget.EditText")),
            UI_TIMEOUT_MILLIS,
        )
        assertNotNull("라이선스 검색 입력란을 찾지 못했습니다.", search)
        requireNotNull(search).text = "LiteRT-LM Android"
        assertTrue(
            "대형 LiteRT-LM 오프라인 고지를 검색하지 못했습니다.",
            device.wait(Until.hasObject(By.text("LiteRT-LM Android")), UI_TIMEOUT_MILLIS),
        )
        val details = scrollDownUntilText("상세 보기")
        assertNotNull("라이선스 상세 버튼을 찾지 못했습니다.", details)
        tap(requireNotNull(details))

        val offline = scrollDownUntilText("오프라인 전문·고지 보기")
        assertNotNull("패키징된 오프라인 고지 진입점을 찾지 못했습니다.", offline)
        tap(requireNotNull(offline))
        assertTrue(
            "오프라인 고지 화면이 열리지 않았습니다.",
            device.wait(Until.hasObject(By.text("오프라인 고지")), UI_TIMEOUT_MILLIS),
        )
        assertTrue(
            "대형 고지를 비동기로 읽은 뒤 첫 내용을 표시하지 못했습니다.",
            device.wait(Until.hasObject(By.textContains("Apache License")), UI_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun gemmaCandidateSelectionRequiresExplicitApplyAndPreservesOriginalModel() {
        val provider = (targetContext.applicationContext as GuideCastApplication).gemmaTranslationProvider
        val original = provider.modelManager.selectedVariant
        val originalFile = provider.modelManager.modelFile
        val originalSize = originalFile.length()
        val originalModified = originalFile.lastModified()
        try {
            openSection(tabLabel = "설정", heading = "언어·모델 설정")
            tap(requireNotNull(scrollDownUntilText("AI 모델")))
            if (Build.VERSION.SDK_INT < 31) {
                val gpuLabel = requireNotNull(scrollDownUntilText(GemmaModelVariant.GPU_OPTIMIZED.label))
                tap(gpuLabel)
                assertEquals("Unsupported GPU tap must preserve the current model", original,
                    provider.modelManager.selectedVariant)
                if (Build.VERSION.SDK_INT == 29) {
                    assertNotNull(scrollDownUntilText(
                        "Note9 · Android 10 호환: E2B 기본형 CPU 실행. GPU 최적화형은 지원하지 않습니다. " +
                            "방송 전 시험 탭에서 번역·음성과 지연을 확인하세요.",
                    ))
                }
                return
            }
            tap(requireNotNull(scrollDownUntilText(GemmaModelVariant.GPU_OPTIMIZED.label)))
            if (!waitUntilTextAncestorChecked(GemmaModelVariant.GPU_OPTIMIZED.label)) {
                val hierarchy = java.io.ByteArrayOutputStream()
                device.dumpWindowHierarchy(hierarchy)
                error("GPU 선택 실패: selected=${provider.modelManager.selectedVariant}, " +
                    "status=${provider.modelManager.status.value}, UI=$hierarchy")
            }
            assertNotNull(scrollDownUntilText("선택 모델 다운로드·점검 후 적용"))
            assertNotNull(scrollDownUntilText("서명된 모델 목록 가져오기"))
            assertEquals("Radio selection must not apply or download a model", original,
                provider.modelManager.selectedVariant)
            assertEquals(original, GemmaModelManager(targetContext).selectedVariant)
            assertEquals(originalSize, originalFile.length())
            assertEquals(originalModified, originalFile.lastModified())
            openSection(tabLabel = "운영", heading = "안내방송 운영")
            openSection(tabLabel = "설정", heading = "언어·모델 설정")
            tap(requireNotNull(scrollDownUntilText(GemmaModelVariant.STANDARD.label)))
            assertEquals(original, GemmaModelManager(targetContext).selectedVariant)
        } finally {
            if (original.compatibilityIssue(Build.VERSION.SDK_INT) == null) {
                runBlocking { provider.selectModel(original) }
            }
        }
    }

    @Test
    fun selectiveTranslationRefinementIsExplicitAndDoesNotStartRuntime() {
        val app = targetContext.applicationContext as GuideCastApplication
        lateinit var viewModel: AudioInputViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(
                requireNotNull(activity) as MainActivity,
            )[AudioInputViewModel::class.java]
        }
        assertEquals(false, viewModel.gemmaState.value.selectiveTranslationRefinement)
        assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
        assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)

        openSection(tabLabel = "설정", heading = "언어·모델 설정")
        tap(requireNotNull(scrollDownUntilText("AI 모델")))
        assertNotNull(scrollDownUntilText("선택적 번역 보완 (시험)"))
        assertNotNull(
            scrollDownUntilText(
                "ML Kit 초안 중 용어·숫자·긴 문장만 Gemma가 검토합니다. " +
                    "보완 실패 시 초안을 한 번만 송출합니다. 지연·품질은 시험 후 판단하세요.",
            ),
        )
        assertTrue(waitUntilTextAncestorChecked("선택적 번역 보완 (시험)", expected = false))

        tap(requireNotNull(scrollDownUntilText("선택적 번역 보완 (시험)")))
        assertTrue(waitUntilTextAncestorChecked("선택적 번역 보완 (시험)"))
        runBlocking {
            withTimeout(5_000L) {
                viewModel.gemmaState.first { it.selectiveTranslationRefinement }
            }
        }
        assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
        assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)
        assertEquals(false, app.broadcastRuntime.state.value.translationTestActive)

        tap(requireNotNull(scrollDownUntilText("선택적 번역 보완 (시험)")))
        assertTrue(waitUntilTextAncestorChecked("선택적 번역 보완 (시험)", expected = false))
        assertEquals(false, viewModel.gemmaState.value.selectiveTranslationRefinement)
        assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)
        assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)

        try {
            instrumentation.runOnMainSync {
                app.broadcastRuntime.update { it.copy(inputPhase = InputPhase.ACTIVE) }
            }
            val disableDeadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS
            var refinementControl: UiObject2? = null
            while (SystemClock.uptimeMillis() < disableDeadline) {
                var candidate = device.findObject(By.text("선택적 번역 보완 (시험)"))
                while (candidate != null && !candidate.isCheckable) candidate = candidate.parent
                if (candidate?.isEnabled == false) {
                    refinementControl = candidate
                    break
                }
                SystemClock.sleep(100L)
            }
            assertNotNull("입력 동작 중 선택적 보완 제어가 비활성화되지 않았습니다.", refinementControl)
            instrumentation.runOnMainSync { viewModel.setSelectiveTranslationRefinement(true) }
            assertEquals(false, viewModel.gemmaState.value.selectiveTranslationRefinement)
            assertEquals(BroadcastPhase.IDLE, app.broadcastRuntime.state.value.phase)
        } finally {
            instrumentation.runOnMainSync {
                app.broadcastRuntime.update { it.copy(inputPhase = InputPhase.IDLE) }
            }
        }
    }

    private fun openSection(tabLabel: String, heading: String) {
        val tab = device.wait(Until.findObject(By.text(tabLabel)), UI_TIMEOUT_MILLIS)
        assertNotNull("$tabLabel 하단 메뉴를 찾지 못했습니다.", tab)
        tap(requireNotNull(tab))
        assertTrue(
            "$tabLabel 화면 제목 '$heading'이 표시되지 않았습니다.",
            device.wait(Until.hasObject(By.text(heading)), UI_TIMEOUT_MILLIS),
        )
    }

    private fun captureScreen(name: String) {
        device.waitForIdle()
        val destination = java.io.File(targetContext.getExternalFilesDir(null), "032-$name.png")
        assertTrue("화면 캡처 실패: $destination", device.takeScreenshot(destination))
    }

    private fun openLicenseScreen() {
        if (!device.hasObject(By.text("언어·모델 설정"))) {
            openSection(tabLabel = "설정", heading = "언어·모델 설정")
        }
        repeat(6) { scrollBackward() }
        tap(requireNotNull(scrollDownUntilText("도구 · 정보")))
        val license = scrollDownUntilDescription("설정 메뉴 라이선스 열기")
        assertNotNull("설정 화면에서 라이선스 메뉴를 찾지 못했습니다.", license)
        tap(requireNotNull(license))
        assertTrue(
            "라이선스 화면이 열리지 않았습니다.",
            device.wait(Until.hasObject(By.text("오픈소스와 이용조건")), UI_TIMEOUT_MILLIS),
        )
        assertTrue(
            "라이선스 화면에 패키지·버전·라이선스 검색이 없습니다.",
            device.wait(
                Until.hasObject(By.text("패키지·버전·라이선스 검색")),
                UI_TIMEOUT_MILLIS,
            ),
        )
        assertTrue(
            "라이선스 화면에 제품 제작 고지가 없습니다.",
            device.wait(
                Until.hasObject(By.text(GUIDECAST_PRODUCT_ATTRIBUTION)),
                UI_TIMEOUT_MILLIS,
            ),
        )
        assertTrue(
            "라이선스 화면에 Moonshine Community License 필수 고지가 없습니다.",
            device.wait(
                Until.hasObject(By.text(MOONSHINE_REQUIRED_ATTRIBUTION)),
                UI_TIMEOUT_MILLIS,
            ),
        )
    }

    private fun assertSettingsScreenVisible(backPath: String) {
        assertTrue(
            "$backPath 후 설정 화면으로 돌아오지 않았습니다.",
            device.wait(
                Until.hasObject(By.desc("설정 메뉴 라이선스 열기")),
                UI_TIMEOUT_MILLIS,
            ),
        )
        assertTrue(
            "$backPath 후 하단 메뉴가 복원되지 않았습니다.",
            device.wait(Until.hasObject(By.text("설정")), UI_TIMEOUT_MILLIS),
        )
    }

    private fun scrollDownUntilText(text: String): UiObject2? {
        repeat(MAX_SCROLL_ATTEMPTS) {
            device.wait(Until.findObject(By.text(text)), 300L)?.let { node ->
                when {
                    node.visibleBounds.centerY() in safeTapTop()..safeTapBottom() -> return node
                    node.visibleBounds.centerY() > safeTapBottom() -> scrollTargetUp()
                    else -> scrollTargetDown()
                }
                device.waitForIdle()
                return@repeat
            }
            scrollForward()
            device.waitForIdle()
        }
        if (device.findObject(By.text(text)) == null) {
            repeat(MAX_SCROLL_ATTEMPTS / 2) {
                scrollBackward()
                device.waitForIdle()
                device.findObject(By.text(text))?.let { node ->
                    when {
                        node.visibleBounds.centerY() in safeTapTop()..safeTapBottom() -> return node
                        node.visibleBounds.centerY() > safeTapBottom() -> scrollTargetUp()
                        else -> scrollTargetDown()
                    }
                    device.waitForIdle()
                    return@repeat
                }
            }
        }
        return device.findObject(By.text(text))?.takeIf(::isTapSafe)
    }

    private fun scrollDownUntilDescription(description: String): UiObject2? {
        repeat(MAX_SCROLL_ATTEMPTS) {
            device.wait(Until.findObject(By.desc(description)), 300L)?.let { node ->
                when {
                    node.visibleBounds.centerY() in safeTapTop()..safeTapBottom() -> return node
                    node.visibleBounds.centerY() > safeTapBottom() -> scrollTargetUp()
                    else -> scrollTargetDown()
                }
                device.waitForIdle()
                return@repeat
            }
            scrollForward()
            device.waitForIdle()
        }
        if (device.findObject(By.desc(description)) == null) {
            repeat(MAX_SCROLL_ATTEMPTS / 2) {
                scrollBackward()
                device.waitForIdle()
                device.findObject(By.desc(description))?.let { node ->
                    when {
                        node.visibleBounds.centerY() in safeTapTop()..safeTapBottom() -> return node
                        node.visibleBounds.centerY() > safeTapBottom() -> scrollTargetUp()
                        else -> scrollTargetDown()
                    }
                    device.waitForIdle()
                    return@repeat
                }
            }
        }
        return device.findObject(By.desc(description))?.takeIf(::isTapSafe)
    }

    private fun isTapSafe(node: UiObject2): Boolean =
        node.visibleBounds.centerY() in safeTapTop()..safeTapBottom()

    private fun safeTapTop(): Int = device.displayHeight / 5

    private fun safeTapBottom(): Int = device.displayHeight * 5 / 6

    private fun scrollForward() {
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 3,
            SWIPE_STEPS,
        )
    }

    private fun scrollBackward() {
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight / 3,
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            SWIPE_STEPS,
        )
    }

    private fun scrollTargetUp() {
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 2,
            SWIPE_STEPS,
        )
    }

    private fun scrollTargetDown() {
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight / 3,
            device.displayWidth / 2,
            device.displayHeight * 2 / 3,
            SWIPE_STEPS,
        )
    }

    private fun tap(node: UiObject2) {
        // Android 15 can keep a LazyColumn fling active after the accessibility tree looks idle.
        // A tap during that tail only stops scrolling, so wait briefly and read fresh bounds.
        SystemClock.sleep(SCROLL_SETTLE_MILLIS)
        device.waitForIdle()
        val bounds = node.visibleBounds
        assertTrue(
            "화면 좌표를 누르지 못했습니다: $bounds",
            device.click(bounds.centerX(), bounds.centerY()),
        )
        device.waitForIdle()
    }

    private fun waitUntilTextAncestorChecked(text: String, expected: Boolean = true): Boolean {
        val deadline = SystemClock.uptimeMillis() + UI_TIMEOUT_MILLIS
        while (SystemClock.uptimeMillis() < deadline) {
            var node = device.findObject(By.text(text))
            while (node != null) {
                if (node.isCheckable && node.isChecked == expected) return true
                node = node.parent
            }
            SystemClock.sleep(100L)
        }
        return false
    }

    private fun grantRuntimePermissions() {
        instrumentation.uiAutomation.grantRuntimePermission(
            targetContext.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            instrumentation.uiAutomation.grantRuntimePermission(
                targetContext.packageName,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private companion object {
        const val UI_TIMEOUT_MILLIS = 10_000L
        const val MAX_SCROLL_ATTEMPTS = 8
        const val SWIPE_STEPS = 45
        const val SCROLL_SETTLE_MILLIS = 500L
        val INPUT_ACTION_LABELS = listOf(
            "입력 시작",
            "선택 앱 재생음 권한 허용",
            "입력 시작 취소",
            "입력 일시정지",
            "입력 재개",
            "입력 중지",
        )
    }
}
