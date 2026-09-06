package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.audio.pcmS16LeSignalStats
import app.guidecast.provider.gemma.translation.GemmaModelManager
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith

/**
 * Product gate for the in-app one-stop model installer, not a mocked HTTP downloader.
 *
 * - [appServiceDownloadsVerifiesAndRunsOfficialGemmaTranslatorModel] verifies Gemma model file integrity,
 *   foreground service completion, and actual Gemma translation inference leading to Gemma READY state
 *   (decoupled from speech synthesis).
 * - [warmedGemmaTranslatesTourSentenceAndMoonshineProducesAudiblePcm] independently tests warm Gemma tour
 *   sentence translation and subsequent Moonshine TTS PCM audio generation as a separate release gate.
 */
@RunWith(AndroidJUnit4::class)
class GemmaModelEndToEndDeviceTest {
    private var backendLease: TranslationBackendUseLease? = null

    @Before fun claimBackendUse() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        backendLease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
    }

    @After fun releaseBackendUse() {
        backendLease?.close()
        backendLease = null
    }

    @Test
    fun appServiceDownloadsVerifiesAndRunsOfficialGemmaTranslatorModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val manager = app.gemmaTranslationProvider.modelManager

        manager.refresh()
        // A READY file is deliberately returned to VERIFIED so every run exercises the
        // update/restart path: reuse the 2.59 GB file, then rerun Gemma translation inference
        // self-test without requiring a second model-sized block of free storage.
        // Speech synthesis is decoupled from Gemma readiness and tested in the separate gate below.
        if (manager.status.value.readiness == GemmaModelReadiness.READY) {
            manager.markRuntimeFailure("instrumentation runtime recheck requested")
        }
        if (manager.status.value.readiness != GemmaModelReadiness.READY) {
            // A quick double tap must still leave exactly one operation whose completion stops
            // every startId and removes the dataSync foreground service.
            GemmaModelService.start(context)
            GemmaModelService.start(context)
        }
        val terminal = withTimeout(90 * 60 * 1_000L) {
            manager.status.first {
                it.readiness == GemmaModelReadiness.READY ||
                    it.readiness == GemmaModelReadiness.FAILED
            }
        }
        assertEquals(
            terminal.errorMessage ?: "Gemma Translator가 READY가 되지 않았습니다.",
            GemmaModelReadiness.READY,
            terminal.readiness,
        )
        assertTrue("공식 Gemma 모델 파일이 없습니다.", manager.modelFile.isFile)
        assertEquals(GemmaModelManager.MODEL_SIZE_BYTES, manager.modelFile.length())
        withTimeout(20_000) {
            while (isGemmaModelServiceRunning(context)) delay(100)
        }

        val output = withTimeout(15 * 60 * 1_000L) {
            app.gemmaTranslationProvider.selfTest()
        }
        assertTrue("Gemma 실제 번역 결과가 비어 있습니다.", output.isNotBlank())
    }

    @Test
    fun warmedGemmaTranslatesTourSentenceAndMoonshineProducesAudiblePcm() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val manager = app.gemmaTranslationProvider.modelManager
        manager.refresh()
        // JUnit does not promise method order. A restored/fresh AVD must prepare this fixture
        // itself instead of assuming the download test happened to run first.
        if (manager.status.value.readiness != GemmaModelReadiness.READY) {
            GemmaModelService.start(context)
            withTimeout(15 * 60_000L) {
                manager.status.first {
                    it.readiness == GemmaModelReadiness.READY || it.readiness == GemmaModelReadiness.FAILED
                }
            }
        }
        assertEquals(
            "먼저 공식 Gemma 4 E2B 모델 준비 시험을 실행해야 합니다.",
            GemmaModelReadiness.READY,
            manager.status.value.readiness,
        )

        withTimeout(60_000L) { app.gemmaTranslationProvider.warmup("en") }
        val translation = withTimeout(15_000L) {
            app.gemmaTranslationProvider.engineFor("en").translate(
                text = "지금부터 평화의 길을 따라 함께 이동하겠습니다.",
                sourceLanguageTag = "ko-KR",
                targetLanguageTag = "en",
            )
        }
        assertTrue("Gemma 관광 문장 번역이 비었습니다.", translation.isNotBlank())

        withTimeout(10 * 60 * 1_000L) {
            app.speechSynthesisProvider.prepare(listOf("en"))
        }
        val output = ByteArrayOutputStream()
        withTimeout(2 * 60 * 1_000L) {
            app.speechSynthesisProvider.engineFor("en")
                .synthesize(translation, "en")
                .collect { output.write(it.bytes) }
        }
        val stats = output.toByteArray().pcmS16LeSignalStats()
        assertTrue("Gemma 번역문 Moonshine PCM이 비었습니다.", stats.sampleCount > 12_000)
        assertTrue("Gemma 번역문 Moonshine PCM이 무음입니다: $stats", stats.rms > 0.003f)
        assertTrue("Gemma 번역문 Moonshine 피크가 너무 낮습니다: $stats", stats.peak > 0.015f)
        app.speechSynthesisProvider.releaseNativeResources()
    }

    @Suppress("DEPRECATION")
    private fun isGemmaModelServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GemmaModelService::class.java.name }
}
