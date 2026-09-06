package app.guidecast.core.translation

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uses the primary on-device translator until it fails, then keeps every engine routed through
 * this instance on a prepared offline fallback. Multilingual callers with one large primary
 * runtime must route only their priority target here via [PriorityTranslationEngineProvider]; the
 * other targets should use their prepared per-language engines directly.
 */
class FailoverTranslationEngineProvider(
    private val primary: TranslationEngineProvider,
    private val fallback: TranslationEngineProvider,
    private val primaryAttemptTimeoutMillis: Long? = null,
    private val primaryRetryCooldownMillis: Long? = null,
    // Cooldowns are elapsed durations. A wall-clock correction must never postpone or accelerate
    // Gemma recovery while a tour is in progress.
    private val currentMonotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onPrimaryFailure: suspend (Throwable) -> Unit = {},
    private val onPrimaryRecovered: suspend () -> Unit = {},
) : TranslationEngineProvider {
    private val nextPrimaryAttemptAtMillis = AtomicLong(0L)
    private val switchMutex = Mutex()

    init {
        require(primaryAttemptTimeoutMillis == null || primaryAttemptTimeoutMillis > 0) {
            "Primary translation timeout must be positive"
        }
        require(primaryRetryCooldownMillis == null || primaryRetryCooldownMillis > 0) {
            "Primary retry cooldown must be positive"
        }
    }

    val isUsingFallback: Boolean
        get() = nextPrimaryAttemptAtMillis.get().let { retryAt ->
            retryAt == PERMANENTLY_DISABLED || retryAt > currentMonotonicMillis()
        }

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        val primaryEngine = primary.engineFor(targetLanguageTag)
        val fallbackEngine = fallback.engineFor(targetLanguageTag)
        return object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                val primaryResult = switchMutex.withLock {
                    val retryAt = nextPrimaryAttemptAtMillis.get()
                    if (retryAt == PERMANENTLY_DISABLED || retryAt > currentMonotonicMillis()) {
                        null
                    } else {
                        try {
                            val attempt = if (primaryAttemptTimeoutMillis == null) {
                                CompletedPrimaryTranslation(
                                    primaryEngine.translatePreservingContext(
                                        text = text,
                                        contextBefore = contextBefore,
                                        sourceLanguageTag = sourceLanguageTag,
                                        targetLanguageTag = targetLanguageTag,
                                    ),
                                )
                            } else {
                                withTimeoutOrNull(primaryAttemptTimeoutMillis) {
                                    CompletedPrimaryTranslation(
                                        primaryEngine.translatePreservingContext(
                                            text = text,
                                            contextBefore = contextBefore,
                                            sourceLanguageTag = sourceLanguageTag,
                                            targetLanguageTag = targetLanguageTag,
                                        ),
                                    )
                                } ?: throw PrimaryTranslationAttemptTimeoutException(
                                    primaryAttemptTimeoutMillis,
                                )
                            }
                            val wasRecovering = retryAt > 0L
                            nextPrimaryAttemptAtMillis.set(0L)
                            if (wasRecovering) onPrimaryRecovered()
                            attempt.text
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (fatal: Error) {
                            throw fatal
                        } catch (error: Throwable) {
                            nextPrimaryAttemptAtMillis.set(
                                primaryRetryCooldownMillis?.let { cooldown ->
                                    (currentMonotonicMillis() + cooldown).coerceAtLeast(1L)
                                } ?: PERMANENTLY_DISABLED,
                            )
                            onPrimaryFailure(error)
                            null
                        }
                    }
                }
                return primaryResult ?: fallbackEngine.translatePreservingContext(
                    text = text,
                    contextBefore = contextBefore,
                    sourceLanguageTag = sourceLanguageTag,
                    targetLanguageTag = targetLanguageTag,
                )
            }
        }
    }

    private companion object {
        const val PERMANENTLY_DISABLED = Long.MAX_VALUE
    }
}

private data class CompletedPrimaryTranslation(val text: String)

private class PrimaryTranslationAttemptTimeoutException(timeoutMillis: Long) :
    IllegalStateException(
        "우선 번역 엔진이 ${timeoutMillis}ms 안에 응답하지 않아 준비된 대체 번역으로 전환합니다.",
    )

private suspend fun TextTranslationEngine.translatePreservingContext(
    text: String,
    contextBefore: String?,
    sourceLanguageTag: String,
    targetLanguageTag: String,
): String = if (this is ContextualTextTranslationEngine) {
    translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
} else {
    translate(text, sourceLanguageTag, targetLanguageTag)
}
