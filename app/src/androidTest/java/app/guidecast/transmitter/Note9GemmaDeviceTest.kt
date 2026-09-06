package app.guidecast.transmitter

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.guidecast.provider.gemma.translation.GemmaModelVariant
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit, offline native gate: requires a full pinned fixture staged in external app storage. */
@RunWith(AndroidJUnit4::class)
class Note9GemmaDeviceTest {
    @Test fun localStandardModelRunsActualCpuInference() = runBlocking {
        assertTrue("This gate targets Android 10", Build.VERSION.SDK_INT == 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = (context.applicationContext as GuideCastApplication).gemmaTranslationProvider
        val fixture = File(context.getExternalFilesDir(null), "gemma-standard-fixture.litertlm")
        assertTrue("Stage the full pinned model fixture before this explicit test", fixture.isFile)
        provider.selectModel(GemmaModelVariant.STANDARD)
        // Uses production SHA-256 verification. No network, marker injection or fake model bytes.
        if (!provider.modelManager.modelFile.isFile) {
            provider.modelManager.importModel(Uri.fromFile(fixture))
        } else {
            // Reuse the exact staged fixture without writing another 2.6GB on each regression run.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            provider.modelManager.modelFile.inputStream().use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            assertTrue(digest.digest().joinToString("") { "%02x".format(it) } == GemmaModelVariant.STANDARD.sha256)
            provider.modelManager.refresh()
        }
        try {
            val started = SystemClock.elapsedRealtime()
            val result = provider.applyVerifiedModel(GemmaModelVariant.STANDARD)
            Log.i("GuideCastNote9Gate", "actualCpuSelfTestMs=${SystemClock.elapsedRealtime() - started}")
            assertTrue("Actual Gemma output must be non-empty", result.isNotBlank())
        } finally {
            provider.resetEngineSafely()
        }
    }

    @Test fun preparedStandardModelAppliesThroughForegroundService() = runBlocking {
        assertTrue(android.os.Build.VERSION.SDK_INT == 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = (context.applicationContext as GuideCastApplication).gemmaTranslationProvider
        assertTrue("Run the full pinned import fixture first", provider.modelManager.modelFile.isFile)
        GemmaModelService.start(context, GemmaModelVariant.STANDARD.id)
        withTimeout(10_000) { GemmaModelService.preparing.first { it } }
        withTimeout(120_000) { GemmaModelService.preparing.first { !it } }
        assertTrue(GemmaModelService.preparationMessage.value.orEmpty().contains("적용 완료"))
        assertTrue(provider.modelManager.status.value.readiness ==
            app.guidecast.provider.gemma.translation.GemmaModelReadiness.READY)
        assertTrue(provider.modelManager.selectedVariant == GemmaModelVariant.STANDARD)
        provider.resetEngineSafely()
    }
}
