package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.core.translation.SpeechSynthesisEngineProvider
import app.guidecast.provider.moonshine.tts.MoonshineTtsEnglishInferenceService
import app.guidecast.provider.moonshine.tts.MoonshineSpeechSynthesisProvider
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/** Regression gate for the Samsung "clear cache" dialog caused by killing a private process. */
@RunWith(AndroidJUnit4::class)
class MoonshineTtsWorkerLifecycleDeviceTest {
    private var backendLease: TranslationBackendUseLease? = null

    @Before fun claimBackendUse() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        // Direct provider tests must own the same lease as a real broadcast/test caller.
        // Otherwise a preceding model-service test can legitimately schedule idle cleanup.
        backendLease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
    }

    @After fun releaseBackendUse() {
        backendLease?.close()
        backendLease = null
    }

    @Test
    fun selfTestFirstPcmCancellationDoesNotPoisonNextSynthesis() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication

        withTimeout(10 * 60 * 1_000L) {
            app.speechSynthesisProvider.prepare(listOf("en"))
        }
        repeat(5) { iteration ->
            val firstAudible = withTimeout(6_500L) {
                app.speechSynthesisProvider.engineFor("en")
                    .synthesize("Hello from GuideCast.", "en")
                    .map { it.bytes.pcmS16LeSignalStats() }
                    .first { it.sampleCount > 0 && it.peak > 0.002f }
            }
            assertTrue("$iteration 회차 자체점검 첫 PCM이 무음입니다.", firstAudible.rms > 0f)
        }

        // The real Gemma self-test cancels synthesis after its first audible PCM. A following
        // full broadcast sentence must therefore prove the native incremental stream was reset.
        assertPlayable(app.speechSynthesisProvider, "after-self-test-cancellations")
        app.speechSynthesisProvider.releaseNativeResources()
    }

    @Test
    fun workerKilledDuringPcmStreamingRecoversOnNextSentenceWithoutDuplicating() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val mainPid = Process.myPid()

        withTimeout(10 * 60 * 1_000L) {
            app.speechSynthesisProvider.prepare(listOf("en"))
        }

        val initialWorkerPid = requireNotNull(ttsWorkerPid(context)) {
            "TTS 작업 프로세스가 실행되지 않았습니다."
        }

        try {
            // Sentence 1: Start synthesis, find first audible PCM, then kill the worker mid-stream
            var firstAudibleReceived = false
            var caughtStreamException: Throwable? = null
            var sentence1AudioBytes = 0
            try {
                withTimeout(15_000L) {
                    app.speechSynthesisProvider.engineFor("en")
                        .synthesize("The tour guide will explain the history of the peace trail.", "en")
                        .collect { frame ->
                            sentence1AudioBytes += frame.bytes.size
                            val stats = frame.bytes.pcmS16LeSignalStats()
                            if (!firstAudibleReceived && stats.sampleCount > 0 && stats.peak > 0.002f) {
                                firstAudibleReceived = true
                                // Kill the worker process while streaming
                                Process.sendSignal(initialWorkerPid, Process.SIGNAL_KILL)
                            }
                        }
                }
            } catch (e: Throwable) {
                if (e is AssertionError || e is CancellationException) throw e
                caughtStreamException = e
            }
            assertTrue("첫 비무음 PCM 프레임을 수신해야 합니다.", firstAudibleReceived)
            assertNotNull("Worker 사망으로 인한 스트림 오류가 발생해야 합니다.", caughtStreamException)
            assertEquals("메인 앱 프로세스가 유지되어야 합니다.", mainPid, Process.myPid())

            // Verify the initial worker process is dead
            withTimeout(15_000L) {
                while (ttsWorkerPid(context) == initialWorkerPid) delay(100)
            }

            // Sentence 2: Next sentence triggers lazy rebind without waiting in a loop beforehand.
            // It must produce playable non-silent audio with a replacement worker process and no duplication.
            val outputSentence2 = ByteArrayOutputStream()
            var sentence2FrameCount = 0
            var lastFrameTimestampNanos = 0L
            withTimeout(2 * 60 * 1_000L) {
                app.speechSynthesisProvider.engineFor("en")
                    .synthesize("Next sentence begins cleanly with a fresh worker process.", "en")
                    .collect { frame ->
                        sentence2FrameCount++
                        assertTrue(
                            "프레임 타임스탬프 순서가 단조 증가해야 합니다.",
                            frame.capturedAtElapsedRealtimeNanos >= lastFrameTimestampNanos,
                        )
                        lastFrameTimestampNanos = frame.capturedAtElapsedRealtimeNanos
                        outputSentence2.write(frame.bytes)
                    }
            }
            assertTrue("다음 문장에서 PCM 프레임이 수신되어야 합니다.", sentence2FrameCount > 0)
            val replacementPid = requireNotNull(ttsWorkerPid(context)) {
                "다음 문장 합성 후 새 TTS 작업 프로세스가 생성되지 않았습니다."
            }
            assertTrue(
                "새로운 worker 프로세스가 기동되어야 합니다: initial=$initialWorkerPid, new=$replacementPid",
                replacementPid != initialWorkerPid,
            )
            val stats2 = outputSentence2.toByteArray().pcmS16LeSignalStats()
            assertTrue("다음 문장 TTS가 비무음이어야 합니다: $stats2", stats2.rms > 0.003f)
            assertTrue("다음 문장 TTS 피크가 충분해야 합니다: $stats2", stats2.peak > 0.015f)
            assertEquals("다음 문장 합성 후에도 메인 앱 프로세스가 유지되어야 합니다.", mainPid, Process.myPid())
        } finally {
            app.speechSynthesisProvider.releaseNativeResources()
        }
    }

    @Test
    fun repeatedSynthesisReusesWorkerWithoutKillingAppProcess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val mainPid = Process.myPid()

        withTimeout(10 * 60 * 1_000L) {
            app.speechSynthesisProvider.prepare(listOf("en"))
        }
        var workerPid: Int? = null
        repeat(5) { iteration ->
            val output = ByteArrayOutputStream()
            withTimeout(2 * 60 * 1_000L) {
                app.speechSynthesisProvider.engineFor("en")
                    .synthesize("Welcome to Gyeongbokgung Palace.", "en")
                    .collect { output.write(it.bytes) }
            }
            val stats = output.toByteArray().pcmS16LeSignalStats()
            assertTrue("$iteration 회차 TTS가 무음입니다: $stats", stats.rms > 0.003f)
            assertTrue("$iteration 회차 TTS 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
            assertEquals("메인 앱 프로세스가 교체됐습니다.", mainPid, Process.myPid())

            val observed = requireNotNull(ttsWorkerPid(context)) {
                "$iteration 회차 뒤 TTS 작업 프로세스가 사라졌습니다."
            }
            if (workerPid == null) workerPid = observed
            assertEquals("TTS 작업 프로세스를 매 문장마다 종료했습니다.", workerPid, observed)
        }
        app.speechSynthesisProvider.releaseNativeResources()
    }

    @Test
    fun longPublishedTranslationCrossesMoonshineTextLimitWithoutSilence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val mainPid = Process.myPid()
        val sentence = "Visitors should remain together while the guide explains the history " +
            "of the peace trail, and please wait for the next safety instruction before moving. "
        val translatedParagraph = sentence.repeat(4).trim()
        assertTrue("시험 문장이 Moonshine 단일 입력 상한을 넘지 않습니다.", translatedParagraph.length > 480)

        withTimeout(10 * 60 * 1_000L) {
            app.speechSynthesisProvider.prepare(listOf("en"))
        }
        val output = ByteArrayOutputStream()
        withTimeout(5 * 60 * 1_000L) {
            app.speechSynthesisProvider.engineFor("en")
                .synthesize(translatedParagraph, "en")
                .collect { output.write(it.bytes) }
        }
        val stats = output.toByteArray().pcmS16LeSignalStats()
        assertTrue("긴 번역문 TTS PCM이 비었습니다.", stats.sampleCount > 12_000)
        assertTrue("긴 번역문 TTS가 무음입니다: $stats", stats.rms > 0.003f)
        assertTrue("긴 번역문 TTS 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
        assertEquals("긴 번역문 뒤 앱 프로세스가 종료됐습니다.", mainPid, Process.myPid())
        app.speechSynthesisProvider.releaseNativeResources()
    }

    @Test
    fun forcedWorkerDeathReconnectsAndRepeatedReleaseDoesNotLeakBinding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val provider = MoonshineSpeechSynthesisProvider(context)
        try {
            // Keep the application singleton bound before another provider kills the process.
            // This reproduces the full-suite ordering that exposed the stale-Binder race.
            withTimeout(10 * 60 * 1_000L) {
                app.speechSynthesisProvider.prepare(listOf("en"))
            }
            assertPlayable(app.speechSynthesisProvider, "singleton-before-shared-kill")

            repeat(3) { iteration ->
                withTimeout(10 * 60 * 1_000L) { provider.prepare(listOf("en")) }
                assertPlayable(provider, "before-kill-$iteration")
                val killedPid = requireNotNull(ttsWorkerPid(context)) {
                    "$iteration 회차 강제 종료 전 TTS worker가 없습니다."
                }
                Process.sendSignal(killedPid, Process.SIGNAL_KILL)
                withTimeout(15_000L) {
                    while (ttsWorkerPid(context) == killedPid) delay(100)
                }

                assertPlayable(provider, "after-reconnect-$iteration")
                val reconnectedPid = requireNotNull(ttsWorkerPid(context)) {
                    "$iteration 회차 TTS worker가 재연결되지 않았습니다."
                }
                assertTrue(
                    "$iteration 회차 worker PID가 교체되지 않았습니다: $killedPid",
                    reconnectedPid != killedPid,
                )

                provider.releaseNativeResources()
                assertWorkerBindingReleased(
                    context = context,
                    releasedPid = reconnectedPid,
                    label = "$iteration 회차 release",
                )

                // The singleton still owns a proxy to the process killed by the direct provider.
                // Its first post-kill transaction must reconnect instead of falling through to a
                // device-dependent Android TTS engine.
                assertPlayable(
                    app.speechSynthesisProvider,
                    "singleton-after-shared-kill-$iteration",
                )
            }
        } finally {
            provider.close()
            app.speechSynthesisProvider.releaseNativeResources()
        }
    }

    private suspend fun assertPlayable(
        provider: SpeechSynthesisEngineProvider,
        label: String,
    ) {
        val output = ByteArrayOutputStream()
        withTimeout(2 * 60 * 1_000L) {
            provider.engineFor("en")
                .synthesize("Welcome to GuideCast.", "en")
                .collect { output.write(it.bytes) }
        }
        val stats = output.toByteArray().pcmS16LeSignalStats()
        assertTrue("$label TTS가 무음입니다: $stats", stats.rms > 0.003f)
        assertTrue("$label TTS 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
    }

    private suspend fun assertWorkerBindingReleased(
        context: android.content.Context,
        releasedPid: Int,
        label: String,
    ) {
        var observedPid = ttsWorkerPid(context)
        var serviceDump = ttsWorkerServiceDump(context)
        val released = withTimeoutOrNull(15_000L) {
            while (
                observedPid != null &&
                activeWorkerBindingCount(serviceDump) != 0
            ) {
                delay(250)
                observedPid = ttsWorkerPid(context)
                serviceDump = ttsWorkerServiceDump(context)
            }
            true
        }
        val activeBindings = activeWorkerBindingCount(serviceDump)
        assertTrue(
            "$label 뒤 TTS worker 바인딩이 해제되지 않았습니다. " +
                "releasedPid=$releasedPid, observedPid=$observedPid, " +
                "activeBindings=$activeBindings\n$serviceDump",
            released == true,
        )
        // Android may retain an unbound application process in the cached-process LRU. A live
        // PID alone is therefore not a leak; dumpsys must show zero ServiceConnection records.
        assertEquals(
            "$label 뒤 TTS worker ServiceConnection이 남았습니다. " +
                "releasedPid=$releasedPid, observedPid=$observedPid\n$serviceDump",
            0,
            activeBindings,
        )
    }

    private fun ttsWorkerServiceDump(context: android.content.Context): String {
        val component = ComponentName(context, MoonshineTtsEnglishInferenceService::class.java)
        return ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "dumpsys activity services ${component.flattenToShortString()}",
            ),
        ).bufferedReader().use { it.readText() }
    }

    private fun activeWorkerBindingCount(serviceDump: String): Int =
        serviceDump.lineSequence().count { it.contains("ConnectionRecord{") }

    private fun ttsWorkerPid(context: android.content.Context): Int? =
        context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull {
                it.processName == "${context.packageName}:tts_en"
            }
            ?.pid
}
