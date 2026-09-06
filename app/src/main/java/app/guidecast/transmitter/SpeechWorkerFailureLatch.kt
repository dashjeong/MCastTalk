package app.guidecast.transmitter

import android.os.RemoteException
import java.util.concurrent.ConcurrentHashMap

/** A transport death is not a reason to launch the same crashing native voice on every sentence. */
internal class SpeechWorkerFailureLatch(
    private val describeExit: (String) -> String? = { null },
) {
    private data class Failure(val count: Int, val error: Throwable)
    private val failures = ConcurrentHashMap<String, Failure>()

    fun record(languageTag: String, error: Throwable) {
        val pending = java.util.ArrayDeque<Throwable>().apply { add(error) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        repeat(32) {
            val cause = pending.pollFirst() ?: return
            if (!seen.add(cause)) return@repeat
            if (cause is RemoteException) {
                failures.compute(languageTag) { _, previous ->
                    Failure(minOf(2, (previous?.count ?: 0) + 1), error)
                }
                return
            }
            cause.cause?.let(pending::addLast)
            cause.suppressed.take(8).forEach(pending::addLast)
        }
    }

    fun check(languageTag: String) {
        failures[languageTag]?.takeIf { it.count >= 2 }?.let { failure ->
            throw IllegalStateException(
                "$languageTag 음성 작업자 종료 후 자동 재실행을 보류했습니다. " +
                    "준비된 대체 음성을 사용합니다. 고품질 음성은 설정에서 다시 준비하세요." +
                    (runCatching { describeExit(languageTag) }.getOrNull()?.let { " · $it" } ?: ""), failure.error,
            )
        }
    }

    fun reset(languageTags: Collection<String>) = languageTags.forEach(failures::remove)
    fun completed(languageTag: String) { failures.remove(languageTag) }
    fun clear() = failures.clear()
}
