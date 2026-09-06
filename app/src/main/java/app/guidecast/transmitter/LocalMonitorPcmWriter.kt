package app.guidecast.transmitter

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** One frame, no rewind. Control changes discard only this monitor's unwritten tail. */
internal suspend fun writeLocalMonitorPcm(
    bytes: ByteArray,
    isCurrent: () -> Boolean,
    writeNonBlocking: (ByteArray, Int, Int) -> Int,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
): Boolean {
    require(bytes.size % 2 == 0) { "로컬 모니터 PCM S16LE 프레임 길이가 올바르지 않습니다." }
    var offset = 0
    var lastProgress = nowMillis()
    while (offset < bytes.size) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return false
        val written = writeNonBlocking(bytes, offset, bytes.size - offset)
        check(written in 0..(bytes.size - offset) && written % 2 == 0) {
            "방송 음성을 로컬 출력으로 재생하지 못했습니다. (AudioTrack $written)"
        }
        if (written == 0) {
            check(nowMillis() - lastProgress < 2_000L) {
                "출력 장치가 응답하지 않아 로컬 모니터만 중지했습니다. 방송은 계속됩니다."
            }
            delay(5)
        } else {
            offset += written
            lastProgress = nowMillis()
        }
    }
    return isCurrent()
}
