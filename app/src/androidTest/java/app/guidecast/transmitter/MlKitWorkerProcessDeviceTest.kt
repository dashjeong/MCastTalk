package app.guidecast.transmitter

import android.app.ActivityManager
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.provider.mlkit.translation.MlKitTranslationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Proves ML Kit initialization, model preparation and inference inside a private process. */
@RunWith(AndroidJUnit4::class)
class MlKitWorkerProcessDeviceTest {
    @Test
    fun isolatedWorkerPreparesAndTranslatesWithoutDefaultProcessInitializer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MlKitTranslationProvider(
            context = context,
            sourceLanguageTag = "ko",
            requireWifiForModels = false,
        )
        try {
            withTimeout(PREPARE_TIMEOUT_MILLIS) {
                provider.modelManager.prepare(setOf("en"))
            }
            val translated = withTimeout(TRANSLATE_TIMEOUT_MILLIS) {
                provider.engineFor("en").translate("안녕하세요", "ko", "en")
            }
            assertTrue("격리 ML Kit 번역문이 비었습니다.", translated.isNotBlank())

            val worker = context.getSystemService(ActivityManager::class.java)
                .runningAppProcesses
                .orEmpty()
                .firstOrNull { it.processName.startsWith("${context.packageName}:mlkit_translate_") }
            assertTrue("ML Kit 격리 작업 프로세스가 없습니다.", worker != null)
            assertNotEquals("번역이 주 앱 프로세스에서 실행됐습니다.", Process.myPid(), worker?.pid)

            // Recreate the Service in the same cached process when Android chooses to retain it.
            // MlKit.initialize() is non-idempotent, so a second prepare+translate is the required
            // regression for the process-local initialization gate.
            provider.releaseNativeResources()
            withTimeout(PREPARE_TIMEOUT_MILLIS) {
                provider.modelManager.prepare(setOf("en"))
            }
            val afterRebind = withTimeout(TRANSLATE_TIMEOUT_MILLIS) {
                provider.engineFor("en").translate("평화의 길", "ko", "en")
            }
            assertTrue("서비스 재생성 뒤 ML Kit 번역문이 비었습니다.", afterRebind.isNotBlank())
        } finally {
            provider.close()
        }
    }

    @Test
    fun fiveAlreadyReadyChannelsSurviveStopAndReplaceOneLanguageWithoutFirstUtteranceLoss() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val provider = MlKitTranslationProvider(
                context = context,
                sourceLanguageTag = "ko",
                requireWifiForModels = false,
            )
            val firstSelection = setOf("en", "ja", "zh", "nl", "es")
            val nextSelection = setOf("en", "ja", "zh", "es", "ar")
            try {
                withTimeout(MULTILINGUAL_PREPARE_TIMEOUT_MILLIS) {
                    // Prime all six downloadable models, then leave exactly the first five worker
                    // assignments live so the second selection exercises one real slot swap.
                    provider.modelManager.prepare(firstSelection)
                    provider.modelManager.prepare(setOf("ar"))
                    provider.modelManager.prepare(firstSelection)
                    provider.releaseNativeResources()
                    provider.modelManager.prepare(nextSelection)
                }

                val translations = withTimeout(MULTILINGUAL_PREPARE_TIMEOUT_MILLIS) {
                    coroutineScope {
                        nextSelection.map { languageTag ->
                            async {
                                languageTag to provider.engineFor(languageTag).translate(
                                    "평화의 길을 함께 걷습니다.",
                                    "ko",
                                    languageTag,
                                )
                            }
                        }.awaitAll().toMap()
                    }
                }
                assertTrue(
                    "교체 직후 첫 다국어 번역이 비었습니다: $translations",
                    translations.keys == nextSelection && translations.values.all { it.isNotBlank() },
                )
            } finally {
                provider.close()
            }
        }

    @Test
    fun killingOneMlKitWorkerLeavesSiblingLanguageProcessAndTranslationAlive() = runBlocking {
        assumeTrue(
            "This test deliberately kills a private worker and must not run on a physical phone.",
            isEmulator(),
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = MlKitTranslationProvider(
            context = context,
            sourceLanguageTag = "ko",
            requireWifiForModels = false,
        )
        try {
            // Prepare English first so it deterministically owns slot 0. Adding Japanese retains
            // that lease and assigns slot 1, giving the process-kill assertion a stable mapping.
            withTimeout(PREPARE_TIMEOUT_MILLIS) {
                provider.modelManager.prepare(setOf("en"))
                provider.modelManager.prepare(setOf("en", "ja"))
            }
            withTimeout(TRANSLATE_TIMEOUT_MILLIS) {
                provider.engineFor("en").translate("안녕하세요", "ko", "en")
                provider.engineFor("ja").translate("안녕하세요", "ko", "ja")
            }
            val englishPid = requireNotNull(workerPid(context, 0))
            val japanesePid = requireNotNull(workerPid(context, 1))
            assertNotEquals("언어별 ML Kit가 같은 프로세스를 사용합니다.", englishPid, japanesePid)

            Process.sendSignal(englishPid, Process.SIGNAL_KILL)
            withTimeout(WORKER_DEATH_TIMEOUT_MILLIS) {
                while (workerPid(context, 0) == englishPid) delay(50L)
            }

            assertEquals(
                "영어 번역 작업자 장애가 일본어 작업자를 교체했습니다.",
                japanesePid,
                workerPid(context, 1),
            )
            val siblingTranslation = withTimeout(TRANSLATE_TIMEOUT_MILLIS) {
                provider.engineFor("ja").translate(
                    "일본어 안내는 계속됩니다.",
                    "ko",
                    "ja",
                )
            }
            assertTrue("형제 일본어 번역이 중단됐습니다.", siblingTranslation.isNotBlank())

            // The failed language gets one fresh Binder process without replacing its sibling.
            val recoveredTranslation = withTimeout(TRANSLATE_TIMEOUT_MILLIS) {
                provider.engineFor("en").translate(
                    "영어 채널도 다음 문장에서 복구됩니다.",
                    "ko",
                    "en",
                )
            }
            assertTrue("영어 번역 작업자가 복구되지 않았습니다.", recoveredTranslation.isNotBlank())
            assertEquals(japanesePid, workerPid(context, 1))
        } finally {
            provider.close()
        }
    }

    private fun workerPid(context: android.content.Context, slot: Int): Int? =
        context.getSystemService(ActivityManager::class.java)
            .runningAppProcesses
            ?.firstOrNull {
                it.processName == "${context.packageName}:mlkit_translate_$slot"
            }
            ?.pid

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true)

    private companion object {
        const val PREPARE_TIMEOUT_MILLIS = 10L * 60 * 1_000
        const val TRANSLATE_TIMEOUT_MILLIS = 30_000L
        const val MULTILINGUAL_PREPARE_TIMEOUT_MILLIS = 20L * 60 * 1_000
        const val WORKER_DEATH_TIMEOUT_MILLIS = 15_000L
    }
}
