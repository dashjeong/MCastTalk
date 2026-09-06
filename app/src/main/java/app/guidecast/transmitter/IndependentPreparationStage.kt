package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** One stage failing never cancels sibling stages; operator cancellation always propagates. */
internal suspend fun <T> runIndependentPreparationStage(
    label: String,
    timeoutMillis: Long,
    report: (String, String) -> Unit,
    operation: suspend () -> T,
): Result<T> {
    report(label, "준비 중")
    return try {
        val value = withTimeout(timeoutMillis) { operation() }
        report(label, "완료")
        Result.success(value)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) {
            throw error
        }
        val reason = if (error is kotlinx.coroutines.TimeoutCancellationException) {
            "시간 초과 · 연결 상태를 확인한 뒤 다시 준비하세요."
        } else error.message ?: error.javaClass.simpleName
        report(label, "확인 필요 · $reason")
        Result.failure(error)
    }
}
