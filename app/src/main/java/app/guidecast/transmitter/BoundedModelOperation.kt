package app.guidecast.transmitter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** UI ownership always ends; this does not release native admission tickets. */
internal suspend fun runBoundedModelOperation(
    timeoutMillis: Long,
    busy: (Boolean) -> Unit,
    failure: (String) -> Unit,
    operation: suspend () -> Unit,
) {
    busy(true)
    try {
        withTimeout(timeoutMillis) { operation() }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        failure("모델 확인/준비 응답 시간이 초과됐습니다. 연결 상태를 확인하고 다시 준비하세요.")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        failure(error.message ?: "모델 작업을 완료하지 못했습니다. 다시 준비하세요.")
    } finally {
        busy(false)
    }
}
