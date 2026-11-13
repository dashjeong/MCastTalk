package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.withOfflineInterpreterOutputGuard

/** Keeps native deadlines/context; network review never delays live speech. */
internal class SentenceRefiningTranslationEngine(
    private val delegate: TextTranslationEngine,
    private val reviewer: CloudTranslationReviewer,
    private val allowLocalRefinement: () -> Boolean = { true },
) : BoundedQueuedTranslationEngine {
    override val maximumCallDurationMillis: Long
        get() = (((delegate as? BoundedQueuedTranslationEngine)?.maximumCallDurationMillis ?: 4_000L) *
            (if (allowLocalRefinement()) 2 else 1) + 200L).coerceAtMost(90_000L)

    override suspend fun translateWithContext(text: String, contextBefore: String?,
        sourceLanguageTag: String, targetLanguageTag: String): String {
        suspend fun translate(context: String?) = if (delegate is ContextualTextTranslationEngine)
            delegate.translateWithContext(text, context, sourceLanguageTag, targetLanguageTag)
        else delegate.translate(text, sourceLanguageTag, targetLanguageTag)
        if (!allowLocalRefinement()) return translate(contextBefore)
        return withOfflineInterpreterOutputGuard(text, contextBefore, sourceLanguageTag, targetLanguageTag,
            onRejected = { strict -> RuntimeDiagnosticLog.record("interpreter_guard",
                "route=refinement reason=repeated_context strict=$strict", true) }) { safeContext, strict ->
            val draft = translate(safeContext)
            // 반복된 보완 결과는 다시 적용하지 않고 맥락 없는 새 초안을 검사한다.
            if (strict) draft else reviewer.refine(sourceLanguageTag, targetLanguageTag, text, draft,
                live = true, contextBefore = safeContext)
        }
    }
}
