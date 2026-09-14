package app.guidecast.provider.android.stt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A live recognizer stopped consuming its injected PCM; only this attempt needs replacing. */
class RecognitionAudioInputStalledException : IllegalStateException(
    "음성인식기가 입력 PCM을 소비하지 않아 인식 연결을 다시 준비합니다.",
)

/**
 * [tryWrite] must never block and returns zero when the pipe is full. Keeping the syscall
 * nonblocking is essential: a coroutine timeout cannot interrupt FileOutputStream.write().
 * Partial writes preserve every byte in order; a stalled attempt is failed rather than silently
 * deleting audio. The deadline starts again only when the reader actually consumes more bytes.
 */
internal suspend fun writeRecognitionPcm(
    bytes: ByteArray,
    tryWrite: (offset: Int, count: Int) -> Int,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    waitForCapacity: suspend (Long) -> Unit = { delay(it) },
    maximumNoProgressMillis: Long = 3_000L,
) {
    require(bytes.size % Short.SIZE_BYTES == 0) { "Speech recognition requires PCM 16-bit frames" }
    require(maximumNoProgressMillis > 0)
    var offset = 0
    var lastProgress = nowMillis()
    while (offset < bytes.size) {
        currentCoroutineContext().ensureActive()
        val count = tryWrite(offset, bytes.size - offset)
        check(count in 0..(bytes.size - offset)) { "Invalid PCM pipe write length" }
        if (count > 0) {
            offset += count
            lastProgress = nowMillis()
        } else {
            val remaining = maximumNoProgressMillis - (nowMillis() - lastProgress)
            if (remaining <= 0) throw RecognitionAudioInputStalledException()
            waitForCapacity(minOf(10L, remaining))
        }
    }
}
