package app.guidecast.transmitter

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.core.translation.ModelReadiness
import app.guidecast.core.audio.pcmS16LeSignalStats
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings workflow, not a provider-only shortcut. Uses existing model files without deletion. */
@RunWith(AndroidJUnit4::class)
class PreparationWorkflowDeviceTest {
    /** The user's last-known-good 0.2.26 selection must produce speech, not just READY labels. */
    @Test fun baselineThreeLanguagesPrepareTranslateAndSynthesizeWithoutChangingGemma() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val originalVariant = app.gemmaTranslationProvider.modelManager.selectedVariant
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        ) as MainActivity
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(activity)[AudioInputViewModel::class.java]
        }
        val collector = launch { vm.translationModelState.collect() }
        try {
            delay(500)
            withTimeout(35_000) { vm.translationModelState.first { !it.isBusy } }
            withContext(Dispatchers.Main) {
                vm.clearTranslationLanguages()
                listOf("en", "ja", "zh").forEach(vm::toggleTranslationLanguage)
                vm.prepareSelectedTranslationModels()
            }
            val result = withTimeout(14 * 60_000L) {
                vm.translationModelState.first {
                    !it.isBusy && it.operationLabel == "통번역 준비 중"
                }
            }
            assertEquals(setOf("en", "ja", "zh"), result.selectedLanguageTags)
            assertTrue("한국어 STT 준비 실패: ${result.speechRecognitionReason}", result.speechRecognitionReady)
            assertEquals("언어 준비가 적용 모델을 변경하면 안 됩니다.", originalVariant,
                app.gemmaTranslationProvider.modelManager.selectedVariant)
            val lease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
            try {
                for (language in listOf("en", "ja", "zh")) {
                    assertEquals("번역 준비 실패: $language ${result.message}",
                        ModelReadiness.READY, result.readiness(language))
                    assertTrue("TTS 준비 실패: $language ${result.ttsUnavailableReason(language)}",
                        result.ttsReady(language))
                    val translated = withTimeout(30_000) {
                        app.translationProvider.engineFor(language)
                            .translate("평화의 길을 함께 걷습니다.", "ko", language)
                    }
                    assertTrue(translated.isNotBlank())
                    val pcm = ByteArrayOutputStream()
                    withTimeout(120_000) {
                        app.speechSynthesisProvider.engineFor(language).synthesize(translated, language)
                            .collect { frame ->
                                assertEquals("PCM frame alignment", 0, frame.bytes.size % 2)
                                pcm.write(frame.bytes)
                            }
                    }
                    val stats = pcm.toByteArray().pcmS16LeSignalStats()
                    assertTrue("$language TTS가 비었거나 무음입니다: $stats",
                        stats.sampleCount > 12_000 && stats.rms > 0.003f && stats.peak > 0.015f)
                    println("BASELINE_026_LANGUAGE $language translation=$translated pcm=$stats")
                }
            } finally { lease.close() }
        } finally {
            collector.cancelAndJoin()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun fiveSelectedLanguagesFinishPreparationAndPreserveHealthyStages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as GuideCastApplication
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        ) as MainActivity
        val vm = withContext(Dispatchers.Main) {
            ViewModelProvider(activity)[AudioInputViewModel::class.java]
        }
        val collector = launch { vm.translationModelState.collect() }
        try {
            delay(500)
            withTimeout(35_000) { vm.translationModelState.first { !it.isBusy } }
            withContext(Dispatchers.Main) {
                vm.selectAllTranslationLanguages()
                vm.prepareSelectedTranslationModels()
            }
            val result = withTimeout(14 * 60_000L) {
                vm.translationModelState.first {
                    !it.isBusy && it.operationLabel == "통번역 준비 중"
                }
            }
            assertEquals(setOf("en", "ja", "zh", "zh-TW", "vi"), result.selectedLanguageTags)
            result.selectedLanguageTags.forEach { language ->
                assertEquals("실제 모델 준비 실패: $language ${result.message}",
                    ModelReadiness.READY, result.readiness(language))
            }
            assertTrue("한국어 STT 준비가 번역/TTS 뒤에 고착됐습니다: ${result.speechRecognitionReason}",
                result.speechRecognitionReady)
            listOf("en", "ja", "zh").forEach { language ->
                assertTrue("지원 음성 준비 실패: $language ${result.ttsUnavailableReason(language)}",
                    result.ttsReady(language))
            }
            val lease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
            try {
                app.translationProvider.modelManager.refresh(result.selectedLanguageTags)
                assertEquals("새로고침이 내장 영어를 미설치로 되돌리면 안 됩니다.",
                    ModelReadiness.READY,
                    app.translationProvider.modelManager.statuses.value.first { it.languageTag == "en" }.readiness)
                val translations = linkedMapOf<String, String>()
                withTimeout(120_000L) {
                    result.selectedLanguageTags.forEach { language ->
                        translations[language] = app.translationProvider.engineFor(language)
                            .translate("평화의 길을 함께 걷습니다.", "ko", language)
                    }
                }
                assertTrue("실제 5개 번역문 중 빈 결과가 있습니다: $translations",
                    translations.size == 5 && translations.values.all { it.isNotBlank() })
                println("PREPARATION_TRANSLATIONS $translations")
            } finally { lease.close() }
            println("PREPARATION_FINAL translation=${result.statuses} STT=${result.speechRecognitionReady} TTS=${result.ttsStatuses} unavailable=${result.ttsUnavailableLanguageReasons}")
        } finally {
            collector.cancelAndJoin()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
