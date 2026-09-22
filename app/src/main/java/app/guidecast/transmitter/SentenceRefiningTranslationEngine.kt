package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.TextTranslationEngine

/** Keeps native deadlines/context; network review never delays live speech. */
internal class SentenceRefiningTranslationEngine(
    private val delegate: TextTranslationEngine,
    private val reviewer: CloudTranslationReviewer,
) : BoundedQueuedTranslationEngine {
    override val maximumCallDurationMillis: Long
        get() = ((delegate as? BoundedQueuedTranslationEngine)?.maximumCallDurationMillis ?: 4_000L) + 200L

    override suspend fun translateWithContext(text: String, contextBefore: String?,
        sourceLanguageTag: String, targetLanguageTag: String): String {
        val draft = if (delegate is ContextualTextTranslationEngine)
            delegate.translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
        else delegate.translate(text, sourceLanguageTag, targetLanguageTag)
        return reviewer.refine(sourceLanguageTag, targetLanguageTag, text, draft, live = true, contextBefore = contextBefore)
    }
}
