package app.guidecast.provider.gemma.translation

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GemmaStoragePolicyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val e4bVariant = GemmaModelVariant.E4B_IT
    private val standardVariant = GemmaModelVariant.STANDARD
    private val gpuVariant = GemmaModelVariant.GPU_OPTIMIZED

    @Test
    fun `old model timestamp in cache filename cannot gain a new completion marker`() {
        val cache = tempFolder.newFolder("old_timestamp")
        val model = tempFolder.newFile("model.litertlm").apply { writeBytes(ByteArray(1024)) }
        File(cache, "${model.name}_123_${model.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(model.lastModified() + 1000L)
        }
        assertFalse(GemmaStoragePolicy.recordCacheCompletionSafely(
            cache, model, e4bVariant.copy(sizeBytes = model.length())))
    }

    @Test
    fun `malformed commas and duplicate marker keys are rejected`() {
        val marker = GemmaStoragePolicy.CacheMarker("abc", 1, 1, "contract", "version",
            "CPU", "model_1_1.xnnpack_cache", 1, 1)
        val json = marker.toJson()
        assertNotNull(GemmaStoragePolicy.CacheMarker.parseJsonStrict(json))
        assertNull(GemmaStoragePolicy.CacheMarker.parseJsonStrict(json.replace(",", "")))
        assertNull(GemmaStoragePolicy.CacheMarker.parseJsonStrict(
            json.replace("{\n", "{\n  \"modelSha\": \"abc\",\n")))
    }

    @Test
    fun `e2b variants are exempt from e4b cache budget restrictions`() {
        assertFalse(GemmaStoragePolicy.isCacheBudgetRequired(standardVariant))
        assertFalse(GemmaStoragePolicy.isCacheBudgetRequired(gpuVariant))
        assertTrue(GemmaStoragePolicy.isCacheBudgetRequired(e4bVariant))

        // Standard models require 0 download cache budget
        val dummyDir = tempFolder.newFolder("standard_dummy")
        val dummyModel = File(dummyDir, standardVariant.fileName).apply { writeBytes(ByteArray(10)) }
        assertEquals(0L, GemmaStoragePolicy.requiredDownloadCacheBudget(dummyDir, dummyModel, standardVariant))
        assertEquals(0L, GemmaStoragePolicy.requiredDownloadCacheBudget(dummyDir, dummyModel, gpuVariant))

        // Runtime storage check does not impose E4B cache requirement on standard models
        GemmaStoragePolicy.ensureRuntimeStorage(
            cacheDir = dummyDir,
            modelFile = dummyModel,
            variant = standardVariant,
            backend = "CPU",
            availableBytes = 10L * 1024 * 1024, // 10 MiB
        )
    }

    @Test
    fun `e4b requires conservative 2400MiB cache budget when marker is absent`() {
        val cacheDir = tempFolder.newFolder("e4b_cache_empty")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }

        assertEquals(
            GemmaStoragePolicy.E4B_CACHE_BUDGET_BYTES,
            GemmaStoragePolicy.requiredDownloadCacheBudget(cacheDir, modelFile, e4bVariant),
        )
        assertFalse(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, e4bVariant))

        // Available space less than 2,400 MiB + 128 MiB throws
        val insufficientSpace = GemmaStoragePolicy.E4B_CACHE_BUDGET_BYTES + (50L * 1024 * 1024)
        val failure = assertThrows(IllegalStateException::class.java) {
            GemmaStoragePolicy.ensureRuntimeStorage(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = e4bVariant,
                backend = "CPU",
                availableBytes = insufficientSpace,
            )
        }
        assertTrue(failure.message!!.contains("추론 엔진 캐시 생성 및 런타임 여유"))

        // Available space meeting 2,400 MiB + 128 MiB succeeds
        val sufficientSpace = GemmaStoragePolicy.E4B_CACHE_BUDGET_BYTES + GemmaStoragePolicy.RUNTIME_SAFETY_BYTES
        GemmaStoragePolicy.ensureRuntimeStorage(
            cacheDir = cacheDir,
            modelFile = modelFile,
            variant = e4bVariant,
            backend = "CPU",
            availableBytes = sufficientSpace,
        )
    }

    @Test
    fun `successful engine initialize records completion marker and enables cache reuse`() {
        val cacheDir = tempFolder.newFolder("e4b_cache_valid")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        // Cache filename follows LiteRT-LM naming: ${modelFile.name}_<mtime-seconds>_${modelFile.length()}.xnnpack_cache
        val cacheFile = File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_${modelFile.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }

        // Record completion
        val recorded = GemmaStoragePolicy.recordCacheCompletionSafely(
            cacheDir = cacheDir,
            modelFile = modelFile,
            variant = testVariant,
            backend = "CPU",
        )
        assertTrue(recorded)

        val markerFile = File(cacheDir, GemmaStoragePolicy.MARKER_FILE_NAME)
        assertTrue(markerFile.isFile)

        // Cache is now recognized as complete
        assertTrue(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant, backend = "CPU"))

        // Required download cache budget drops to 0
        assertEquals(0L, GemmaStoragePolicy.requiredDownloadCacheBudget(cacheDir, modelFile, testVariant))

        // Pre-runtime guard now only requires 128 MiB runtime scratch space
        GemmaStoragePolicy.ensureRuntimeStorage(
            cacheDir = cacheDir,
            modelFile = modelFile,
            variant = testVariant,
            backend = "CPU",
            availableBytes = 200L * 1024 * 1024, // 200 MiB > 128 MiB
        )

        // Less than 128 MiB runtime scratch space still fails
        val scratchFailure = assertThrows(IllegalStateException::class.java) {
            GemmaStoragePolicy.ensureRuntimeStorage(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
                availableBytes = 50L * 1024 * 1024, // 50 MiB < 128 MiB
            )
        }
        assertTrue(scratchFailure.message!!.contains("런타임 안전 여유"))
    }

    @Test
    fun `xnnpack cache marker is strictly CPU only and cannot be recorded or verified for GPU`() {
        val cacheDir = tempFolder.newFolder("e4b_cache_gpu_test")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_${modelFile.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }

        // GPU backend cannot record XNNPACK cache completion marker
        assertFalse(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "GPU",
            )
        )

        // Record on CPU first
        assertTrue(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )

        // Verification for GPU backend returns false
        assertFalse(
            GemmaStoragePolicy.isCacheComplete(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "GPU",
            )
        )
    }

    @Test
    fun `strict parser rejects truncated documents missing braces and oversized files`() {
        val cacheDir = tempFolder.newFolder("e4b_strict_parse")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)
        val markerFile = File(cacheDir, GemmaStoragePolicy.MARKER_FILE_NAME)

        // 1. Truncated document without closing brace
        val truncatedJson = """
            {
              "modelSha": "${testVariant.sha256}",
              "modelLength": 1024,
              "modelLastModified": ${modelFile.lastModified()},
              "runtimeContract": "gemma4-translator-v1"
        """.trimIndent()
        markerFile.writeText(truncatedJson, Charsets.UTF_8)
        assertNull(GemmaStoragePolicy.CacheMarker.parseJsonStrict(truncatedJson))
        assertFalse(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant))

        // 2. Extra unapproved keys
        val extraKeyJson = """
            {
              "modelSha": "${testVariant.sha256}",
              "modelLength": 1024,
              "modelLastModified": ${modelFile.lastModified()},
              "runtimeContract": "gemma4-translator-v1",
              "runtimeVersion": "LiteRT-LM Android 0.16.1",
              "backend": "CPU",
              "cacheFileName": "${modelFile.name}_${modelFile.lastModified() / 1000L}_1024.xnnpack_cache",
              "cacheFileLength": 2048,
              "cacheFileLastModified": 5000,
              "maliciousKey": "hacked"
            }
        """.trimIndent()
        assertNull(GemmaStoragePolicy.CacheMarker.parseJsonStrict(extraKeyJson))

        // 3. Oversized file (> 4 KiB)
        val oversizedJson = buildString {
            append("{\n")
            append("  \"modelSha\": \"${testVariant.sha256}\",\n")
            append("  \"modelLength\": 1024,\n")
            append("  \"modelLastModified\": ${modelFile.lastModified()},\n")
            append("  \"runtimeContract\": \"gemma4-translator-v1\",\n")
            append("  \"runtimeVersion\": \"LiteRT-LM Android 0.16.1\",\n")
            append("  \"backend\": \"CPU\",\n")
            append("  \"cacheFileName\": \"${modelFile.name}_${modelFile.lastModified() / 1000L}_1024.xnnpack_cache\",\n")
            append("  \"cacheFileLength\": 2048,\n")
            append("  \"cacheFileLastModified\": 5000\n")
            append("}\n")
            append("/* " + "A".repeat(5000) + " */")
        }
        markerFile.writeText(oversizedJson, Charsets.UTF_8)
        assertTrue(markerFile.length() > GemmaStoragePolicy.MAX_MARKER_FILE_BYTES)
        assertFalse(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant))
    }

    @Test
    fun `arbitrary or older cache files not matching model name and length are rejected`() {
        val cacheDir = tempFolder.newFolder("e4b_unrelated_cache")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        // 1. File from another model (different prefix)
        File(cacheDir, "other-model.litertlm_${modelFile.lastModified() / 1000L}_1024.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }
        assertFalse(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )

        // 2. File with different model length in suffix
        File(cacheDir, "${modelFile.name}_123_9999.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }
        assertFalse(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )

        // 3. File with mtime older than model file
        File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_1024.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() - 10_000L)
        }
        assertFalse(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )
    }

    @Test
    fun `cache completion marker is invalidated when model file changes`() {
        val cacheDir = tempFolder.newFolder("e4b_cache_model_drift")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_${modelFile.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }

        assertTrue(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )
        assertTrue(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant))

        // Change model file mtime (simulate model redownload/replacement)
        modelFile.setLastModified(modelFile.lastModified() + 50_000L)
        assertFalse(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant))
        assertEquals(
            GemmaStoragePolicy.E4B_CACHE_BUDGET_BYTES,
            GemmaStoragePolicy.requiredDownloadCacheBudget(cacheDir, modelFile, testVariant),
        )
    }

    @Test
    fun `cache completion marker is invalidated when runtime version drifts`() {
        val cacheDir = tempFolder.newFolder("e4b_cache_runtime_drift")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_${modelFile.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }

        assertTrue(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                runtimeVersion = "LiteRT-LM Android 0.16.1",
                backend = "CPU",
            )
        )

        // Old cache should NOT be accepted if runtime is upgraded
        assertFalse(
            GemmaStoragePolicy.isCacheComplete(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                runtimeVersion = "LiteRT-LM Android 0.17.0",
                backend = "CPU",
            )
        )
    }

    @Test
    fun `cache completion marker is invalidated when cache file is deleted or truncated`() {
        val cacheDir = tempFolder.newFolder("e4b_cache_deleted")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        val cacheFile = File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_${modelFile.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(2048))
            setLastModified(modelFile.lastModified() + 1000L)
        }

        assertTrue(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )

        // Delete cache file
        assertTrue(cacheFile.delete())
        assertFalse(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant))

        // Recreate empty cache file (0 bytes)
        cacheFile.createNewFile()
        assertFalse(GemmaStoragePolicy.isCacheComplete(cacheDir, modelFile, testVariant))
    }

    @Test
    fun `empty cache files and unsafe paths cannot be recorded or verified`() {
        val cacheDir = tempFolder.newFolder("e4b_unsafe")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }
        val testVariant = e4bVariant.copy(sizeBytes = 1024L)

        // Empty file cannot be recorded
        File(cacheDir, "${modelFile.name}_${modelFile.lastModified() / 1000L}_${modelFile.length()}.xnnpack_cache").apply {
            writeBytes(ByteArray(0))
            setLastModified(modelFile.lastModified() + 1000L)
        }
        assertFalse(
            GemmaStoragePolicy.recordCacheCompletionSafely(
                cacheDir = cacheDir,
                modelFile = modelFile,
                variant = testVariant,
                backend = "CPU",
            )
        )

        // Unsafe filenames are rejected
        assertFalse(GemmaStoragePolicy.isSafeCacheFileName("../evil.xnnpack_cache"))
        assertFalse(GemmaStoragePolicy.isSafeCacheFileName("/tmp/evil.xnnpack_cache"))
        assertFalse(GemmaStoragePolicy.isSafeCacheFileName("evil.txt"))
        assertTrue(GemmaStoragePolicy.isSafeCacheFileName("gemma-4-E4B-it.litertlm_123.xnnpack_cache"))
    }

    @Test
    fun `marker recording failure does not crash process`() {
        val unwriteableDir = File("/nonexistent/readonly/path")
        val modelFile = File(tempFolder.root, e4bVariant.fileName).apply {
            writeBytes(ByteArray(1024))
        }

        // Per requirement: failure to write marker returns false gracefully without throwing
        val recorded = GemmaStoragePolicy.recordCacheCompletionSafely(
            cacheDir = unwriteableDir,
            modelFile = modelFile,
            variant = e4bVariant,
            backend = "CPU",
        )
        assertFalse(recorded)
    }
}
