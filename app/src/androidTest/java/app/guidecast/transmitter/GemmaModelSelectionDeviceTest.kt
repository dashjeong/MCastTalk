package app.guidecast.transmitter

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.provider.gemma.translation.GemmaModelReadiness
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GemmaModelSelectionDeviceTest {
    @Test fun malformedGpuImportNeverReplacesStandardArtifactOrBecomesReady() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = (context.applicationContext as GuideCastApplication).gemmaTranslationProvider
        val manager = provider.modelManager
        if (android.os.Build.VERSION.SDK_INT < 31) {
            val before = manager.selectedVariant
            val result = runCatching { provider.selectModel(GemmaModelVariant.GPU_OPTIMIZED) }
            assertTrue("Unsupported GPU selection must be rejected before any download", result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("기본형"))
            assertEquals(before, manager.selectedVariant)
            return@runBlocking
        }
        val original = manager.selectedVariant
        val standard = File(context.filesDir, "models/${GemmaModelVariant.STANDARD.fileName}")
        val originalSize = standard.length()
        val originalModified = standard.lastModified()
        val gpuPartial = File(context.filesDir, "models/${GemmaModelVariant.GPU_OPTIMIZED.fileName}.download")
        // Never discard a user's resumable download for an automated negative fixture.
        assumeFalse("Existing GPU partial must be preserved", gpuPartial.exists())
        val fixture = File.createTempFile("invalid-gemma-", ".litertlm", context.cacheDir)
        try {
            fixture.writeText("Not a Gemma model")
            provider.selectModel(GemmaModelVariant.GPU_OPTIMIZED)
            val result = runCatching { manager.importModel(Uri.fromFile(fixture)) }
            assertTrue(result.isFailure)
            assertEquals(GemmaModelReadiness.FAILED, manager.status.value.readiness)
            assertEquals(GemmaModelVariant.GPU_OPTIMIZED, manager.status.value.variant)
            assertFalse(gpuPartial.exists())
            assertEquals(originalSize, standard.length())
            assertEquals(originalModified, standard.lastModified())
            provider.selectModel(GemmaModelVariant.STANDARD)
            assertEquals(GemmaModelVariant.STANDARD, manager.status.value.variant)
            assertEquals(2_588_147_712L, manager.status.value.totalBytes)
        } finally {
            fixture.delete()
            provider.selectModel(original)
        }
    }
}
