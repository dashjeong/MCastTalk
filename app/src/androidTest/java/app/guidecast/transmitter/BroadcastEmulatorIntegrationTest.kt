package app.guidecast.transmitter

import ai.moonshine.voice.TextToSpeech
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.guidecast.core.audio.AudioInputKind
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.server.BroadcastAccess
import app.guidecast.core.server.GuideCastLocalServer
import app.guidecast.core.server.GuideCastServerConfig
import app.guidecast.core.stream.AudioChannelDescriptor
import app.guidecast.core.stream.AudioStreamRegistry
import app.guidecast.core.stream.PcmAudioFrame
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import app.guidecast.provider.moonshine.tts.MoonshineTtsReadiness
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BroadcastEmulatorIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private lateinit var app: GuideCastApplication
    private var activity: Activity? = null

    @Before
    fun setUp() {
        grantRuntimePermissions()
        app = targetContext.applicationContext as GuideCastApplication
        app.audioInputRepository.start()
        app.audioInputRepository.useAutomaticSelection()
        activity = instrumentation.startActivitySync(
            Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        runBlocking {
            withTimeout(15_000) {
                app.audioInputRepository.selectedDevice.first {
                    it != null && it.kind != AudioInputKind.DEVICE_PLAYBACK
                }
            }
        }
    }

    @After
    fun tearDown() {
        if (!::app.isInitialized) return
        try {
            BroadcastService.stop(targetContext)
            runBlocking {
                withTimeout(10_000) {
                    app.broadcastRuntime.state.first {
                        it.phase == BroadcastPhase.IDLE && it.inputPhase == InputPhase.IDLE
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { PlaybackPcmActivity.finishActivePlayback() }
            activity?.finish()
        }
    }

    @Test
    fun installedAppAndOperatorScreenUseDmzPeaceWalkName() {
        val installedLabel = targetContext.applicationInfo
            .loadLabel(targetContext.packageManager)
            .toString()
        assertEquals("DMZ 평화걷기 안내 방송", installedLabel)
        assertTrue(
            "운영 화면에 새 앱 이름이 표시되지 않습니다.",
            UiDevice.getInstance(instrumentation)
                .wait(Until.hasObject(By.text("DMZ 평화걷기 안내 방송")), 5_000),
        )
    }

    @Test
    fun sourceBroadcastServesProtectedWebPlayerAndPcmStream() = runBlocking {
        BroadcastService.startInput(targetContext)
        val input = withTimeout(20_000) {
            app.broadcastRuntime.state.first {
                it.inputPhase == InputPhase.ACTIVE || it.inputPhase == InputPhase.FAILED
            }
        }
        assertEquals(
            input.inputErrorMessage ?: "입력이 ACTIVE 상태가 아닙니다.",
            InputPhase.ACTIVE,
            input.inputPhase,
        )
        assertTrue("마이크 PCM 프레임이 생성되지 않았습니다.", input.inputFrameCount > 0)

        BroadcastService.start(targetContext, OperatorAccessMode.QR_TOKEN)
        val live = withTimeout(30_000) {
            app.broadcastRuntime.state.first {
                it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
            }
        }
        assertEquals(live.errorMessage ?: "방송이 LIVE 상태가 아닙니다.", BroadcastPhase.LIVE, live.phase)
        assertEquals("원음", live.channelSummary)
        assertFalse(live.inputLabel.isNullOrBlank())

        val listenerUri = URI(requireNotNull(live.listenerUrl))
        assertTrue(listenerUri.host.isNotBlank())
        assertFalse(listenerUri.host == "127.0.0.1")
        val token = listenerUri.rawFragment
            ?.substringAfter("token=", missingDelimiterValue = "")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            .orEmpty()
        assertTrue("QR 토큰이 생성되지 않았습니다.", token.length >= 32)

        val page = rawHttpGet(listenerUri, "/")
        assertTrue(page.startsWith("HTTP/1.1 200"))
        assertTrue(page.contains("Content-Security-Policy:", ignoreCase = true))
        assertTrue(page.contains("Cache-Control: no-store", ignoreCase = true))
        assertTrue(page.contains("DMZ 평화걷기 안내 방송"))

        val unauthorized = rawHttpGet(listenerUri, "/api/status")
        assertTrue(unauthorized.startsWith("HTTP/1.1 401"))

        val status = rawHttpGet(listenerUri, "/api/status", bearerToken = token)
        assertTrue(status.startsWith("HTTP/1.1 200"))
        assertTrue(status.contains("\"maxListeners\":50"))
        assertTrue(status.contains("\"id\":\"source\""))
        assertTrue(status.contains("\"sampleRate\":16000"))

        assertWebSocketDeliversPcm(listenerUri, token)
        assertInputAndBroadcastControlsAreIndependent(listenerUri)
        assertPerRemoteListenerLimitPreservesGlobalCapacity(listenerUri, token)
    }

    @Test
    fun operatorCanOpenBroadcastWithoutInputAndVerifyEveryHopWithTestTone() = runBlocking {
        assertEquals(InputPhase.IDLE, app.broadcastRuntime.state.value.inputPhase)

        BroadcastService.start(targetContext, OperatorAccessMode.OPEN)
        val live = withTimeout(30_000) {
            app.broadcastRuntime.state.first {
                it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
            }
        }
        assertEquals(live.errorMessage ?: "입력 없이 방송 서버를 열지 못했습니다.", BroadcastPhase.LIVE, live.phase)
        assertEquals("방송 서버가 입력 상태를 임의로 바꿨습니다.", InputPhase.IDLE, live.inputPhase)
        assertWebSocketDeliversPcm(URI(requireNotNull(live.listenerUrl)), "")
    }

    @Test
    fun translationReadinessNeverBlocksOperatorFromOpeningAndTestingChannel() = runBlocking {
        BroadcastService.start(
            targetContext,
            OperatorAccessMode.OPEN,
            translationLanguages = arrayOf("en"),
            useGemma = true,
        )
        val live = withTimeout(30_000) {
            app.broadcastRuntime.state.first {
                it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
            }
        }
        assertEquals(live.errorMessage ?: "통역 준비 상태와 무관하게 방송을 열어야 합니다.", BroadcastPhase.LIVE, live.phase)
        assertTrue(live.channelSummary.orEmpty().contains("Gemma"))
        val listener = URI(requireNotNull(live.listenerUrl))
        val channelStatus = rawHttpGet(listener, "/api/status?channel=en")
        assertTrue("Translation page must also offer original audio", channelStatus.contains("\"id\":\"source\""))
        assertWebSocketDeliversPcm(URI(requireNotNull(live.listenerUrl)), "", channelId = "en")
        assertWebSocketDeliversPcm(URI(requireNotNull(live.listenerUrl)), "", channelId = "source")
    }

    @Test
    fun sevenTranslationChannelsAndOriginalOpenWithoutStartingMicrophone() = runBlocking {
        val languages = arrayOf("en", "ja", "zh", "zh-TW", "vi", "nl", "es")
        BroadcastService.start(targetContext, OperatorAccessMode.OPEN,
            translationLanguages = languages, useGemma = true)
        val live = withTimeout(30_000) {
            app.broadcastRuntime.state.first {
                it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
            }
        }
        assertEquals(live.errorMessage, BroadcastPhase.LIVE, live.phase)
        assertEquals(InputPhase.IDLE, live.inputPhase)
        val uri = URI(requireNotNull(live.listenerUrl))
        val status = rawHttpGet(uri, "/api/status")
        (languages.toList() + "source").forEach { language ->
            val id = language.lowercase(java.util.Locale.ROOT)
            assertTrue("Missing channel $id", status.contains("\"id\":\"$id\""))
        }
        // Tests the actual APK server/registry, not a claim that untranslated test tones are TTS.
        assertWebSocketDeliversPcm(uri, "", channelId = "es")
        assertWebSocketDeliversPcm(uri, "", channelId = "source")
    }

    @Test
    fun cancelledTranslationPreviewCannotSupersedeNewBroadcastAudioSession() = runBlocking {
        BroadcastService.startInput(targetContext)
        val input = withTimeout(20_000) {
            app.broadcastRuntime.state.first {
                it.inputPhase == InputPhase.ACTIVE || it.inputPhase == InputPhase.FAILED
            }
        }
        assertEquals(input.inputErrorMessage, InputPhase.ACTIVE, input.inputPhase)

        // Start a real preview preparation and replace it immediately. Native preparation can
        // return after cancellation; its private stream registry must never supersede the live
        // server generation that follows.
        BroadcastService.startTranslationTest(targetContext, languageTag = "en", useGemma = false)
        withTimeout(5_000) {
            app.broadcastRuntime.state.first { it.translationTestActive }
        }
        BroadcastService.start(targetContext, OperatorAccessMode.OPEN)
        val live = withTimeout(30_000) {
            app.broadcastRuntime.state.first {
                it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
            }
        }
        assertEquals(live.errorMessage, BroadcastPhase.LIVE, live.phase)
        assertFalse("새 방송이 열린 뒤 통번역 시험이 남아 있습니다.", live.translationTestActive)

        delay(2_000)
        val afterLateCallbacks = app.broadcastRuntime.state.value
        assertEquals(
            "취소된 시험 callback이 새 방송을 종료했습니다: ${afterLateCallbacks.errorMessage}",
            BroadcastPhase.LIVE,
            afterLateCallbacks.phase,
        )
        assertWebSocketDeliversPcm(URI(requireNotNull(afterLateCallbacks.listenerUrl)), "")
    }

    @Test
    fun rapidStartStopRestartWaitsForPortTeardownAndPublishesOnlyNewestSession() = runBlocking {
        repeat(3) { cycle ->
            val previousGeneration = app.audioStreams.observability.value.generation
            BroadcastService.start(targetContext, OperatorAccessMode.OPEN)
            BroadcastService.stopBroadcast(targetContext)
            BroadcastService.start(targetContext, OperatorAccessMode.OPEN)

            val live = awaitStableLiveAfterGeneration(previousGeneration)
            assertEquals(
                "${cycle + 1}회 재시작에서 :8787 소켓/세션 교체에 실패했습니다: " +
                    live.errorMessage,
                BroadcastPhase.LIVE,
                live.phase,
            )
            assertWebSocketDeliversPcm(URI(requireNotNull(live.listenerUrl)), "")
            BroadcastService.stopBroadcast(targetContext)
            withTimeout(10_000) {
                app.broadcastRuntime.state.first { it.phase == BroadcastPhase.IDLE }
            }
        }
    }

    private suspend fun awaitStableLiveAfterGeneration(
        previousGeneration: Long,
    ): BroadcastSnapshot = withTimeout(30_000) {
        while (true) {
            val candidate = app.broadcastRuntime.state.first { state ->
                val stream = app.audioStreams.observability.value
                state.phase == BroadcastPhase.FAILED ||
                    (state.phase == BroadcastPhase.LIVE &&
                        stream.isActive && stream.generation > previousGeneration)
            }
            if (candidate.phase == BroadcastPhase.FAILED) return@withTimeout candidate

            val candidateGeneration = app.audioStreams.observability.value.generation
            val candidateUrl = candidate.listenerUrl
            // The first start is allowed to become LIVE immediately before its queued stop is
            // delivered. Do not mistake that legitimate superseded state for the requested
            // restart. A usable candidate must remain the same active generation after the main
            // service queue and completion callback have had time to settle.
            delay(300)
            val stable = app.broadcastRuntime.state.value
            val stableStream = app.audioStreams.observability.value
            if (stable.phase == BroadcastPhase.LIVE &&
                candidateUrl != null && stable.listenerUrl == candidateUrl &&
                stableStream.isActive && stableStream.generation == candidateGeneration
            ) {
                return@withTimeout stable
            }
        }
        error("Unreachable")
    }

    @Test
    fun stopAllInvalidatesADeferredRapidRestart() = runBlocking {
        repeat(3) { cycle ->
            BroadcastService.start(targetContext, OperatorAccessMode.PIN, "2468".toCharArray())
            BroadcastService.stopBroadcast(targetContext)
            BroadcastService.start(targetContext, OperatorAccessMode.PIN, "1357".toCharArray())
            BroadcastService.stop(targetContext)

            // A cancelled LAZY start completes off-main-thread and posts deferred work to main.
            // Wait beyond that handoff and prove STOP_ALL's generation prevents a stolen pending
            // request from reopening the server after the operator stopped everything.
            delay(1_500)
            val stopped = app.broadcastRuntime.state.value
            assertEquals(
                "${cycle + 1}회 STOP_ALL 뒤 지연 재시작이 방송을 다시 열었습니다.",
                BroadcastPhase.IDLE,
                stopped.phase,
            )
            assertEquals(InputPhase.IDLE, stopped.inputPhase)
            assertEquals(null, stopped.listenerUrl)
        }
    }

    @Test
    fun moonshineEnglishTtsReachesListenerWebSocketAsNonSilentPcm() = runBlocking {
        val backendLease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
        val streams = AudioStreamRegistry(maxChannels = 1, maxListeners = 2)
        streams.configure(
            listOf(AudioChannelDescriptor("en", "English", "en", 24_000)),
        )
        val port = ServerSocket(0).use { it.localPort }
        val server = GuideCastLocalServer(targetContext, streams).start(
            bindAddress = InetAddress.getByName("127.0.0.1") as Inet4Address,
            config = GuideCastServerConfig(port = port, access = BroadcastAccess.Open),
        )
        // Use the production singleton. A second client binds the same native Service and its
        // close() shuts down the worker still held by the application's prepared-model lease.
        val provider = app.speechSynthesisProvider
        try {
            withTimeout(10 * 60 * 1_000L) { provider.prepare(listOf("en")) }
            assertTrue("This gate requires the real Moonshine primary voice", provider.isReady("en"))
            val uri = URI(server.listenerUrl)
            openWebSocket(uri, "", channelId = "en").use { connection ->
                val configFrame = generateSequence { readWebSocketFrame(connection) }
                    .first { it.opcode == OPCODE_TEXT }
                assertTrue(
                    configFrame.payload.toString(StandardCharsets.UTF_8)
                        .contains("\"sampleRate\":24000"),
                )
                val audible = async(Dispatchers.IO) {
                    generateSequence { readWebSocketFrame(connection) }
                        .filter { it.opcode == OPCODE_BINARY }
                        .take(600)
                        .firstOrNull { frame ->
                            val stats = frame.payload.pcmS16LeSignalStats()
                            stats.rms > 0.003f && stats.peak > 0.015f
                        }
                }
                provider.engineFor("en")
                    .synthesize("Welcome to the DMZ peace walk.", "en")
                    .collect { frame -> streams.publish("en", frame) }
                val pcmFrame = withTimeout(15_000L) { audible.await() }
                assertNotNull(
                    "Moonshine 영어 TTS의 비무음 PCM이 웹소켓에 도달하지 않았습니다.",
                    pcmFrame,
                )
                assertTrue("Fallback must not turn this into a passing Moonshine gate", provider.isReady("en"))
            }
        } finally {
            server.close()
            backendLease.close()
        }
    }

    @Test
    fun belowFiveGibGemmaRequestKeepsServerLiveAndReportsSafeFallback() = runBlocking {
        val capability = GemmaBroadcastCapability.detect(targetContext)
        assumeFalse("이 시험은 Android 인식 RAM 5.0 GiB 미만 전용입니다.", capability.supported)

        BroadcastService.start(
            targetContext,
            OperatorAccessMode.OPEN,
            translationLanguages = arrayOf("en"),
            useGemma = true,
        )
        val warned = withTimeout(30_000) {
            app.broadcastRuntime.state.first {
                it.phase == BroadcastPhase.FAILED ||
                    (it.phase == BroadcastPhase.LIVE &&
                        it.translationWarning.orEmpty().contains("자동 전환") &&
                        it.channelSummary.orEmpty().startsWith("ML Kit"))
            }
        }
        assertEquals(
            warned.errorMessage ?: "저메모리 단말에서도 서버는 운영자 선택대로 열려야 합니다.",
            BroadcastPhase.LIVE,
            warned.phase,
        )
        assertTrue(warned.translationWarning.orEmpty().contains("5.0 GiB 이상"))
        assertTrue(
            "저메모리 안전 전환 뒤 실제 채널 표기가 ML Kit이 아닙니다: " +
                warned.channelSummary,
            warned.channelSummary.orEmpty().startsWith("ML Kit"),
        )
        assertWebSocketDeliversPcm(
            URI(requireNotNull(warned.listenerUrl)),
            "",
            channelId = "en",
        )
        assertEquals(BroadcastPhase.LIVE, app.broadcastRuntime.state.value.phase)
    }

    @Test
    fun reportedSixGigabyteClassRamAllowsConstrainedGemmaSelection() = runBlocking {
        val capability = GemmaBroadcastCapability.detect(targetContext)
        assumeTrue(
            "이 시험은 Android 인식 RAM 5.0~7.0 GiB 에뮬레이터 전용입니다: " +
                capability.message,
            capability.totalMemoryBytes >= GemmaBroadcastCapability.MIN_TOTAL_MEMORY_BYTES &&
                capability.totalMemoryBytes < GemmaBroadcastCapability.STANDARD_MEMORY_BYTES,
        )
        assertTrue(capability.supported)
        assertTrue(capability.constrainedMemoryMode)
        assertTrue(capability.message.contains("6GB급 메모리 절약 모드"))

        val viewModel = requireUserViewModel()
        instrumentation.runOnMainSync { viewModel.setUseGemma(true) }
        val ui = withTimeout(5_000L) {
            viewModel.gemmaState.first { it.useForTranslation }
        }
        assertTrue(ui.broadcastCapable)
        assertTrue(ui.broadcastCapabilityMessage.orEmpty().contains("6GB급"))
        instrumentation.runOnMainSync { viewModel.setUseGemma(false) }
    }

    @Test
    fun claimingLiveSessionPreservesPreparedMlKitLanguageWorker() = runBlocking {
        app.translationProvider.modelManager.refresh(setOf("en"))
        assumeTrue(
            "영어 ML Kit 모델이 설치된 환경에서만 준비 상태 보존을 검증합니다.",
            app.translationProvider.modelManager.statuses.value.any {
                it.languageTag == "en" && it.readiness == ModelReadiness.READY
            },
        )
        app.translationProvider.warm(listOf("en"))
        val preparedEngine = app.translationProvider.engineFor("en")
        assertFalse(
            "준비된 영어 번역 작업자가 유효한 결과를 만들지 못했습니다.",
            preparedEngine.translate("안녕하세요", "ko-KR", "en").isBlank(),
        )

        val backendUseLease = requireNotNull(
            app.acquireTranslationBackendUseIf(
                isOwnerCurrent = { true },
                supersedeSettingsStandby = true,
            ),
        )
        try {
            assertFalse(
                "방송 세션 확보가 직전에 준비한 언어 작업자를 폐기했습니다.",
                preparedEngine.translate("평화 걷기를 시작합니다", "ko-KR", "en").isBlank(),
            )
        } finally {
            backendUseLease.close()
        }
    }

    @Test
    fun devicePlaybackCaptureDeliversOtherAppMediaPcm() = runBlocking {
        app.audioInputRepository.selectDevice(AudioInputKind.DEVICE_PLAYBACK_ID)
        withTimeout(10_000) {
            app.audioInputRepository.selectedDevice.first {
                it?.platformId == AudioInputKind.DEVICE_PLAYBACK_ID
            }
        }
        instrumentation.waitForIdleSync()

        val device = UiDevice.getInstance(instrumentation)
        try {
            targetContext.startActivity(mediaProjectionTestIntent())

            assertTrue(
                "MediaProjection 동의 화면이 열리지 않았습니다.",
                device.wait(Until.hasObject(By.res("android", "button1")), 10_000),
            )
            val consent = device.findObject(By.res("android", "button1"))
            assertNotNull("MediaProjection 시작 버튼이 없습니다.", consent)
            requireNotNull(consent).click()

            val input = withTimeout(20_000) {
                app.broadcastRuntime.state.first {
                    it.inputPhase == InputPhase.ACTIVE || it.inputPhase == InputPhase.FAILED
                }
            }
            assertEquals(
                input.inputErrorMessage ?: "내부 재생음 입력이 ACTIVE 상태가 아닙니다.",
                InputPhase.ACTIVE,
                input.inputPhase,
            )

            instrumentation.context.startActivity(
                Intent(instrumentation.context, PlaybackToneActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val playbackDiagnostics = withTimeout(10_000) {
                app.audioInputRepository.playbackCaptureDiagnostics.first {
                    it.potentiallyCapturablePlaybackCount > 0
                }
            }
            assertTrue(
                "캡처 허용 타 앱 재생을 진단하지 못했습니다: $playbackDiagnostics",
                playbackDiagnostics.activePlaybackCount > 0,
            )
            val audibleInput = withTimeout(15_000) {
                app.broadcastRuntime.state.first {
                    it.inputPhase == InputPhase.ACTIVE &&
                        it.inputSignalActive &&
                        it.inputRms > 0.15f &&
                        it.inputPeak > 0.25f
                }
            }
            assertTrue("다른 앱의 MEDIA PCM이 내부 입력으로 감지되지 않았습니다.", audibleInput.inputFrameCount > 0)

            BroadcastService.start(targetContext, OperatorAccessMode.OPEN)
            val live = withTimeout(20_000) {
                app.broadcastRuntime.state.first {
                    it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
                }
            }
            assertEquals(live.errorMessage ?: "내부음 방송이 LIVE 상태가 아닙니다.", BroadcastPhase.LIVE, live.phase)
            assertEquals("앱 출력", live.channelSummary)
            assertCapturedPlaybackPcm(URI(requireNotNull(live.listenerUrl)))
        } finally {
            device.pressBack()
        }
    }

    @Test
    fun blockedOtherAppPlaybackIsDiagnosedWithoutFalsePcmSuccess() = runBlocking {
        activateDevicePlaybackInput()
        val device = UiDevice.getInstance(instrumentation)
        try {
            instrumentation.context.startActivity(
                Intent(instrumentation.context, PlaybackToneActivity::class.java)
                    .putExtra(PlaybackToneActivity.EXTRA_BLOCK_CAPTURE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val diagnostics = withTimeout(10_000) {
                app.audioInputRepository.playbackCaptureDiagnostics.first {
                    it.policyBlockedPlaybackCount > 0
                }
            }
            assertEquals(0, diagnostics.potentiallyCapturablePlaybackCount)
            delay(1_500)
            val input = app.broadcastRuntime.state.value
            assertEquals(InputPhase.ACTIVE, input.inputPhase)
            assertEquals(
                "캡처 금지 타 앱 출력이 실제 PCM으로 잘못 판정됐습니다.",
                0L,
                input.inputAudibleFrameCount,
            )
            assertFalse("캡처 금지 출력이 최근 음성 신호로 표시됐습니다.", input.inputSignalActive)
            assertTrue("캡처 차단 시험이 PCM 프레임을 읽지 못했습니다.", input.inputFrameCount > 0)

            BroadcastService.start(targetContext, OperatorAccessMode.OPEN)
            val live = withTimeout(20_000) {
                app.broadcastRuntime.state.first {
                    it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
                }
            }
            assertEquals(
                live.errorMessage ?: "캡처 차단 상태에서 원음 방송 서버를 열지 못했습니다.",
                BroadcastPhase.LIVE,
                live.phase,
            )
            assertWebSocketCarriesOnlySilentPcm(URI(requireNotNull(live.listenerUrl)))
        } finally {
            device.pressBack()
        }
    }

    @Test
    fun localMonitorVolumePauseResumeAndStopLeaveOriginalBroadcastUntouched(): Unit = runBlocking {
        // Actual AudioTrack/Service check, not an acoustic Bluetooth qualification.
        app.audioInputRepository.selectDevice(AudioInputKind.DEVICE_PLAYBACK_ID)
        withTimeout(10_000) {
            app.audioInputRepository.selectedDevice.first { it?.kind == AudioInputKind.DEVICE_PLAYBACK }
        }
        BroadcastService.start(targetContext, OperatorAccessMode.OPEN)
        withTimeout(20_000) { app.broadcastRuntime.state.first { it.phase == BroadcastPhase.LIVE } }
        val output = targetContext.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val session = app.audioStreams.currentSession()
        val listener = session.subscribe("source")
        try {
            BroadcastService.setLocalMonitorVolume(targetContext, 0.25f)
            BroadcastService.startLocalMonitor(targetContext, "source", output.id)
            val started = withTimeout(10_000) { app.broadcastRuntime.state.first {
                it.localMonitor.phase == LocalMonitorPhase.PLAYING || it.localMonitor.phase == LocalMonitorPhase.FAILED
            } }
            assertEquals(started.localMonitor.errorMessage, LocalMonitorPhase.PLAYING, started.localMonitor.phase)
            assertEquals(0.25f, started.localMonitor.volume)
            val pcm = ByteArray(640) { index -> if (index % 2 == 0) 0 else 16 }
            // Runtime counters are sampled every 25 frames; exceed two update windows.
            repeat(60) { index ->
                session.tryPublish("source", PcmAudioFrame(pcm.copyOf(), index.toLong() + 1))
                assertTrue(pcm.contentEquals(withTimeout(1_000) { listener.frames.receive() }.bytes))
                delay(20)
            }
            withTimeout(5_000) { app.broadcastRuntime.state.first { it.localMonitor.renderedNonSilentFrames > 0 } }
            BroadcastService.pauseLocalMonitor(targetContext)
            withTimeout(5_000) { app.broadcastRuntime.state.first { it.localMonitor.phase == LocalMonitorPhase.PAUSED } }
            BroadcastService.setLocalMonitorVolume(targetContext, 0f)
            withTimeout(5_000) { app.broadcastRuntime.state.first { it.localMonitor.volume == 0f } }
            session.tryPublish("source", PcmAudioFrame(pcm, 100L))
            assertTrue(pcm.contentEquals(withTimeout(1_000) { listener.frames.receive() }.bytes))
            BroadcastService.resumeLocalMonitor(targetContext)
            withTimeout(5_000) { app.broadcastRuntime.state.first { it.localMonitor.phase == LocalMonitorPhase.PLAYING } }
            BroadcastService.stopLocalMonitor(targetContext)
            withTimeout(5_000) { app.broadcastRuntime.state.first { it.localMonitor.phase == LocalMonitorPhase.IDLE } }
            assertEquals(BroadcastPhase.LIVE, app.broadcastRuntime.state.value.phase)
            assertTrue(session.isActive())
            session.tryPublish("source", PcmAudioFrame(pcm, 101L))
            assertTrue(pcm.contentEquals(withTimeout(1_000) { listener.frames.receive() }.bytes))
        } finally { listener.close() }
    }

    @Test
    fun capturedKoreanSpeechProducesGemmaEnglishAlongsideOriginalAudio(): Unit = runBlocking {
        requireDirectGemmaReady(requireUserViewModel())
        assertCapturedKoreanSpeechProducesTranslatedWebAudioChannels(
            useGemma = true,
            requireGemmaPriority = true,
            languages = listOf("en"),
        )
    }

    @Test
    fun capturedKoreanSpeechProducesFourMlKitWebAudioChannelsWithoutProcessExit() {
        runBlocking {
            assertCapturedKoreanSpeechProducesTranslatedWebAudioChannels(useGemma = false)
        }
    }

    @Test
    fun capturedKoreanSpeechProducesFourMixedProviderWebAudioChannelsWithoutProcessExit() {
        runBlocking {
            assertCapturedKoreanSpeechProducesTranslatedWebAudioChannels(useGemma = true)
        }
    }

    /**
     * Android 15/S23-class regression for the exact operator journey that previously had no
     * device coverage: prepare models, activate input, start the translation-test service,
     * hear the AudioTrack preview, inspect scripts, and stop it again. The default five cycles
     * are the fast gate. Pass `-e guidecast.translationLongRun true` for twenty cycles.
     *
     * This is deliberately a strict Gemma gate: fallback is a product safety feature, but it
     * must not turn a failed Gemma runtime into a green Gemma test.
     */
    @Test
    fun android15UserTranslationTestJourneySurvivesRepeatedGemmaPreviewCycles() = runBlocking {
        assumeTrue("Android 15 이상 S23급 장치 시험입니다.", Build.VERSION.SDK_INT >= 35)
        val sourceSpeech = createCapturedKoreanSpeech()
        val viewModel = requireUserViewModel()
        requireDirectGemmaReady(viewModel)
        val health = ProcessHealthProbe()

        prepareSelectedModelsThroughUserControl(viewModel, setOf("en"))
        health.assertHealthy("영어 모델 준비")
        activateDevicePlaybackInput()

        val iterations = if (
            InstrumentationRegistry.getArguments()
                .getString("guidecast.translationLongRun")
                .equals("true", ignoreCase = true)
        ) {
            20
        } else {
            5
        }

        repeat(iterations) { iteration ->
            val before = app.broadcastRuntime.state.value
            val previousMessage = before.translationTestMessage
            try {
                instrumentation.runOnMainSync { viewModel.startTranslationTest("en") }
                val accepted = withTimeout(20_000) {
                    app.broadcastRuntime.state.first { state ->
                        state.translationTestActive ||
                            (!state.translationTestActive &&
                                state.translationTestMessage != previousMessage)
                    }
                }
                assertTrue(
                    "${iteration + 1}회차 통번역 시험 요청이 거부됐습니다: " +
                        accepted.translationTestMessage,
                    accepted.translationTestActive,
                )
                val ready = withTimeout(10 * 60 * 1_000L) {
                    app.broadcastRuntime.state.first { state ->
                        !state.translationTestActive ||
                            state.translationTestMessage.orEmpty().startsWith("준비됐습니다")
                    }
                }
                assertTrue(
                    "${iteration + 1}회차 통역 엔진 준비 실패: " +
                        ready.translationTestMessage,
                    ready.translationTestActive,
                )
                assertTrue(
                    "${iteration + 1}회차 새 시험이 이전 스크립트를 지우지 않았습니다.",
                    ready.transcripts.isEmpty(),
                )

                val beforePlayback = app.broadcastRuntime.state.value
                playCapturedKoreanSpeech(sourceSpeech)
                val captured = withTimeout(30_000L) {
                    app.broadcastRuntime.state.first { state ->
                        state.inputPhase == InputPhase.FAILED ||
                            (state.inputPhase == InputPhase.ACTIVE &&
                                state.inputFrameCount > beforePlayback.inputFrameCount &&
                                state.inputAudibleFrameCount >
                                beforePlayback.inputAudibleFrameCount)
                    }
                }
                assertEquals(
                    "${iteration + 1}회차 시험 PCM이 입력되지 않았습니다: " +
                        captured.inputErrorMessage,
                    InputPhase.ACTIVE,
                    captured.inputPhase,
                )

                val completed = try {
                    withTimeout(TRANSLATION_PREVIEW_COMPLETION_TIMEOUT_MILLIS) {
                        app.broadcastRuntime.state.first { state ->
                            !state.translationTestActive ||
                                state.transcripts.any { line ->
                                    line.isFinal &&
                                        line.translations["en"].orEmpty().isNotBlank() &&
                                        line.synthesisLatencyMillis.containsKey("en")
                                }
                        }
                    }
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    val state = app.broadcastRuntime.state.value
                    throw AssertionError(
                        "${iteration + 1}회차 통번역 완료 시간 초과 · " +
                            "message=${state.translationTestMessage} · " +
                            "inputFrames=${state.inputFrameCount} · " +
                            "audibleFrames=${state.inputAudibleFrameCount} · " +
                            "transcripts=${state.transcripts}",
                        timeout,
                    )
                }
                assertTrue(
                    "${iteration + 1}회차 통번역 시험이 중단됐습니다: " +
                        completed.translationTestMessage,
                    completed.translationTestActive,
                )
                val line = completed.transcripts.last {
                    it.synthesisLatencyMillis.containsKey("en")
                }
                assertFalse(
                    "${iteration + 1}회차 한국어 스크립트가 비었습니다.",
                    line.sourceText.isBlank(),
                )
                assertFalse(
                    "${iteration + 1}회차 영어 번역문이 비었습니다.",
                    line.translations["en"].isNullOrBlank(),
                )

                // Synthesis completion precedes the last AudioTrack drain. Keeping the test alive
                // catches a write/route failure reported asynchronously by startPreviewPlayback().
                delay(1_000)
                val preview = app.broadcastRuntime.state.value
                assertTrue(
                    "${iteration + 1}회차 AudioTrack 미리듣기 실패: " +
                        preview.translationTestMessage,
                    preview.translationTestActive && preview.translationTestPassed,
                )
            } finally {
                stopTranslationTestDeterministically(viewModel)
            }
            val stopped = app.broadcastRuntime.state.value
            assertEquals(
                "통번역 시험 중지가 음성 입력을 함께 중지했습니다.",
                InputPhase.ACTIVE,
                stopped.inputPhase,
            )
            health.assertHealthy("영어 통번역 시험 ${iteration + 1}/$iterations")
        }
    }

    /** Android 15 gate: one bounded Gemma-priority channel plus four independent ML Kit channels. */
    @Test
    fun android15PreparedFiveLanguageMixedBroadcastKeepsEveryProcessAndWebChannelAlive() {
        runBlocking {
            assumeTrue("Android 15 이상 S23급 장치 시험입니다.", Build.VERSION.SDK_INT >= 35)
            val viewModel = requireUserViewModel()
            requireDirectGemmaReady(viewModel)
            val health = ProcessHealthProbe()

            prepareSelectedModelsThroughUserControl(viewModel, setOf("en", "ja", "zh", "nl", "es"))
            health.assertHealthy("5개 언어 모델 준비")
            assertCapturedKoreanSpeechProducesTranslatedWebAudioChannels(
                useGemma = true,
                requireGemmaPriority = true,
                health = health,
                languages = listOf("en", "ja", "zh", "nl", "es"),
            )
        }
    }

    /** Android 15 gate that does not require the separately downloaded 2.41 GiB Gemma model. */
    @Test
    fun android15PreparedFiveLanguageMlKitBroadcastServesEveryWebAudioChannel() {
        runBlocking {
            assumeTrue("Android 15 이상 장치 시험입니다.", Build.VERSION.SDK_INT >= 35)
            val viewModel = requireUserViewModel()
            val health = ProcessHealthProbe()
            val languages = listOf("en", "ja", "zh", "nl", "es")

            prepareSelectedModelsThroughUserControl(viewModel, languages.toSet())
            health.assertHealthy("ML Kit 5개 언어 모델·음성 준비")
            assertCapturedKoreanSpeechProducesTranslatedWebAudioChannels(
                useGemma = false,
                health = health,
                languages = languages,
            )
        }
    }

    private suspend fun assertCapturedKoreanSpeechProducesTranslatedWebAudioChannels(
        useGemma: Boolean,
        requireGemmaPriority: Boolean = false,
        health: ProcessHealthProbe? = null,
        languages: List<String> = listOf("en", "ja", "zh", "nl"),
    ) = coroutineScope {
        val sourceSpeech = createCapturedKoreanSpeech()
        if (languages.size > 1 || !useGemma) {
            // This journey needs the non-priority languages' installed ML Kit models too.
            // Opening a server is intentionally allowed before preparation and is not readiness.
            prepareSelectedModelsThroughUserControl(requireUserViewModel(), languages.toSet())
        }

        app.audioInputRepository.selectDevice(AudioInputKind.DEVICE_PLAYBACK_ID)
        withTimeout(10_000) {
            app.audioInputRepository.selectedDevice.first {
                it?.platformId == AudioInputKind.DEVICE_PLAYBACK_ID
            }
        }

        val device = UiDevice.getInstance(instrumentation)
        val connections = mutableListOf<WebSocketConnection>()
        val gemmaBroadcastSupported = useGemma &&
            GemmaBroadcastCapability.detect(targetContext).supported
        val gemmaLabel = if (languages.size == 1) "Gemma" else "혼합"
        try {
            targetContext.startActivity(mediaProjectionTestIntent())
            assertTrue(
                "MediaProjection 동의 화면이 열리지 않았습니다.",
                device.wait(Until.hasObject(By.res("android", "button1")), 10_000),
            )
            requireNotNull(device.findObject(By.res("android", "button1"))).click()
            val activeInput = withTimeout(20_000) {
                app.broadcastRuntime.state.first {
                    it.inputPhase == InputPhase.ACTIVE || it.inputPhase == InputPhase.FAILED
                }
            }
            assertEquals(activeInput.inputErrorMessage, InputPhase.ACTIVE, activeInput.inputPhase)

            BroadcastService.start(
                targetContext,
                OperatorAccessMode.OPEN,
                translationLanguages = languages.toTypedArray(),
                useGemma = useGemma,
            )
            val live = withTimeout(30_000) {
                app.broadcastRuntime.state.first {
                    it.phase == BroadcastPhase.LIVE || it.phase == BroadcastPhase.FAILED
                }
            }
            assertEquals(live.errorMessage, BroadcastPhase.LIVE, live.phase)
            assertTrue(
                "선택한 번역 엔진이 채널 요약에 반영되지 않았습니다: ${live.channelSummary}",
                live.channelSummary.orEmpty().removePrefix("원음 병행 · ")
                    .startsWith(if (useGemma) gemmaLabel else "ML Kit"),
            )
            assertTrue(live.channelSummary.orEmpty().contains("영어"))
            if ("nl" in languages) assertTrue(live.channelSummary.orEmpty().contains("네덜란드어"))

            val ready = withTimeout(10 * 60 * 1_000L) {
                app.broadcastRuntime.state.first {
                    it.phase == BroadcastPhase.FAILED ||
                        (it.phase == BroadcastPhase.LIVE &&
                            it.channelSummary.orEmpty().startsWith(
                                if (gemmaBroadcastSupported) gemmaLabel else "ML Kit",
                            ) &&
                            isExpectedProviderNotice(
                                warning = it.translationWarning,
                                useGemma = useGemma,
                                gemmaBroadcastSupported = gemmaBroadcastSupported,
                            ))
                }
            }
            assertEquals(ready.errorMessage, BroadcastPhase.LIVE, ready.phase)
            if (requireGemmaPriority) {
                assertTrue("S23급 Gemma 우선 경로가 열리지 않았습니다.", gemmaBroadcastSupported)
                assertTrue(
                    "다국어 혼합 경로가 선택되지 않았습니다: ${ready.channelSummary}",
                    ready.channelSummary.orEmpty().startsWith(gemmaLabel),
                )
                assertTrue(
                    "언어별 공급자 경로가 사용자에게 표시되지 않았습니다: ${ready.translationWarning}",
                    languages.size == 1 || ready.translationWarning.orEmpty().contains("혼합 번역 경로"),
                )
            } else {
                assertTrue(
                    "통역 엔진 준비 오류가 남았습니다: ${ready.translationWarning}",
                    isExpectedProviderNotice(
                        warning = ready.translationWarning,
                        useGemma = useGemma,
                        gemmaBroadcastSupported = gemmaBroadcastSupported,
                    ),
                )
            }

            val listenerUri = URI(requireNotNull(ready.listenerUrl))
            val startingSequence = ready.transcripts.maxOfOrNull { it.sequence } ?: Long.MIN_VALUE
            val channelReaders = languages.map { languageTag ->
                val connection = openWebSocket(listenerUri, "", languageTag)
                connections += connection
                val configFrame = generateSequence { readWebSocketFrame(connection) }
                    .first { it.opcode == OPCODE_TEXT }
                assertTrue(configFrame.payload.toString(StandardCharsets.UTF_8).contains("pcm_s16le"))
                connection.setReadTimeout(10 * 60 * 1_000)
                async(Dispatchers.IO) {
                    generateSequence { readWebSocketFrame(connection) }
                        .filter { it.opcode == OPCODE_BINARY }
                        .take(5_000)
                        .firstOrNull {
                            val stats = it.payload.pcmS16LeSignalStats()
                            stats.rms > 0.003f && stats.peak > 0.015f && stats.nonZeroSamples > 100
                        } ?: error("$languageTag 웹 채널에서 통역 음성 PCM을 받지 못했습니다.")
                }
            }

            playCapturedKoreanSpeech(sourceSpeech)

            val audibleInput = withTimeout(30_000) {
                app.broadcastRuntime.state.first {
                    it.inputPhase == InputPhase.FAILED ||
                        (it.inputPhase == InputPhase.ACTIVE &&
                            it.inputRms > 0.003f &&
                            it.inputPeak > 0.015f)
                }
            }
            assertEquals(audibleInput.inputErrorMessage, InputPhase.ACTIVE, audibleInput.inputPhase)
            assertTrue(
                "한국어 시험 음성이 기기 내부 소리 입력으로 잡히지 않았습니다: " +
                    "rms=${audibleInput.inputRms}, peak=${audibleInput.inputPeak}",
                audibleInput.inputRms > 0.003f && audibleInput.inputPeak > 0.015f,
            )

            val completed = try {
                withTimeout(10 * 60 * 1_000L) {
                    app.broadcastRuntime.state.first { state ->
                        state.phase == BroadcastPhase.FAILED ||
                            (state.translationWarning != null &&
                                !isExpectedProviderNotice(
                                    warning = state.translationWarning,
                                    useGemma = useGemma,
                                    gemmaBroadcastSupported = gemmaBroadcastSupported,
                                )) ||
                            state.transcripts.any { line ->
                                line.sequence > startingSequence &&
                                    line.isFinal &&
                                    line.translations.keys.containsAll(languages) &&
                                    line.firstAudioLatencyMillis.keys.containsAll(languages) &&
                                    line.synthesisLatencyMillis.keys.containsAll(languages)
                            }
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                val state = app.broadcastRuntime.state.value
                throw AssertionError(
                    "통역 방송 완료 시간 초과 · warning=${state.translationWarning} · " +
                        "transcripts=${state.transcripts}",
                    timeout,
                )
            }
            assertEquals(completed.errorMessage, BroadcastPhase.LIVE, completed.phase)
            if (requireGemmaPriority) {
                assertTrue(
                    "Gemma 우선 통역 중 경로 전환 또는 오류가 발생했습니다: " +
                        completed.translationWarning,
                    completed.channelSummary.orEmpty().startsWith(gemmaLabel) &&
                        (languages.size == 1 || completed.translationWarning.orEmpty().contains("혼합 번역 경로")) &&
                        !completed.translationWarning.orEmpty().contains("자동 전환"),
                )
            } else {
                assertTrue(
                    "통역 파이프라인 오류: ${completed.translationWarning}",
                    isExpectedProviderNotice(
                        warning = completed.translationWarning,
                        useGemma = useGemma,
                        gemmaBroadcastSupported = gemmaBroadcastSupported,
                    ),
                )
            }
            val line = completed.transcripts.last {
                it.sequence > startingSequence && it.synthesisLatencyMillis.keys.containsAll(languages)
            }
            Log.i(
                "GuideCastTest",
                "All synthesis observers completed: gemma=$useGemma, " +
                    "firstAudio=${line.firstAudioLatencyMillis}, " +
                    "synthesis=${line.synthesisLatencyMillis}",
            )
            if (Build.VERSION.SDK_INT >= 35 && !isAndroidEmulator()) {
                val overTarget = line.firstAudioLatencyMillis.filterValues { it > 2_000L }
                assertTrue(
                    "실제 S23급 환경에서 확정 후 첫 음성 2초 기준을 넘었습니다: $overTarget",
                    overTarget.isEmpty(),
                )
            } else if (Build.VERSION.SDK_INT >= 35) {
                Log.i(
                    "GuideCastTest",
                    "Emulator latency is diagnostic only; physical S23 gate is separate: " +
                        line.firstAudioLatencyMillis,
                )
            }
            assertFalse("한국어 방송 스크립트가 비어 있습니다.", line.sourceText.isBlank())
            assertTrue(
                "${languages.size}개 번역 스크립트가 완성되지 않았습니다: ${line.translations}",
                line.translations.values.all { it.isNotBlank() },
            )
            languages.filterNot(MOONSHINE_TEST_LANGUAGE_TAGS::contains).forEach { languageTag ->
                assertEquals(
                    "$languageTag 채널이 준비된 Galaxy 오프라인 음성을 사용하지 않았습니다.",
                    "Galaxy 오프라인",
                    completed.translationChannels.single {
                        it.languageTag == languageTag
                    }.synthesisProvider,
                )
            }
            withTimeout(10 * 60 * 1_000L) { channelReaders.awaitAll() }
            Log.i(
                "GuideCastTest",
                "All ${languages.size} WebSocket channels received non-silent PCM: gemma=$useGemma",
            )
            assertEquals("통역 방송 도중 서비스가 종료됐습니다.", BroadcastPhase.LIVE, app.broadcastRuntime.state.value.phase)
            health?.assertHealthy("${languages.size}개 언어 혼합 공급자 웹 방송")
        } finally {
            connections.forEach(WebSocketConnection::close)
            device.pressBack()
        }
    }

    private fun isExpectedProviderNotice(
        warning: String?,
        useGemma: Boolean,
        gemmaBroadcastSupported: Boolean,
    ): Boolean = warning == null ||
        warning.contains("Note9 호환 음성 처리") ||
        warning.contains("Galaxy 오프라인 대체 음성으로 계속합니다") ||
        (gemmaBroadcastSupported && warning.contains("ML Kit 대체 모델 미준비")) ||
        (useGemma && warning.contains("자동 전환")) ||
        (gemmaBroadcastSupported && warning.contains("혼합 번역 경로"))

    private fun isAndroidEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for")

    private fun requireUserViewModel(): AudioInputViewModel =
        ViewModelProvider(requireNotNull(activity) as MainActivity)[AudioInputViewModel::class.java]

    private suspend fun requireDirectGemmaReady(viewModel: AudioInputViewModel) {
        val capability = GemmaBroadcastCapability.detect(targetContext)
        assertTrue("직접 Gemma 실행 조건이 아닙니다: ${capability.message}", capability.supported)
        app.gemmaTranslationProvider.modelManager.refresh()
        val status = app.gemmaTranslationProvider.modelManager.status.value
        assertEquals(
            "Gemma 모델 설치·자체점검을 먼저 완료해야 합니다: ${status.errorMessage}",
            GemmaModelReadiness.READY,
            status.readiness,
        )
        instrumentation.runOnMainSync { viewModel.setUseGemma(true) }
        assertTrue("사용자 Gemma 선택이 UI 상태에 반영되지 않았습니다.", viewModel.gemmaState.first { it.useForTranslation }.useForTranslation)
    }

    private suspend fun prepareSelectedModelsThroughUserControl(
        viewModel: AudioInputViewModel,
        languages: Set<String>,
    ) {
        withTimeout(2 * 60 * 1_000L) {
            viewModel.translationModelState.first { !it.isBusy && it.statuses.isNotEmpty() }
        }
        delay(250)
        instrumentation.runOnMainSync {
            if (languages == recommendedTranslationLanguageSelection()) {
                viewModel.selectAllTranslationLanguages()
            } else {
                viewModel.clearTranslationLanguages()
                languages.forEach(viewModel::toggleTranslationLanguage)
            }
            viewModel.prepareSelectedTranslationModels()
        }
        withTimeout(20_000) { viewModel.translationModelState.first { it.isBusy } }
        val prepared = withTimeout(30 * 60 * 1_000L) {
            viewModel.translationModelState.first { !it.isBusy }
        }
        assertEquals("사용자 선택 언어가 모델 준비 과정에서 바뀌었습니다.", languages, prepared.selectedLanguageTags)
        assertTrue("한국어 음성인식 준비 실패: ${prepared.speechRecognitionReason}", prepared.speechRecognitionReady)
        languages.forEach { languageTag ->
            assertEquals(
                "$languageTag 경량 안전 번역 모델이 준비되지 않았습니다: ${prepared.message}",
                ModelReadiness.READY,
                prepared.readiness(languageTag),
            )
            assertTrue(
                "$languageTag 통역 음성이 준비되지 않았습니다: ${prepared.message}",
                prepared.ttsReady(languageTag),
            )
            if (languageTag in MOONSHINE_TEST_LANGUAGE_TAGS) {
                assertEquals(
                    "$languageTag Moonshine 음성이 준비되지 않았습니다: ${prepared.message}",
                    MoonshineTtsReadiness.READY,
                    prepared.ttsReadiness(languageTag),
                )
                assertFalse(
                    "$languageTag Moonshine 준비 성공이 대체 음성으로 잘못 표시됐습니다.",
                    prepared.ttsFallbackReady(languageTag),
                )
            } else {
                assertTrue(
                    "$languageTag Galaxy 오프라인 대체 음성이 준비되지 않았습니다: " +
                        prepared.message,
                    prepared.ttsFallbackReady(languageTag),
                )
                assertEquals(
                    "$languageTag 미제공 Moonshine 음성이 READY로 잘못 표시됐습니다.",
                    MoonshineTtsReadiness.NOT_INSTALLED,
                    prepared.ttsReadiness(languageTag),
                )
            }
        }
    }

    private suspend fun activateDevicePlaybackInput() {
        app.audioInputRepository.selectDevice(AudioInputKind.DEVICE_PLAYBACK_ID)
        withTimeout(10_000) {
            app.audioInputRepository.selectedDevice.first {
                it?.platformId == AudioInputKind.DEVICE_PLAYBACK_ID
            }
        }
        val device = UiDevice.getInstance(instrumentation)
        targetContext.startActivity(mediaProjectionTestIntent())
        assertTrue(
            "MediaProjection 동의 화면이 열리지 않았습니다.",
            device.wait(Until.hasObject(By.res("android", "button1")), 10_000),
        )
        requireNotNull(device.findObject(By.res("android", "button1"))).click()
        val active = withTimeout(20_000) {
            app.broadcastRuntime.state.first {
                it.inputPhase == InputPhase.ACTIVE || it.inputPhase == InputPhase.FAILED
            }
        }
        assertEquals(active.inputErrorMessage, InputPhase.ACTIVE, active.inputPhase)
    }

    private fun mediaProjectionTestIntent(): Intent = MediaProjectionTestActivity.intent(
        context = targetContext,
        targetPackage = instrumentation.context.packageName,
        targetUid = instrumentation.context.applicationInfo.uid,
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun createCapturedKoreanSpeech(): CapturedTestSpeech {
        val sourceSpeech = TextToSpeech(targetContext).language("ko-kr").let { sourceTts ->
            try {
                withTimeout(10 * 60 * 1_000L) {
                    sourceTts.load()
                    sourceTts.synthesize("안녕하세요. 경복궁 관광 안내를 시작합니다.")
                }
            } finally {
                sourceTts.close()
            }
        }
        val paddedPcm = ByteBuffer.allocate(
            (sourceSpeech.sampleRateHz * 5 + sourceSpeech.samples.size) * 2,
        ).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sourceSpeech.sampleRateHz) { putShort(0) }
            sourceSpeech.samples.forEach { sample ->
                putShort((sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort())
            }
            repeat(sourceSpeech.sampleRateHz * 4) { putShort(0) }
        }.array()
        check(paddedPcm.size < 800_000) { "시험 음성이 Binder 제한에 비해 너무 큽니다: ${paddedPcm.size}" }
        return CapturedTestSpeech(paddedPcm, sourceSpeech.sampleRateHz)
    }

    private fun playCapturedKoreanSpeech(speech: CapturedTestSpeech) {
        instrumentation.context.startActivity(
            Intent(instrumentation.context, PlaybackPcmActivity::class.java)
                .putExtra(PlaybackPcmActivity.EXTRA_PCM_BYTES, speech.pcm)
                .putExtra(PlaybackPcmActivity.EXTRA_SAMPLE_RATE_HZ, speech.sampleRateHz)
                // NEW_TASK alone only fronts the existing test task. During repeated preview
                // cycles that silently reuses the old, nearly-finished Activity and plays no new
                // PCM. Clear this isolated test-only task before every captured-speech launch.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    private suspend fun stopTranslationTestDeterministically(viewModel: AudioInputViewModel) =
        withContext(NonCancellable) {
            if (app.broadcastRuntime.state.value.translationTestActive) {
                instrumentation.runOnMainSync { viewModel.stopTranslationTest() }
                withTimeout(20_000L) {
                    app.broadcastRuntime.state.first { !it.translationTestActive }
                }
            }
            instrumentation.runOnMainSync { PlaybackPcmActivity.finishActivePlayback() }
        }

    private inner class ProcessHealthProbe {
        private val activityManager = targetContext.getSystemService(ActivityManager::class.java)
        private val startedAtMillis: Long
        private val mainPid: Int

        init {
            readShellOutput("logcat -b all -c")
            startedAtMillis = System.currentTimeMillis()
            mainPid = requireNotNull(currentMainPid()) { "GuideCast 메인 프로세스를 찾지 못했습니다." }
        }

        fun assertHealthy(stage: String) {
            instrumentation.waitForIdleSync()
            assertEquals("$stage 중 메인 앱 프로세스가 교체됐습니다.", mainPid, currentMainPid())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val criticalReasons = setOf(
                    ApplicationExitInfo.REASON_SIGNALED,
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED,
                )
                val exits = activityManager.getHistoricalProcessExitReasons(
                    targetContext.packageName,
                    0,
                    64,
                ).filter { info ->
                    info.timestamp >= startedAtMillis && info.reason in criticalReasons
                }
                assertTrue("$stage 중 치명적 프로세스 종료가 기록됐습니다: $exits", exits.isEmpty())
            }

            val crashLog = readShellOutput("logcat -b crash -d -v threadtime")
            val eventLog = readShellOutput(
                "logcat -b events -d -v threadtime -s am_crash am_anr am_kill",
            )
            val relevantCrash = crashLog.contains(targetContext.packageName) ||
                crashLog.contains("GuideCast", ignoreCase = true)
            val relevantEvents = eventLog.lineSequence().filter { line ->
                line.contains(targetContext.packageName) &&
                    (line.contains("am_crash") ||
                        line.contains("am_anr") ||
                        (line.contains("am_kill") && line.contains("low", ignoreCase = true)))
            }.toList()
            assertFalse("$stage 중 FATAL/native 충돌 로그가 있습니다:\n$crashLog", relevantCrash)
            assertTrue("$stage 중 ANR/LMK 이벤트가 있습니다: $relevantEvents", relevantEvents.isEmpty())
        }

        private fun currentMainPid(): Int? = activityManager.runningAppProcesses
            ?.firstOrNull { it.processName == targetContext.packageName }
            ?.pid
    }

    private fun readShellOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private data class CapturedTestSpeech(
        val pcm: ByteArray,
        val sampleRateHz: Int,
    )

    private suspend fun assertInputAndBroadcastControlsAreIndependent(listenerUri: URI) {
        BroadcastService.pauseInput(targetContext)
        val inputPaused = withTimeout(10_000) {
            app.broadcastRuntime.state.first { it.inputPhase == InputPhase.PAUSED }
        }
        assertEquals(BroadcastPhase.LIVE, inputPaused.phase)
        assertEquals(listenerUri.toString(), inputPaused.listenerUrl)

        BroadcastService.resumeInput(targetContext)
        val inputResumed = withTimeout(10_000) {
            app.broadcastRuntime.state.first { it.inputPhase == InputPhase.ACTIVE }
        }
        assertEquals(BroadcastPhase.LIVE, inputResumed.phase)

        BroadcastService.pauseBroadcast(targetContext)
        val broadcastPaused = withTimeout(10_000) {
            app.broadcastRuntime.state.first { it.phase == BroadcastPhase.PAUSED }
        }
        assertEquals(InputPhase.ACTIVE, broadcastPaused.inputPhase)
        assertEquals(listenerUri.toString(), broadcastPaused.listenerUrl)

        BroadcastService.resumeBroadcast(targetContext)
        val broadcastResumed = withTimeout(10_000) {
            app.broadcastRuntime.state.first { it.phase == BroadcastPhase.LIVE }
        }
        assertEquals(InputPhase.ACTIVE, broadcastResumed.inputPhase)
    }

    @Test
    fun speechRecognitionCapabilityMatchesAndroidGeneration() {
        val capability = app.speechRecognitionEngine.capability()
        assertTrue("ARM64 에뮬레이터에서 Moonshine STT가 제공되어야 합니다.", capability.available)
    }

    private fun grantRuntimePermissions() {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            automation.grantRuntimePermission(
                targetContext.packageName,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            automation.grantRuntimePermission(
                targetContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    private fun rawHttpGet(uri: URI, path: String, bearerToken: String? = null): String =
        Socket(uri.host, uri.effectivePort()).use { socket ->
            socket.soTimeout = 10_000
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: ${uri.host}:${uri.effectivePort()}\r\n")
                if (bearerToken != null) append("Authorization: Bearer $bearerToken\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.US_ASCII))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }

    private fun assertWebSocketDeliversPcm(
        uri: URI,
        token: String,
        channelId: String = "source",
    ) {
        openWebSocket(uri, token, channelId).use { connection ->
            val configFrame = generateSequence { readWebSocketFrame(connection) }
                .first { it.opcode == OPCODE_TEXT }
            assertTrue(configFrame.payload.toString(StandardCharsets.UTF_8).contains("pcm_s16le"))

            BroadcastService.playTestTone(targetContext)
            val pcmFrame = generateSequence { readWebSocketFrame(connection) }
                .filter { it.opcode == OPCODE_BINARY }
                .take(200)
                .firstOrNull { it.payload.pcmS16LeSignalStats().rms > 0.15f }
            assertNotNull("실제 신호 에너지가 있는 PCM이 웹소켓으로 전달되지 않았습니다.", pcmFrame)
            val payload = requireNotNull(pcmFrame).payload
            assertTrue("PCM 프레임이 비어 있습니다.", payload.isNotEmpty())
            assertEquals("16-bit PCM 프레임 길이는 짝수여야 합니다.", 0, payload.size % 2)
            val stats = payload.pcmS16LeSignalStats()
            assertTrue("RMS가 너무 낮아 재생 가능한 소리임을 입증하지 못했습니다: $stats", stats.rms > 0.15f)
            assertTrue("피크가 너무 낮아 재생 가능한 소리임을 입증하지 못했습니다: $stats", stats.peak > 0.25f)
            assertTrue("PCM 샘플이 모두 0입니다: $stats", stats.nonZeroSamples > 100)
        }
        awaitListenerCount(uri, token, 0)
    }

    private fun assertCapturedPlaybackPcm(uri: URI) {
        openWebSocket(uri, "").use { connection ->
            val configFrame = generateSequence { readWebSocketFrame(connection) }
                .first { it.opcode == OPCODE_TEXT }
            assertTrue(configFrame.payload.toString(StandardCharsets.UTF_8).contains("pcm_s16le"))
            val pcmFrames = generateSequence { readWebSocketFrame(connection) }
                .filter { it.opcode == OPCODE_BINARY }
                .take(300)
                .dropWhile {
                    val stats = it.payload.pcmS16LeSignalStats()
                    stats.rms <= 0.15f || stats.peak <= 0.25f || stats.nonZeroSamples <= 100
                }
                .take(PLAYBACK_SIGNATURE_FRAME_COUNT)
                .map { it.payload }
                .toList()
            assertEquals(
                "내부 재생음의 연속 PCM 표본 수가 부족합니다.",
                PLAYBACK_SIGNATURE_FRAME_COUNT,
                pcmFrames.size,
            )
            val estimatedFrequencyHz = estimateRisingZeroCrossingFrequency(
                pcmFrames = pcmFrames,
                sampleRateHz = 16_000,
            )
            assertTrue(
                "타 앱의 733Hz 서명이 아닌 PCM을 수신했습니다: ${estimatedFrequencyHz}Hz",
                abs(estimatedFrequencyHz - 733.0) < 90.0,
            )
        }
    }

    private fun assertWebSocketCarriesOnlySilentPcm(uri: URI) {
        openWebSocket(uri, "").use { connection ->
            val configFrame = generateSequence { readWebSocketFrame(connection) }
                .first { it.opcode == OPCODE_TEXT }
            assertTrue(configFrame.payload.toString(StandardCharsets.UTF_8).contains("pcm_s16le"))

            val pcmFrames = generateSequence { readWebSocketFrame(connection) }
                .filter { it.opcode == OPCODE_BINARY }
                .take(80)
                .toList()
            assertEquals("캡처 차단 웹 경로의 PCM 표본 수가 부족합니다.", 80, pcmFrames.size)
            val audibleFrame = pcmFrames.firstOrNull { frame ->
                val stats = frame.payload.pcmS16LeSignalStats()
                stats.rms >= 0.002f || stats.peak >= 0.01f
            }
            assertEquals(
                "캡처 금지 타 앱 음원이 웹 PCM으로 유출됐습니다: " +
                    audibleFrame?.payload?.pcmS16LeSignalStats(),
                null,
                audibleFrame,
            )
        }
    }

    private fun estimateRisingZeroCrossingFrequency(
        pcmFrames: List<ByteArray>,
        sampleRateHz: Int,
    ): Double {
        var risingCrossings = 0
        var measuredSampleIntervals = 0
        pcmFrames.forEach { pcm ->
            require(pcm.size >= 64 && pcm.size % 2 == 0)
            var previous = ((pcm[1].toInt() shl 8) or
                (pcm[0].toInt() and 0xff)).toShort()
            var offset = 2
            while (offset < pcm.size) {
                val current = ((pcm[offset + 1].toInt() shl 8) or
                    (pcm[offset].toInt() and 0xff)).toShort()
                if (previous <= 0 && current > 0) risingCrossings += 1
                previous = current
                offset += 2
            }
            measuredSampleIntervals += (pcm.size / 2) - 1
        }
        val durationSeconds = measuredSampleIntervals.toDouble() / sampleRateHz
        return risingCrossings / durationSeconds
    }

    private fun assertPerRemoteListenerLimitPreservesGlobalCapacity(uri: URI, token: String) {
        val clients = mutableListOf<WebSocketConnection>()
        try {
            // Emulator sockets all originate from one address. Production keeps the advertised
            // global capacity at 50 while limiting one handset to eight long-lived tabs so one
            // hotspot peer cannot evict the other tour participants.
            repeat(8) {
                val connection = openWebSocket(uri, token)
                val config = generateSequence { readWebSocketFrame(connection) }
                    .first { it.opcode == OPCODE_TEXT }
                assertTrue(config.payload.toString(StandardCharsets.UTF_8).contains("pcm_s16le"))
                clients += connection
            }
            awaitListenerCount(uri, token, 8)

            openWebSocket(uri, token).use { rejected ->
                val close = generateSequence { readWebSocketFrame(rejected) }
                    .first { it.opcode == OPCODE_CLOSE }
                assertTrue("거부 close frame에 상태 코드가 없습니다.", close.payload.size >= 2)
                val closeCode =
                    ((close.payload[0].toInt() and 0xff) shl 8) or
                        (close.payload[1].toInt() and 0xff)
                assertEquals("같은 단말의 9번째 청취자는 TRY_AGAIN_LATER여야 합니다.", 1013, closeCode)
            }
        } finally {
            clients.forEach(WebSocketConnection::close)
        }
        awaitListenerCount(uri, token, 0)
    }

    private fun openWebSocket(
        uri: URI,
        token: String,
        channelId: String = "source",
    ): WebSocketConnection {
        val socket = Socket(uri.host, uri.effectivePort()).apply { soTimeout = 15_000 }
        return try {
            val keyBytes = ByteArray(16).also(SecureRandom()::nextBytes)
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val request = buildString {
                append("GET /ws/$channelId?token=$token HTTP/1.1\r\n")
                append("Host: ${uri.host}:${uri.effectivePort()}\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $key\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }
            val input = socket.getInputStream()
            val handshake = readHttpHeaders(input)
            assertTrue(handshake.startsWith("HTTP/1.1 101"))
            WebSocketConnection(socket, input)
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun awaitListenerCount(uri: URI, token: String, expected: Int) {
        repeat(100) {
            val response = rawHttpGet(uri, "/api/status", bearerToken = token)
            if (response.contains("\"listenerCount\":$expected")) return
            Thread.sleep(50)
        }
        val response = rawHttpGet(uri, "/api/status", bearerToken = token)
        assertTrue(
            "청취자 수가 $expected 상태로 전환되지 않았습니다: $response",
            response.contains("\"listenerCount\":$expected"),
        )
    }

    private fun readHttpHeaders(input: java.io.InputStream): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val value = input.read()
            check(value >= 0) { "WebSocket handshake ended early" }
            bytes.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII.name())
    }

    private fun readWebSocketFrame(input: java.io.InputStream): WebSocketFrame {
        val first = input.read()
        val second = input.read()
        check(first >= 0 && second >= 0) { "WebSocket stream ended early" }
        val opcode = first and 0x0f
        val masked = second and 0x80 != 0
        var length = (second and 0x7f).toLong()
        if (length == 126L) {
            length = ((input.read() shl 8) or input.read()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }
        check(length in 0..MAX_TEST_FRAME_BYTES) { "Unexpected WebSocket frame length: $length" }
        val mask = if (masked) input.readExact(4) else null
        val payload = input.readExact(length.toInt())
        if (mask != null) {
            payload.indices.forEach { index ->
                payload[index] = (payload[index].toInt() xor mask[index % mask.size].toInt()).toByte()
            }
        }
        return WebSocketFrame(opcode, payload)
    }

    private fun readWebSocketFrame(connection: WebSocketConnection): WebSocketFrame {
        while (true) {
            val frame = readWebSocketFrame(connection.input)
            if (frame.opcode == OPCODE_PING) {
                connection.sendPong(frame.payload)
            } else {
                return frame
            }
        }
    }

    private fun java.io.InputStream.readExact(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            check(count > 0) { "WebSocket stream ended early" }
            offset += count
        }
        return result
    }

    private fun URI.effectivePort(): Int = if (port == -1) 80 else port

    private data class WebSocketFrame(val opcode: Int, val payload: ByteArray)

    private class WebSocketConnection(
        private val socket: Socket,
        val input: java.io.InputStream,
    ) : AutoCloseable {
        fun setReadTimeout(timeoutMillis: Int) {
            socket.soTimeout = timeoutMillis
        }

        fun sendPong(payload: ByteArray) {
            require(payload.size < 126)
            val mask = ByteArray(4).also(SecureRandom()::nextBytes)
            val frame = ByteArray(6 + payload.size)
            frame[0] = (0x80 or OPCODE_PONG).toByte()
            frame[1] = (0x80 or payload.size).toByte()
            mask.copyInto(frame, destinationOffset = 2)
            payload.indices.forEach { index ->
                frame[6 + index] =
                    (payload[index].toInt() xor mask[index % mask.size].toInt()).toByte()
            }
            synchronized(socket) {
                socket.getOutputStream().apply {
                    write(frame)
                    flush()
                }
            }
        }

        override fun close() = socket.close()
    }

    private companion object {
        val MOONSHINE_TEST_LANGUAGE_TAGS = setOf("en", "ja", "zh", "nl", "es", "ar")
        const val OPCODE_TEXT = 1
        const val OPCODE_BINARY = 2
        const val OPCODE_CLOSE = 8
        const val OPCODE_PING = 9
        const val OPCODE_PONG = 10
        const val MAX_TEST_FRAME_BYTES = 1_048_576L
        const val PLAYBACK_SIGNATURE_FRAME_COUNT = 24
        const val TRANSLATION_PREVIEW_COMPLETION_TIMEOUT_MILLIS = 2 * 60 * 1_000L
    }
}
