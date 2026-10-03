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
    private val matchSnapshot: DomainCorpusMatch? = null,
    private val referenceHintBudget: () -> Int = { DomainCorpusFormat.MAX_HINTS_LENGTH },
) : BoundedQueuedTranslationEngine {

    internal val capturedRevision: Long? get() = matchSnapshot?.revision
    internal val capturedHints: String get() = boundedDomainReferenceHints(matchSnapshot?.hints.orEmpty(), 600)
    internal suspend fun capture(text: String, source: String, target: String, style: TranslationStyle): DomainCorpusTranslationEngine {
        val match = repository.match(text, source, target, style)
        val budget = minOf(referenceHintBudget(), 600)
        return DomainCorpusTranslationEngine(delegate, repository, match) { budget }
    }

    internal suspend fun captureBatchReference(text: String, source: String, targets: List<String>, style: TranslationStyle): Pair<Long, String> {
        val version = repository.revision.value
        val matches = targets.associateWith { repository.match(text, source, it, style) }
        check(repository.revision.value == version && matches.values.all { it.revision == version }) {
            "Domain references changed during input capture"
        }
        val hints = matches.entries.filter { it.value.hints.isNotBlank() }
            .joinToString("\n") { "${it.key}: ${it.value.hints}" }
        return version to boundedDomainReferenceHints(hints, 600)
    }

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
            matchSnapshot ?: repository.match(text, sourceLanguageTag, targetLanguageTag, requestStyle)
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
        val referenceHints = boundedDomainReferenceHints(match.hints, referenceHintBudget())
        val translated = if (referenceHints.isNotBlank()) {
            withContext(DomainTranslationContext(referenceHints, match.revision)) {
                delegateTranslate(text, contextBefore, sourceLanguageTag, targetLanguageTag)
            }
        } else {
            delegateTranslate(text, contextBefore, sourceLanguageTag, targetLanguageTag)
        }
        val repaired = if (referenceHints.isNotBlank())
            TextFidelityGuard.repair(text, translated, sourceLanguageTag, targetLanguageTag) else translated
        app.guidecast.core.translation.requireProtectedTranslationMeaning(text, repaired, sourceLanguageTag, targetLanguageTag)
        return repaired
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
