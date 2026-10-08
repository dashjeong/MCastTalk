package app.guidecast.transmitter

import app.guidecast.core.translation.BoundedQueuedTranslationEngine
import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.DomainTranslationContext
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.TranslationStyleContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal class DomainInputSnapshot(
    val revision: Long,
    val reference: String,
    matches: Map<String, DomainCorpusMatch>,
    exactReferencesIncluded: Set<String>,
) {
    val matches: Map<String, DomainCorpusMatch> = java.util.Collections.unmodifiableMap(matches.toMap())
    val exactReferencesIncluded: Set<String> = java.util.Collections.unmodifiableSet(exactReferencesIncluded.toSet())
}

/**
 * Decorates a [TextTranslationEngine] with local domain corpus exact substitutions
 * and reference hints. Ephemeral domain hints are passed via [DomainTranslationContext]
 * to local Gemma runtimes. A shared input snapshot can also supply bounded references
 * to the online adapter only when its explicit reference-transmission setting is allowed.
 */
class DomainCorpusTranslationEngine(
    private val delegate: TextTranslationEngine,
    private val repository: DomainCorpusRepository,
    private val matchSnapshot: DomainCorpusMatch? = null,
    private val automaticExampleLookup: ((String, String?, String, String, TranslationStyle, Long) -> String?)? = null,
    private val referenceHintBudget: () -> Int = { DomainCorpusFormat.MAX_HINTS_LENGTH },
) : BoundedQueuedTranslationEngine {

    internal fun withAutomaticExamples(lookup: (String, String?, String, String, TranslationStyle, Long) -> String?) =
        DomainCorpusTranslationEngine(delegate, repository, matchSnapshot, lookup, referenceHintBudget)

    internal val capturedRevision: Long? get() = matchSnapshot?.revision
    internal val capturedExactMatch: Boolean get() = matchSnapshot?.exactTranslation != null
    internal val capturedHints: String get() = boundedDomainReferenceHints(matchSnapshot?.hints.orEmpty(), 600)
    internal suspend fun capture(text: String, source: String, target: String, style: TranslationStyle): DomainCorpusTranslationEngine {
        val match = repository.match(text, source, target, style)
        val budget = minOf(referenceHintBudget(), 600)
        return DomainCorpusTranslationEngine(delegate, repository, match) { budget }
    }

    internal suspend fun captureInputSnapshot(text: String, source: String, targets: List<String>, style: TranslationStyle): DomainInputSnapshot {
        val version = repository.revision.value
        val matches = targets.associateWith { repository.match(text, source, it, style) }
        check(repository.revision.value == version && matches.values.all { it.revision == version }) {
            "Domain references changed during input capture"
        }
        val budget = referenceHintBudget().coerceIn(0, 600)
        val includedExact = linkedSetOf<String>()
        val references = JSONArray()
        for ((target, match) in matches) {
            val evidence = if (match.exactTranslation != null)
                JSONObject().put("examples", JSONArray().put(JSONObject()
                    .put("source", text).put("translation", match.exactTranslation)))
            else match.hints.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (evidence == null) continue
            val entry = JSONObject().put("target_language", target).put("reference", evidence)
            val candidate = JSONObject().put("references", JSONArray(references.toString()).put(entry)).toString()
            // Preserve whole evidence objects; never truncate a pair or one target's language label.
            if (candidate.length <= budget) {
                references.put(entry)
                if (match.exactTranslation != null) includedExact += target
            }
        }
        val reference = if (references.length() == 0) "" else JSONObject().put("references", references).toString()
        return DomainInputSnapshot(version, reference, matches, includedExact)
    }

    internal fun fromInputSnapshot(snapshot: DomainInputSnapshot, target: String): DomainCorpusTranslationEngine {
        val match = requireNotNull(snapshot.matches[target])
        // Every local comparison sees the exact same bounded evidence string as the shared API.
        return DomainCorpusTranslationEngine(delegate, repository,
            match.copy(hints = snapshot.reference, revision = snapshot.revision)) { snapshot.reference.length }
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
            val safe = runCatching {
                app.guidecast.core.translation.requireProtectedTranslationMeaning(text, match.exactTranslation,
                    sourceLanguageTag, targetLanguageTag)
            }.isSuccess
            if (safe) return match.exactTranslation
            RuntimeDiagnosticLog.record("domain_corpus", "exact_meaning_rejected")
            val fallback = delegateTranslate(text, contextBefore, sourceLanguageTag, targetLanguageTag)
            app.guidecast.core.translation.requireProtectedTranslationMeaning(text, fallback, sourceLanguageTag, targetLanguageTag)
            return fallback
        }

        // Manually reviewed exact examples above always win. This path has no IO or model call.
        automaticExampleLookup?.invoke(text, contextBefore, sourceLanguageTag, targetLanguageTag,
            requestStyle, match.revision)?.let { saved ->
            val valid = runCatching {
                app.guidecast.core.translation.requireProtectedTranslationMeaning(text, saved, sourceLanguageTag, targetLanguageTag)
            }.isSuccess
            if (valid) return saved
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
