package app.guidecast.core.audio.nemotron

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.reuse.Spectral
import java.io.File
import java.security.MessageDigest

/** Fixed-shape NVIDIA NemotronLabs conversion. Owned, used and closed by one non-capture worker. */
class NemotronLiteRtBackend(file: File, accelerator: String, threads: Int = 2) : AutoCloseable {
    private val owner = Thread.currentThread().id
    private var env: Environment? = null
    private var model: CompiledModel? = null
    private var inputs: List<TensorBuffer> = emptyList()
    private var outputs: List<TensorBuffer> = emptyList()
    private val spectral = Spectral()
    private val spec = FloatArray(2 * FRAMES * Spectral.BINS)
    private val wave = FloatArray(CHUNK_SAMPLES)
    var lastGraphMs: Double = 0.0
        private set
    var lastTotalMs: Double = 0.0
        private set
    var availableAccelerators: String = ""
        private set

    init {
        require(threads in 1..4)
        verifyModel(file)
        try {
            val environment = Environment.create().also { env = it }
            val available = environment.getAvailableAccelerators()
            availableAccelerators = available.joinToString()
            val options = when (accelerator) {
                "gpu", "gpu_fp32" -> {
                    check(Accelerator.GPU in available) { "No LiteRT GPU accelerator: $availableAccelerators" }
                    CompiledModel.Options(Accelerator.GPU).apply {
                        if (accelerator == "gpu_fp32") gpuOptions = CompiledModel.GpuOptions(
                            precision = CompiledModel.GpuOptions.Precision.FP32)
                    }
                }
                "cpu" -> CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
                }
                else -> error("Unknown accelerator: $accelerator")
            }
            val compiled = CompiledModel.create(file.absolutePath, options, environment).also { model = it }
            val inputShape = checkNotNull(compiled.getInputTensorType("args_0", "serving_default").layout).dimensions
            val outputShape = checkNotNull(compiled.getOutputTensorType("output_0", "serving_default").layout).dimensions
            check(inputShape == listOf(1, 2, FRAMES, Spectral.BINS)) { "Unexpected input shape: $inputShape" }
            check(outputShape == listOf(1, 3, FRAMES, Spectral.BINS)) { "Unexpected output shape: $outputShape" }
            inputs = compiled.createInputBuffers()
            outputs = compiled.createOutputBuffers()
            check(inputs.size == 1 && outputs.size == 1)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    /** Full chunk only. No microphone reads, no network and no internal queue. */
    fun restoreChunk(input: FloatArray): FloatArray {
        checkOwner()
        val compiled = checkNotNull(model) { "Backend is closed" }
        require(input.size == CHUNK_SAMPLES && input.all { it.isFinite() && it in -1f..1f })
        val start = System.nanoTime()
        spectral.analyze(input, 0, CHUNK_SAMPLES, spec)
        val graphStart = System.nanoTime()
        inputs.single().writeFloat(spec)
        compiled.run(inputs, outputs)
        val enhanced = outputs.single().readFloat() // GPU completion is included in timing.
        lastGraphMs = (System.nanoTime() - graphStart) / 1e6
        check(enhanced.size == 3 * FRAMES * Spectral.BINS && enhanced.all { it.isFinite() }) { "Nonfinite model output" }
        spectral.synthesize(enhanced, FRAMES, wave)
        check(wave.all { it.isFinite() }) { "Nonfinite restored waveform" }
        lastTotalMs = (System.nanoTime() - start) / 1e6
        return wave.copyOf()
    }

    private fun checkOwner() { check(Thread.currentThread().id == owner) { "Use the owning inference worker" } }
    override fun close() {
        checkOwner()
        // Release every resource even if a previous release throws; never release concurrently with run().
        val ownedInputs = inputs.also { inputs = emptyList() }
        val ownedOutputs = outputs.also { outputs = emptyList() }
        val ownedModel = model.also { model = null }
        val ownedEnv = env.also { env = null }
        ownedInputs.forEach { runCatching { it.close() } }
        ownedOutputs.forEach { runCatching { it.close() } }
        runCatching { ownedModel?.close() }
        runCatching { ownedEnv?.close() }
    }

    companion object {
        const val CHUNK_SAMPLES = 32_000
        const val FRAMES = 401
        const val MODEL_SHA256 = "e413bbd992ed1fbb44d5206abdc040f14da6458ad5ffaa16810079395be68281"
        const val MODEL_BYTES = 54_155_412L
        fun verifyModel(file: File) {
            require(file.isFile && file.length() == MODEL_BYTES) {
                "Place the pinned NemotronLabs file at ${file.absolutePath}; exists=${file.exists()}, bytes=${file.length()}"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash == MODEL_SHA256) { "NemotronLabs model hash mismatch" }
        }
    }
}
