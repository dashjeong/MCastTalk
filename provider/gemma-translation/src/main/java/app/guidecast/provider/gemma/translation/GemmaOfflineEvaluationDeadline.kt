package app.guidecast.provider.gemma.translation

import kotlinx.coroutines.withTimeout

/** Includes model admission and generation queue time, not just Binder inference time. */
const val GEMMA_OFFLINE_EVALUATION_TIMEOUT_MILLIS = 60_000L

internal suspend fun <T> withGemmaOfflineEvaluationDeadline(block: suspend () -> T): T =
    withTimeout(GEMMA_OFFLINE_EVALUATION_TIMEOUT_MILLIS) { block() }
