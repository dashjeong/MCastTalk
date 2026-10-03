package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Ephemeral domain corpus hints passed to local LLM context. */
class DomainTranslationContext(val hints: String, val revision: Long = 0) : AbstractCoroutineContextElement(Key) {
    init {
        require(hints.length <= 1200) { "Domain hints must not exceed 1200 characters" }
    }
    companion object Key : CoroutineContext.Key<DomainTranslationContext>
}
