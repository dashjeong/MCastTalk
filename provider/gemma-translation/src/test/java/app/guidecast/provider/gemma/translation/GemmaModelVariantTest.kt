package app.guidecast.provider.gemma.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GemmaModelVariantTest {
    @Test fun `android 10 eight GB cannot enable a GPU only artifact`() {
        assertNotNull(GemmaModelVariant.GPU_OPTIMIZED.compatibilityIssue(29))
        assertNotNull(GemmaModelVariant.GPU_OPTIMIZED.compatibilityIssue(30))
        assertNull(GemmaModelVariant.STANDARD.compatibilityIssue(29))
        assertNull(GemmaModelVariant.GPU_OPTIMIZED.compatibilityIssue(31))
        assertNull(GemmaModelVariant.GPU_OPTIMIZED.compatibilityIssue(35))
    }

    @Test fun `variants are distinct pinned artifacts with isolated caches`() {
        val variants = GemmaModelVariant.entries
        assertEquals(3, variants.size)
        assertEquals(3, variants.map { it.fileName }.distinct().size)
        assertEquals(3, variants.map { it.sha256 }.distinct().size)
        assertEquals(3, variants.map { it.cacheDirectoryName }.distinct().size)
        variants.forEach {
            assertTrue(it.sha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue(it.revision.matches(Regex("[a-f0-9]{40}")))
            assertTrue(it.downloadUrl.startsWith("https://huggingface.co/"))
            assertFalse(it.downloadUrl.contains("/main/"))
            assertEquals(it, GemmaModelVariant.fromId(it.id))
            assertEquals(it.sizeBytes, GemmaModelStatus(variant = it).totalBytes)
        }
        assertEquals(2_008_432_640L, GemmaModelVariant.GPU_OPTIMIZED.sizeBytes)
        assertTrue(GemmaModelVariant.GPU_OPTIMIZED.gpuOnly)
        assertFalse(GemmaModelVariant.STANDARD.gpuOnly)
        assertFalse(GemmaModelVariant.E4B_IT.gpuOnly)
        assertEquals("cache", GemmaModelVariant.STANDARD.cacheDirectoryName)
        assertEquals("cache-gpu_optimized", GemmaModelVariant.GPU_OPTIMIZED.cacheDirectoryName)
        assertEquals("cache-e4b_it", GemmaModelVariant.E4B_IT.cacheDirectoryName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown IPC identity cannot become a file path`() {
        GemmaModelVariant.fromId("../../other-model")
    }

    @Test fun `preserves legacy enum compatibility methods and catalog descriptor fields`() {
        assertEquals(3, GemmaModelVariant.values().size)
        assertEquals(GemmaModelVariant.STANDARD, GemmaModelVariant.valueOf("STANDARD"))
        assertEquals(GemmaModelVariant.GPU_OPTIMIZED, GemmaModelVariant.valueOf("GPU_OPTIMIZED"))
        assertEquals(GemmaModelVariant.E4B_IT, GemmaModelVariant.valueOf("E4B_IT"))
        assertTrue(GemmaModelVariant.STANDARD.isBuiltin)
        assertTrue(GemmaModelVariant.GPU_OPTIMIZED.isBuiltin)
        assertTrue(GemmaModelVariant.E4B_IT.isBuiltin)
        assertEquals("gemma4-translator-v1", GemmaModelVariant.STANDARD.runtimeContract)
        assertEquals(29, GemmaModelVariant.STANDARD.minSdk)
        assertEquals(31, GemmaModelVariant.GPU_OPTIMIZED.minSdk)
        assertEquals(29, GemmaModelVariant.E4B_IT.minSdk)
    }

    @Test fun `selection cannot race nested download and runtime verification`() = runBlocking {
        val gate = GemmaModelSelectionGate()
        gate.withSelectedModel {
            gate.withSelectedModel { assertTrue(runCatching { gate.change { } }.isFailure) }
            assertTrue(runCatching { gate.change { } }.isFailure)
        }
        gate.change { }
    }

    @Test fun `new native request is rejected while model identity changes`() = runBlocking {
        val gate = GemmaModelSelectionGate()
        gate.change {
            assertTrue(runCatching { gate.withSelectedModel { } }.isFailure)
            assertTrue(runCatching { gate.change { } }.isFailure)
        }
        gate.withSelectedModel { }
    }

    @Test fun `cancellation releases selection lease`() = runBlocking {
        val gate = GemmaModelSelectionGate()
        val entered = CompletableDeferred<Unit>()
        val work = launch {
            gate.withSelectedModel {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        assertTrue(runCatching { gate.change { } }.isFailure)
        work.cancelAndJoin()
        gate.change { }
    }

    @Test fun `failed change does not permanently lock selection`() = runBlocking {
        val gate = GemmaModelSelectionGate()
        assertTrue(runCatching { gate.change { error("storage failure") } }.isFailure)
        gate.withSelectedModel { }
        gate.change { }
    }
}
