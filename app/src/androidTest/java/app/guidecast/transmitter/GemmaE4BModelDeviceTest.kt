package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.os.StatFs
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import app.guidecast.provider.gemma.translation.GemmaStoragePolicy
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dedicated instrumentation product gate for the Gemma 4 E4B-IT LiteRT-LM model.
 *
 * Verifies:
 * 1. Pre-condition: E2B standard model is prepared and verified with baseline inference.
 * 2. E4B model preparation via GemmaModelService.start(context, GemmaModelVariant.E4B_IT_ID)
 *    with robust preparing state progression (waits for true then false).
 * 3. File integrity and cache isolation in "models/cache-e4b_it" without touching E2B artifacts.
 * 4. Actual translation inference on device/emulator with semantic assertion.
 * 5. Safe recovery and rollback to E2B standard model, verifying unchanged E2B checksum and successful E2B self-test.
 * 6. Clean restoration of initial variant under try/finally.
 */
@RunWith(AndroidJUnit4::class)
class GemmaE4BModelDeviceTest {
    private var backendLease: TranslationBackendUseLease? = null

    @Before
    fun claimBackendUse() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GuideCastApplication
        backendLease = requireNotNull(app.acquireTranslationBackendUseIf({ true }))
    }

    @After
    fun releaseBackendUse() {
        backendLease?.close()
        backendLease = null
    }

    /** Run on a dedicated low-storage fixture before the successful cache marker exists. */
    @Test
    fun e4bLowStorageRejectsBeforeNativeAndPreservesE2B(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = (context.applicationContext as GuideCastApplication).gemmaTranslationProvider
        val manager = provider.modelManager
        val variant = GemmaModelVariant.E4B_IT
        val model = File(context.filesDir, "models/${variant.fileName}")
        val cache = File(model.parentFile, variant.cacheDirectoryName)
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        assumeTrue("Verified E4B fixture is required", model.length() == variant.sizeBytes)
        assumeTrue("Fixture must lack a completion marker", !GemmaStoragePolicy.isCacheComplete(cache, model, variant))
        assumeTrue("Fixture must have insufficient cache generation space",
            available < GemmaStoragePolicy.E4B_CACHE_BUDGET_BYTES + GemmaStoragePolicy.RUNTIME_SAFETY_BYTES)
        val initialVariant = manager.selectedVariant
        try {
            provider.applyVerifiedModel(GemmaModelVariant.STANDARD)
            val failure = runCatching { provider.applyVerifiedModel(variant) }.exceptionOrNull()
            assertTrue("E4B must reject before native cache creation", failure != null)
            assertTrue("Expected actionable storage error, got: $failure",
                failure?.message.orEmpty().contains("저장 공간"))
            assertEquals(GemmaModelVariant.STANDARD, manager.appliedVariant)
            assertEquals(GemmaModelVariant.STANDARD, manager.selectedVariant)
            val recovered = provider.selfTest()
            assertTrue(recovered.lowercase().contains("hello"))
            Log.i(TAG, "Low-storage E4B rejection and E2B recovery passed: availableBytes=$available")
        } finally {
            if (manager.selectedVariant != initialVariant) provider.selectModel(initialVariant)
        }
    }

    @Test
    fun appServicePreparesRunsE4BAndRestoresE2B(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as GuideCastApplication
        val provider = app.gemmaTranslationProvider
        val manager = provider.modelManager
        val initialVariant = manager.selectedVariant

        val e4bVariant = GemmaModelVariant.E4B_IT
        val standardVariant = GemmaModelVariant.STANDARD

        val e4bFile = File(context.filesDir, "models/${e4bVariant.fileName}")
        val e4bCache = File(context.filesDir, "models/${e4bVariant.cacheDirectoryName}")
        val standardFile = File(context.filesDir, "models/${standardVariant.fileName}")

        assertEquals("cache-e4b_it", e4bVariant.cacheDirectoryName)
        assertEquals("gemma-4-E4B-it.litertlm", e4bVariant.fileName)
        assertEquals(3_659_530_240L, e4bVariant.sizeBytes)

        try {
            // Step 1: Ensure E2B Standard is prepared & verify initial baseline
            provider.selectModel(standardVariant)
            manager.refresh()
            if (manager.status.value.readiness != GemmaModelReadiness.READY) {
                GemmaModelService.start(context, standardVariant.id)
                withTimeout(15_000L) { GemmaModelService.preparing.first { it } }
                withTimeout(90 * 60 * 1_000L) { GemmaModelService.preparing.first { !it } }
                withTimeout(20_000L) { while (isGemmaModelServiceRunning(context)) delay(100) }
                manager.refresh()
            }
            assertEquals(
                "E2B 기본형이 READY 상태여야 E4B 교체/복구 시험이 가능합니다.",
                GemmaModelReadiness.READY,
                manager.status.value.readiness,
            )
            assertTrue("E2B 표준 모델 파일이 존재해야 합니다.", standardFile.isFile)
            val standardInitialSize = standardFile.length()
            val standardInitialModified = standardFile.lastModified()
            val standardInitialDigest = computeSha256(standardFile)
            assertEquals(standardVariant.sizeBytes, standardInitialSize)
            assertEquals(standardVariant.sha256, standardInitialDigest)

            // Baseline E2B inference check
            val baselineTranslation = withTimeout(11 * 60 * 1_000L) {
                provider.selfTest()
            }
            assertTrue("E2B baseline 번역이 비어 있습니다.", baselineTranslation.isNotBlank())
            Log.i(TAG, "Baseline E2B self-test passed: $baselineTranslation")

            // Step 2: Start E4B preparation via GemmaModelService
            GemmaModelService.start(context, e4bVariant.id)

            // (1) Wait for service to transition to preparing=true, then to preparing=false
            withTimeout(15_000L) {
                GemmaModelService.preparing.first { it }
            }
            withTimeout(120 * 60 * 1_000L) {
                GemmaModelService.preparing.first { !it }
            }
            withTimeout(20_000L) {
                while (isGemmaModelServiceRunning(context)) delay(100)
            }

            // (2) Refresh provider's manager to inspect applied state & readiness
            manager.refresh()
            val appliedVariant = manager.appliedVariant
            val appliedStatus = manager.status.value
            assertEquals("적용된 모델이 E4B_IT여야 합니다.", e4bVariant, appliedVariant)
            assertEquals(
                appliedStatus.errorMessage ?: "E4B 모델 런타임 준비가 완료되지 않았습니다.",
                GemmaModelReadiness.READY,
                appliedStatus.readiness,
            )

            // Verify E4B file size & presence
            assertTrue("E4B 모델 파일이 설치되어야 합니다.", e4bFile.isFile)
            assertEquals(e4bVariant.sizeBytes, e4bFile.length())
            assertTrue("E4B 전용 캐시 디렉터리가 격리 생성되어야 합니다.", e4bCache.exists())

            // Verify E2B standard file was NOT altered during E4B download/preparation
            assertEquals("E2B 모델 파일 크기가 보존되어야 합니다.", standardInitialSize, standardFile.length())
            assertEquals("E2B 모델 수정 시각이 보존되어야 합니다.", standardInitialModified, standardFile.lastModified())

            // (3) Semantic verification on fixed test sentence with E4B
            val testKorean = "안전하게 대피해 주시기 바랍니다."
            val startTime = SystemClock.elapsedRealtime()
            val e4bTranslation = withTimeout(60 * 1_000L) {
                provider.engineFor("en").translate(
                    text = testKorean,
                    sourceLanguageTag = "ko-KR",
                    targetLanguageTag = "en",
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startTime
            Log.i(TAG, "E4B translation output (${elapsedMs}ms): $e4bTranslation")

            assertTrue("E4B 번역 결과가 비어 있습니다.", e4bTranslation.isNotBlank())
            assertFalse("E4B 번역이 한국어 원문을 복사했습니다.", e4bTranslation.contains("대피"))
            val lowerResult = e4bTranslation.lowercase()
            val hasSemanticTerm = lowerResult.contains("safe") || lowerResult.contains("evacuat")
            assertTrue("E4B 번역에 안전/대피 관련 핵심 단어(safe, evacuat)가 포함되어야 합니다: $e4bTranslation", hasSemanticTerm)

            // Step 3: Rollback to E2B Standard and verify self-test
            val restoreResult = provider.applyVerifiedModel(standardVariant)
            Log.i(TAG, "Restored to E2B standard: $restoreResult")

            manager.refresh()
            assertEquals("복구 후 적용 모델이 STANDARD여야 합니다.", standardVariant, manager.appliedVariant)
            assertEquals(GemmaModelReadiness.READY, manager.status.value.readiness)

            // Verify E2B checksum is still bit-exact
            val standardRestoredDigest = computeSha256(standardFile)
            assertEquals("E2B 모델 SHA-256이 변함없이 일치해야 합니다.", standardInitialDigest, standardRestoredDigest)

            // Verify E2B self-test runs cleanly after E4B engine unloaded
            val e2bPostRollbackSelfTest = withTimeout(11 * 60 * 1_000L) {
                provider.selfTest()
            }
            assertTrue("복구 후 E2B self-test 번역이 비어 있습니다.", e2bPostRollbackSelfTest.isNotBlank())
            Log.i(TAG, "E2B post-rollback self-test verified: $e2bPostRollbackSelfTest")

        } finally {
            // (4) Restore initial variant
            runCatching {
                if (manager.selectedVariant != initialVariant) {
                    provider.selectModel(initialVariant)
                }
            }
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val len = input.read(buf)
                if (len < 0) break
                digest.update(buf, 0, len)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun isGemmaModelServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == GemmaModelService::class.java.name }

    companion object {
        private const val TAG = "GemmaE4BDeviceTest"
    }
}
