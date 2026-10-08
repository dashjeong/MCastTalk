package app.guidecast.core.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun boundedGlossaryLookup(
    text: String,
    source: String,
    target: String,
    lookup: suspend (String, String, String) -> List<GlossaryTerm>,
    onWarning: (String) -> Unit,
    timeoutMillis: Long = 100L,
): List<GlossaryTerm> {
    val result = try {
        withTimeoutOrNull(timeoutMillis) { lookup(text, source, target) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        null
    }
    if (result == null) onWarning("용어 사전 조회를 건너뛰고 일반 번역을 계속합니다.")
    return result.orEmpty()
}
