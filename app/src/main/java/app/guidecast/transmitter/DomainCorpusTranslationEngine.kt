package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.DomainTranslationContext
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Decorates a [TextTranslationEngine] with local domain corpus exact substitutions
 * and reference hints. Ephemeral domain hints are passed via [DomainTranslationContext]
 * only to local Gemma runtimes, never transmitted to external APIs.
 */
class DomainCorpusTranslationEngine(
    private val delegate: TextTranslationEngine,
    private val repository: DomainCorpusRepository,
) : BoundedQueuedTranslationEngine {

    override val maximumCallDurationMillis: Long
        get() {
            val base = (delegate as? BoundedQueuedTranslationEngine)?.maximumCallDurationMillis ?: 4_000L
            return base + 200L
        }

    override suspend fun translateWithContext(
        text: String,
        contextBefore: String?,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String {
        val requestStyle = currentCoroutineContext()[TranslationStyleContext]?.style ?: TranslationStyle.AUTO
        val match = try {
            repository.match(text, sourceLanguageTag, targetLanguageTag, requestStyle)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // Fail-open: recoverable corpus errors must not block translation. Never mask fatal VM errors.
            RuntimeDiagnosticLog.record("domain_corpus", "match_error:${error.javaClass.simpleName}")
            DomainCorpusMatch(null, "")
        }

        // Exact substitution only active matching domain/language+style and NFC+trim exact whole source
        if (match.exactTranslation != null) {
            return match.exactTranslation
        }

        // Ephemeral domain hints passed only when present
        return if (match.hints.isNotBlank()) {
            val translated = withContext(DomainTranslationContext(match.hints)) {
                delegateTranslate(text, contextBefore, sourceLanguageTag, targetLanguageTag)
            }
            TextFidelityGuard.repair(text, translated, sourceLanguageTag, targetLanguageTag)
        } else {
            delegateTranslate(text, contextBefore, sourceLanguageTag, targetLanguageTag)
        }
    }

    private suspend fun delegateTranslate(
        text: String,
        contextBefore: String?,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String = if (delegate is ContextualTextTranslationEngine) {
        delegate.translateWithContext(text, contextBefore, sourceLanguageTag, targetLanguageTag)
    } else {
        delegate.translate(text, sourceLanguageTag, targetLanguageTag)
    }

    override suspend fun translate(
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String = translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
}
