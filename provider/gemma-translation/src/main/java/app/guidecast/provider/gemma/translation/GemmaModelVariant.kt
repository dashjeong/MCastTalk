package app.guidecast.provider.gemma.translation

/**
 * Descriptor for a validated Gemma model artifact.
 *
 * Both builtin models use mobile quantization; GPU is not a new QAT model.
 */
data class GemmaModelVariant(
    val id: String,
    val label: String,
    val fileName: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
    val gpuOnly: Boolean,
    val minSdk: Int = 29,
    val runtimeContract: String = DEFAULT_RUNTIME_CONTRACT,
    val repositoryUrl: String = GemmaModelManager.MODEL_REPOSITORY_URL,
    val isBuiltin: Boolean = false,
) {
    val downloadUrl: String
        get() = "$repositoryUrl/resolve/$revision/$fileName?download=true"

    val cacheDirectoryName: String
        get() = if (id == STANDARD_ID) "cache" else "cache-$id"

    /** Match the deployed runtime's API gate before spending bandwidth or mapping a model. */
    fun compatibilityIssue(sdkInt: Int): String? = if (gpuOnly && sdkInt < 31) {
        "Android 10·11에서는 GPU 최적화형을 실행하지 않습니다. E2B 기본형을 선택하세요. " +
            "Note9에서는 기본형 CPU 경로를 사용하며 실제 처리 속도는 별도 점검이 필요합니다."
    } else if (sdkInt < minSdk) {
        "이 모델은 Android API $minSdk 이상이 필요합니다 (현재 API $sdkInt)."
    } else null

    companion object {
        const val DEFAULT_RUNTIME_CONTRACT = "gemma4-translator-v1"
        const val STANDARD_ID = "standard"
        const val GPU_OPTIMIZED_ID = "gpu_optimized"

        @JvmField
        val STANDARD: GemmaModelVariant = GemmaModelVariant(
            id = STANDARD_ID,
            label = "E2B 기본형",
            fileName = GemmaModelManager.MODEL_FILE_NAME,
            revision = GemmaModelManager.MODEL_REVISION,
            sizeBytes = GemmaModelManager.MODEL_SIZE_BYTES,
            sha256 = GemmaModelManager.MODEL_SHA256,
            gpuOnly = false,
            minSdk = 29,
            runtimeContract = DEFAULT_RUNTIME_CONTRACT,
            repositoryUrl = GemmaModelManager.MODEL_REPOSITORY_URL,
            isBuiltin = true,
        )

        @JvmField
        val GPU_OPTIMIZED: GemmaModelVariant = GemmaModelVariant(
            id = GPU_OPTIMIZED_ID,
            label = "E2B GPU 최적화형 · 시험용",
            fileName = "gemma-4-E2B-it-gpu.litertlm",
            revision = "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94",
            sizeBytes = 2_008_432_640L,
            sha256 = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
            gpuOnly = true,
            minSdk = 31,
            runtimeContract = DEFAULT_RUNTIME_CONTRACT,
            repositoryUrl = GemmaModelManager.MODEL_REPOSITORY_URL,
            isBuiltin = true,
        )

        val BUILTIN_VARIANTS: List<GemmaModelVariant> = listOf(STANDARD, GPU_OPTIMIZED)

        val entries: List<GemmaModelVariant>
            get() = BUILTIN_VARIANTS

        fun values(): Array<GemmaModelVariant> = entries.toTypedArray()

        fun valueOf(name: String): GemmaModelVariant = when (name) {
            "STANDARD" -> STANDARD
            "GPU_OPTIMIZED" -> GPU_OPTIMIZED
            else -> fromId(name)
        }

        fun fromId(id: String): GemmaModelVariant =
            requireNotNull(entries.firstOrNull { it.id == id }) { "알 수 없는 Gemma 모델입니다: $id" }
    }
}

/** Holds the selected artifact stable across download -> verification -> native self-test. */
internal class GemmaModelSelectionGate {
    private val lock = Any()
    private var users = 0
    private var changing = false

    suspend fun <T> withSelectedModel(block: suspend () -> T): T {
        synchronized(lock) {
            check(!changing) { "Gemma 모델을 변경 중입니다. 잠시 후 다시 시도하세요." }
            users++
        }
        return try { block() } finally { synchronized(lock) { users-- } }
    }

    suspend fun <T> change(block: suspend () -> T): T {
        synchronized(lock) {
            check(!changing && users == 0) { "모델 준비·시험이 끝난 뒤 변경하세요." }
            changing = true
        }
        return try { block() } finally { synchronized(lock) { changing = false } }
    }
}
