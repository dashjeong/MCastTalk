package app.guidecast.core.audio

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicitly instantiated only by the research harness. No automatic download or public UI. */
class NativeReuseBackend(model: File) : ReuseFrameBackend {
    private var handle = 0L
    init {
        check(leased.compareAndSet(false, true)) { "A RE-USE session is still closing" }
        try {
            ReuseResearchModel.verify(model)
            System.loadLibrary("mcasttalk_reuse")
            handle = create(model.absolutePath)
            check(handle != 0L) { "RE-USE initialization failed" }
        } catch (error: Throwable) {
            leased.set(false)
            throw error
        }
    }
    @Synchronized override fun process(frame: FloatArray): FloatArray {
        check(handle != 0L)
        require(frame.size == 320 && frame.all { it.isFinite() })
        return processFrame(handle, frame)
    }
    @Synchronized override fun close() {
        if (handle == 0L) return
        try { destroy(handle) } finally { handle = 0L; leased.set(false) }
    }
    private external fun create(path: String): Long
    private external fun processFrame(handle: Long, input: FloatArray): FloatArray
    private external fun destroy(handle: Long)
    companion object { private val leased = AtomicBoolean(false) }
}

object ReuseResearchModel {
    const val REVISION = "4c0c186272ca853cd18d832fc5c28777b62f32d1"
    const val SHA256 = "6eac4e8cc721f99a22cde7eac3c9f8e2826ced5dc8a4234fd1977de1ec0d3ade"
    const val SIZE_BYTES = 54_055_764L
    fun verify(file: File) {
        require(file.isFile && file.length() == SIZE_BYTES) { "Missing or invalid RE-USE research model" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == SHA256) { "RE-USE model SHA-256 mismatch" }
    }
}
