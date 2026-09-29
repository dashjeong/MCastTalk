package app.guidecast.provider.gemma.translation

import java.io.File

/**
 * Storage budget and cache lifecycle guard for large Gemma models (e.g. E4B).
 *
 * Background:
 * On LiteRT-LM CPU inference, XNNPACK compiles model weights into a local cache.
 * For E4B (3.66 GB model file), the physical XNNPack weight cache expands to 2,210,334,416 bytes
 * (~2.06 GiB). If host/guest storage is exhausted during weight cache generation, LiteRT-LM native
 * code calls abort() (Fatal signal 6 SIGABRT in weight_cache.cc:750), terminating the process.
 *
 * Safety policy:
 * 1. Distinguished conservative budget: We reserve 2,400 MiB (~2.34 GiB, ~1.14x measured 2.06 GiB)
 *    for E4B cache generation on CPU.
 * 2. Completion marker: Only after Engine.initialize() succeeds without error on CPU, a completion
 *    marker is atomically recorded containing model SHA, model length/mtime, runtime contract/version,
 *    and exact cache file attributes matching the specific model file.
 * 3. Strict validation: Markers larger than 4 KiB, truncated, unclosed, or mismatched against model
 *    name/length/mtime/contract are strictly rejected.
 * 4. Atomic-only write: If atomic renameTo fails, the marker is deleted and not recognized.
 * 5. Re-use: When a valid completion marker and intact cache file exist, required cache space is 0B.
 * 6. Scope limitation: This policy applies specifically to E4B on CPU to prevent SIGABRT crashes
 *    without imposing new restrictive barriers on standard E2B models.
 * 7. Disclaimer: While this pre-JNI guard mitigates deterministic out-of-space aborts on CPU,
 *    it does not guarantee prevention of concurrent external disk space contention or driver faults
 *    in diverse GPU environments.
 */
object GemmaStoragePolicy {
    const val STORAGE_SAFETY_BYTES = 512L * 1024 * 1024 // 512 MiB baseline pre-download
    const val RUNTIME_SAFETY_BYTES = 128L * 1024 * 1024 // 128 MiB runtime scratch margin
    const val E4B_CACHE_BUDGET_BYTES = 2_400L * 1024 * 1024 // 2,400 MiB (~2.34 GiB) conservative budget
    const val MARKER_FILE_NAME = "cache_completion_marker.json"
    const val MAX_MARKER_FILE_BYTES = 4096L // 4 KiB strict size limit
    const val DEFAULT_RUNTIME_VERSION = GemmaModelManager.RUNTIME_VERSION
    const val CPU_BACKEND = "CPU"

