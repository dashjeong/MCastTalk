package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

/** 이 문장의 음성을 시작할 수 있는 절대 마감. 번역 호출 전에만 참고한다. */
class TranslationSpeechDeadlineContext(val deadlineElapsedRealtimeNanos: Long) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranslationSpeechDeadlineContext>
}

class SpeechDeadlineAwareTranslationEngineProvider(
    private val primary: TranslationEngineProvider,
    private val fast: TranslationEngineProvider,
    private val fastAvailable: (String) -> Boolean,
    private val nowNanos: () -> Long = System::nanoTime,
    private val reserveMillis: Long = 1_000L,
    private val onFastUsed: (String) -> Unit = {},
) : TranslationEngineProvider {
    // 같은 주 엔진을 쓰는 모든 언어와 새로 만든 엔진 사이에서 측정값을 공유한다.
    private val timingLock = Any()
    private var averagePrimaryMillis = INITIAL_PRIMARY_MILLIS
    // 주 엔진은 한 번에 하나만 돈다. 이미 맡긴 번역이 있으면 그만큼 차례를 더 기다린다
    // (S21 실측: 3개 언어가 함께 맡기면 번역 한 번이 2.7초가 아니라 8~9초 걸려 마감을 넘겼다).
    private var primaryInFlight = 0

    init {
        require(reserveMillis >= 0L) { "음성 준비 여유는 음수일 수 없습니다" }
    }

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        val primaryEngine = primary.engineFor(targetLanguageTag)
        return object : BoundedQueuedTranslationEngine {
            override val maximumCallDurationMillis: Long
                get() = (primaryEngine as? BoundedQueuedTranslationEngine)?.maximumCallDurationMillis ?: 4_000L

            override suspend fun translateWithContext(
                text: String,
                contextBefore: String?,
                sourceLanguageTag: String,
                targetLanguageTag: String,
            ): String {
                val deadline = currentCoroutineContext()[TranslationSpeechDeadlineContext]
                val predictedMillis = synchronized(timingLock) { (primaryInFlight + 1) * averagePrimaryMillis * 1.2 }
                if (deadline != null && fastAvailable(targetLanguageTag) &&
                    (deadline.deadlineElapsedRealtimeNanos - nowNanos()).toDouble() / 1_000_000.0 -
                    reserveMillis < predictedMillis
                ) {
                    val result = try {
                        fast.engineFor(targetLanguageTag).translatePreservingContext(
                            text, contextBefore, sourceLanguageTag, targetLanguageTag,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (fatal: Error) {
                        throw fatal
                    } catch (_: Throwable) {
                        null
                    }
                    if (result != null) {
                        // 주 번역을 부르지 않으면 평균이 갱신되지 않는다. 한 번 밀렸다고 계속 기본 번역에
                        // 머물지 않도록, 대신 낼 때마다 평균을 처음 값 쪽으로 되돌려 주 번역을 다시 시도하게 한다.
                        synchronized(timingLock) {
                            averagePrimaryMillis = averagePrimaryMillis * 0.75 + INITIAL_PRIMARY_MILLIS * 0.25
                        }
                        onFastUsed(targetLanguageTag)
                        return result
                    }
                }

                // 차례 대기를 포함한 호출 시간을 재고, 앞에 있던 번역 수로 나눠 한 번의 소요로 환산한다.
                // 시작한 주 번역은 취소하지 않는다.
                val ahead = synchronized(timingLock) { primaryInFlight++ }
                val startedNanos = nowNanos()
                try {
                    val result = primaryEngine.translatePreservingContext(
                        text, contextBefore, sourceLanguageTag, targetLanguageTag,
                    )
                    val elapsedMillis = (nowNanos() - startedNanos).coerceAtLeast(0L) / 1_000_000.0
                    synchronized(timingLock) {
                        averagePrimaryMillis = averagePrimaryMillis * 0.75 + elapsedMillis / (ahead + 1) * 0.25
                    }
                    return result
                } finally {
                    synchronized(timingLock) { primaryInFlight-- }
                }
            }
        }
    }
}

private const val INITIAL_PRIMARY_MILLIS = 3_000.0

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
