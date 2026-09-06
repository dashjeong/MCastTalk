package app.guidecast.transmitter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.provider.gemma.translation.GemmaCatalogStore
import app.guidecast.provider.gemma.translation.GemmaModelManager
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignedCatalogDeviceTest {
    @Test fun actualApkSignerAcceptsCatalogAndRejectsReplayAndCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.getExternalFilesDir(null), "model-catalog-v1.gcmodels")
        assertTrue("Stage the release-signed catalog fixture", fixture.isFile)
        val installed = File(context.filesDir, "gemma-catalog.signed")
        assertFalse("Do not replace an existing operator catalog in this explicit fixture", installed.exists())
        val store = GemmaCatalogStore(context)
        val bytes = fixture.readBytes()
        try {
            assertEquals(GemmaModelVariant.entries, store.importSigned(bytes.inputStream()))
            assertEquals(GemmaModelVariant.entries, GemmaCatalogStore(context).entries())
            assertTrue(runCatching { store.importSigned(bytes.inputStream()) }.isFailure)
            val corrupt = bytes.copyOf().also { it[0] = 0 }
            assertTrue(runCatching { store.importSigned(corrupt.inputStream()) }.isFailure)
            assertArrayEquals(bytes, installed.readBytes())
        } finally {
            assertTrue("Remove only this test-created empty catalog", !installed.exists() || installed.delete())
        }
    }

    @Test fun failedCandidateApplicationAndInterruptedSelectionRestorePreviousModel() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = (context.applicationContext as GuideCastApplication).gemmaTranslationProvider
        val before = provider.modelManager.selectedVariant
        val beforeFile = provider.modelManager.modelFile
        val originalLength = beforeFile.length()
        val gpuFile = File(context.filesDir, "models/${GemmaModelVariant.GPU_OPTIMIZED.fileName}")
        assertFalse("This negative fixture requires no installed GPU model", gpuFile.exists())
        assertTrue(runCatching { provider.applyVerifiedModel(GemmaModelVariant.GPU_OPTIMIZED) }.isFailure)
        assertEquals(before, provider.modelManager.selectedVariant)
        assertEquals(originalLength, beforeFile.length())
        val preferences = context.getSharedPreferences("guidecast_gemma_selection", 0)
        assertTrue(preferences.edit().putString("application_previous", before.id)
            .putString("variant", "gpu_optimized").commit())
        assertEquals(before, GemmaModelManager(context).selectedVariant)
        assertFalse(preferences.contains("application_previous"))
        assertEquals(before.id, preferences.getString("variant", null))
    }
}
