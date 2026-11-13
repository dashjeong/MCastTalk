package app.guidecast.core.audio

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** A session backend: 16 kHz mono, 320 samples in; empty first result, then 320 samples out. */
interface ReuseFrameBackend : Closeable {
    fun process(frame: FloatArray): FloatArray
}

/**
 * Never runs inference on the capture thread. Original frames remain owned here until their
 * delayed enhanced counterparts arrive. A failed session drains originals once and never retries.
 * The deadline is checked on each capture read; callers must continue pumping live input.
 */
class ReuseMicrophoneProcessor(
    private val createBackend: () -> ReuseFrameBackend,
    private val onStatus: (String) -> Unit = {},
    private val executor: ExecutorService = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "mcasttalk-reuse").apply { isDaemon = true } },
    ),
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    private data class Original(val bytes: ByteArray, val queuedAt: Long)
    private val originals = ArrayDeque<Original>() // capture thread only, at most four frames
    private val results = ConcurrentLinkedQueue<ByteArray>() // bounded by outstanding originals
    private val ready = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val failure = AtomicReference<String?>(null)
    private var backend: ReuseFrameBackend? = null // worker thread only
    private val partial = ByteArray(FRAME_BYTES)
    private var filled = 0
    private var active = false
    private var bypass = false
    private var closed = false
    private var step = 0 // worker thread only
    private var slowFrames = 0

    init {
        onStatus("RE-USE 준비 중 · 준비될 때까지 원음 전달")
        executor.execute {
            try {
                if (!ended.get()) backend = createBackend()
                if (!ended.get()) ready.set(true)
            } catch (error: Exception) {
                failure.set("모델 준비 실패")
            } catch (error: LinkageError) {
                failure.set("이 패키지·기기에서 엔진 사용 불가")
            } finally {
                if (ended.get() || failure.get() != null) releaseBackend()
            }
        }
    }

    @Synchronized fun pendingInputBytes(): Int = originals.size * FRAME_BYTES + filled

    @Synchronized fun process(input: ByteArray): ByteArray {
        check(!closed)
        if (bypass) return input
        val output = ByteArrayOutputStream(input.size + pendingInputBytes())
        failure.get()?.let { fallBack(it, output) }
        if (!bypass && originals.firstOrNull()?.let { nanoTime() - it.queuedAt >= DEADLINE_NS } == true) {
            fallBack("처리 지연", output)
        }
        if (bypass) { output.write(input); return output.toByteArray() }
        if (!active) {
            if (!ready.get()) return input
            active = true
            onStatus("RE-USE 연구용 정제 활성 · 오프라인 · 지연 시 원음 복귀")
        }
        while (true) {
            val result = results.poll() ?: break
            check(originals.isNotEmpty()) { "RE-USE returned an unowned frame" }
            originals.removeFirst()
            output.write(result)
        }
        for (index in input.indices) {
            partial[filled++] = input[index]
            if (filled != FRAME_BYTES) continue
            if (originals.size == MAX_PENDING_FRAMES) {
                fallBack("처리 대기 한도 초과", output)
                output.write(input, index + 1, input.size - index - 1)
                break
            }
            val raw = partial.copyOf()
            filled = 0
            originals.addLast(Original(raw, nanoTime()))
            executor.execute { enhance(raw) }
        }
        return output.toByteArray()
    }

    private fun enhance(raw: ByteArray) {
        try {
            if (ended.get() || failure.get() != null) return
            val input = FloatArray(FRAME_SAMPLES) { i ->
                (((raw[i * 2 + 1].toInt() shl 8) or (raw[i * 2].toInt() and 255)).toShort()) / 32768f
            }
            val started = nanoTime()
            val enhanced = checkNotNull(backend).process(input)
            val elapsed = nanoTime() - started
            slowFrames = if (elapsed > FRAME_NS) slowFrames + 1 else 0
            check(enhanced.size == if (step++ == 0) 0 else FRAME_SAMPLES) { "Unexpected RE-USE output shape" }
            check(enhanced.all { it.isFinite() }) { "Nonfinite RE-USE output" }
            if (elapsed >= DEADLINE_NS || slowFrames >= 3) {
                failure.compareAndSet(null, "기기 처리 속도 부족")
                return
            }
            if (enhanced.isNotEmpty() && !ended.get()) {
                val pcm = ByteArray(FRAME_BYTES)
                enhanced.forEachIndexed { i, value ->
                    val sample = (value.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767)
                    pcm[i * 2] = sample.toByte()
                    pcm[i * 2 + 1] = (sample shr 8).toByte()
                }
                results.add(pcm)
            }
        } catch (error: Exception) {
            failure.compareAndSet(null, "정제 처리 실패")
        } catch (error: LinkageError) {
            failure.compareAndSet(null, "엔진 실행 실패")
        } finally {
            if (ended.get() || failure.get() != null) releaseBackend()
        }
    }

    private fun fallBack(reason: String, output: ByteArrayOutputStream) {
        if (bypass) return
        bypass = true
        ended.set(true)
        while (originals.isNotEmpty()) output.write(originals.removeFirst().bytes)
        output.write(partial, 0, filled)
        filled = 0
        results.clear()
        executor.execute { releaseBackend(); results.clear() }
        onStatus("RE-USE $reason · 이 입력 세션은 원음으로 계속합니다. 중지 후 다시 선택할 수 있습니다.")
    }

    private fun releaseBackend() {
        val owned = backend
        backend = null
        runCatching { owned?.close() }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        ended.set(true)
        originals.clear()
        filled = 0
        results.clear()
        executor.execute { releaseBackend(); results.clear() }
        executor.shutdown() // Native calls finish before release; never destroy an in-flight handle.
    }

    companion object {
        private const val FRAME_SAMPLES = 320
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val MAX_PENDING_FRAMES = 4
        private const val FRAME_NS = 20_000_000L
        private const val DEADLINE_NS = 80_000_000L
    }
}