    data class CacheMarker(
        val modelSha: String,
        val modelLength: Long,
        val modelLastModified: Long,
        val runtimeContract: String,
        val runtimeVersion: String,
        val backend: String,
        val cacheFileName: String,
        val cacheFileLength: Long,
        val cacheFileLastModified: Long,
    ) {
        fun toJson(): String = buildString {
            append("{\n")
            append("  \"modelSha\": \"$modelSha\",\n")
            append("  \"modelLength\": $modelLength,\n")
            append("  \"modelLastModified\": $modelLastModified,\n")
            append("  \"runtimeContract\": \"$runtimeContract\",\n")
            append("  \"runtimeVersion\": \"$runtimeVersion\",\n")
            append("  \"backend\": \"$backend\",\n")
            append("  \"cacheFileName\": \"$cacheFileName\",\n")
            append("  \"cacheFileLength\": $cacheFileLength,\n")
            append("  \"cacheFileLastModified\": $cacheFileLastModified\n")
            append("}\n")
        }

        companion object {
            private val REQUIRED_KEYS = setOf(
                "modelSha",
                "modelLength",
                "modelLastModified",
                "runtimeContract",
                "runtimeVersion",
                "backend",
                "cacheFileName",
                "cacheFileLength",
                "cacheFileLastModified",
            )

            /**
             * Strict parser that rejects truncated documents, missing braces, extra fields,
             * or malformed lines without relying on Android-dependent JSON libraries.
             */
            fun parseJsonStrict(text: String): CacheMarker? = runCatching {
                val trimmed = text.trim()
                if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

                val lines = trimmed.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "{" && it != "}" }

                val map = mutableMapOf<String, String>()
                for (line in lines) {
                    val match = Regex("^\"([a-zA-Z0-9]+)\"\\s*:\\s*(?:\"([^\"]*)\"|([0-9]+)),?$")
                        .matchEntire(line) ?: return null
                    val key = match.groupValues[1]
                    val strVal = match.groupValues[2]
                    val numVal = match.groupValues[3]
                    map[key] = if (strVal.isNotEmpty()) strVal else numVal
                }

                if (map.keys != REQUIRED_KEYS) return null

                CacheMarker(
                    modelSha = map.getValue("modelSha"),
                    modelLength = map.getValue("modelLength").toLong(),
                    modelLastModified = map.getValue("modelLastModified").toLong(),
                    runtimeContract = map.getValue("runtimeContract"),
                    runtimeVersion = map.getValue("runtimeVersion"),
                    backend = map.getValue("backend"),
                    cacheFileName = map.getValue("cacheFileName"),
                    cacheFileLength = map.getValue("cacheFileLength").toLong(),
                    cacheFileLastModified = map.getValue("cacheFileLastModified").toLong(),
                ).takeIf { it.toJson() == text }
            }.getOrNull()
        }
    }

    fun isCacheBudgetRequired(variant: GemmaModelVariant): Boolean =
        variant.id == GemmaModelVariant.E4B_IT_ID

    fun isSafeCacheFileName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return false
        return name.endsWith(".xnnpack_cache")
    }

    fun isCacheComplete(
        cacheDir: File?,
        modelFile: File?,
        variant: GemmaModelVariant,
        runtimeVersion: String = DEFAULT_RUNTIME_VERSION,
        backend: String = CPU_BACKEND,
    ): Boolean {
        if (!isCacheBudgetRequired(variant)) return true
        // XNNPack weight cache completion marker is strictly a CPU artifact.
        // GPU models/backends do not use XNNPack weight caches.
        if (backend != CPU_BACKEND) return false
        if (cacheDir == null || !cacheDir.isDirectory) return false
        if (modelFile == null || !modelFile.isFile) return false

        val markerFile = File(cacheDir, MARKER_FILE_NAME)
        if (!markerFile.isFile) return false

        val fileLength = markerFile.length()
        if (fileLength !in 1L..MAX_MARKER_FILE_BYTES) return false

        val text = runCatching { markerFile.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        val marker = CacheMarker.parseJsonStrict(text) ?: return false

        if (marker.modelSha != variant.sha256) return false
        if (marker.modelLength != modelFile.length() || marker.modelLength != variant.sizeBytes) return false
        if (marker.modelLastModified != modelFile.lastModified()) return false
        if (marker.runtimeContract != variant.runtimeContract) return false
        if (marker.runtimeVersion != runtimeVersion) return false
        if (marker.backend != CPU_BACKEND) return false

        if (!isSafeCacheFileName(marker.cacheFileName)) return false

        if (marker.cacheFileName != expectedCacheFileName(modelFile)) return false

        val cacheFile = File(cacheDir, marker.cacheFileName)
        if (!cacheFile.isFile) return false
        if (cacheFile.length() <= 0L) return false
        if (cacheFile.length() != marker.cacheFileLength) return false
        if (cacheFile.lastModified() != marker.cacheFileLastModified) return false

        return true
    }

    /**
     * Atomically records the completion marker after successful Engine.initialize() on CPU.
     * Per design requirements:
     * - Only CPU backend can record XNNPack weight cache completion.
     * - Only files matching the current model's name, length, and mtime are considered.
     * - Writing is strictly atomic via renameTo; if atomic rename fails, marker is deleted and not recognized.
     * - Failure to record marker does not convert a successful translation into a failure.
     */
    fun recordCacheCompletionSafely(
        cacheDir: File?,
        modelFile: File?,
        variant: GemmaModelVariant,
        runtimeVersion: String = DEFAULT_RUNTIME_VERSION,
        backend: String = CPU_BACKEND,
    ): Boolean {
        if (!isCacheBudgetRequired(variant)) return true
        if (backend != CPU_BACKEND) return false
        return runCatching {
            if (cacheDir == null || !cacheDir.isDirectory) return false
            if (modelFile == null || !modelFile.isFile) return false

            val candidates = cacheDir.listFiles { file ->
                file.isFile &&
                    file.name == expectedCacheFileName(modelFile) &&
                    isSafeCacheFileName(file.name) &&
                    file.length() > 0L &&
                    file.lastModified() >= modelFile.lastModified()
            }.orEmpty()

            val targetCacheFile = candidates.maxByOrNull { it.lastModified() } ?: return false

            val marker = CacheMarker(
                modelSha = variant.sha256,
                modelLength = modelFile.length(),
                modelLastModified = modelFile.lastModified(),
                runtimeContract = variant.runtimeContract,
                runtimeVersion = runtimeVersion,
                backend = CPU_BACKEND,
                cacheFileName = targetCacheFile.name,
                cacheFileLength = targetCacheFile.length(),
                cacheFileLastModified = targetCacheFile.lastModified(),
            )

            val markerFile = File(cacheDir, MARKER_FILE_NAME)
            val tempMarkerFile = File(cacheDir, "$MARKER_FILE_NAME.tmp")
            tempMarkerFile.writeText(marker.toJson(), Charsets.UTF_8)
            val renamed = tempMarkerFile.renameTo(markerFile)
            if (!renamed) {
                tempMarkerFile.delete()
                return false
            }
            true
        }.getOrDefault(false)
    }

    fun requiredDownloadCacheBudget(
        cacheDir: File?,
        modelFile: File?,
        variant: GemmaModelVariant,
        runtimeVersion: String = DEFAULT_RUNTIME_VERSION,
    ): Long {
        if (!isCacheBudgetRequired(variant)) return 0L
        if (isCacheComplete(cacheDir, modelFile, variant, runtimeVersion, CPU_BACKEND)) return 0L
        return E4B_CACHE_BUDGET_BYTES
    }

    fun ensureRuntimeStorage(
        cacheDir: File?,
        modelFile: File?,
        variant: GemmaModelVariant,
        backend: String,
        availableBytes: Long,
        runtimeVersion: String = DEFAULT_RUNTIME_VERSION,
    ) {
        if (!isCacheBudgetRequired(variant)) return
        val complete = isCacheComplete(cacheDir, modelFile, variant, runtimeVersion, backend)
        val requiredBytes = if (complete) {
            RUNTIME_SAFETY_BYTES
        } else {
            E4B_CACHE_BUDGET_BYTES + RUNTIME_SAFETY_BYTES
        }
        check(availableBytes >= requiredBytes) {
            val role = if (complete) "런타임 안전 여유" else "추론 엔진 캐시 생성 및 런타임 여유"
            "저장 공간이 부족합니다 ($role). 최소 ${requiredBytes.toGiBText()}가 필요하지만 " +
                "현재 ${availableBytes.toGiBText()}만 사용할 수 있습니다. 디스크 공간을 확보한 후 다시 시도하세요."
        }
    }

    fun Long.toGiBText(): String = "%.2f GiB".format(this / 1_073_741_824.0)

    // LiteRT-LM 0.16.1 names the CPU cache with the model mtime in Unix seconds.
    private fun expectedCacheFileName(model: File): String =
        "${model.name}_${model.lastModified() / 1_000L}_${model.length()}.xnnpack_cache"
}
