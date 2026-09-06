package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Immutable per-utterance hints survive provider failover without replacing speech context. */
class TranslationGlossaryContext(val hints: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranslationGlossaryContext>
}
