package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Stable within one source utterance; listener count is never part of request identity. */
class TranslationRequestIdentity(val scope: String, val sequence: Long) : AbstractCoroutineContextElement(Key) {
    init { require(scope.isNotBlank() && scope.length <= 160 && sequence >= 0) }
    companion object Key : CoroutineContext.Key<TranslationRequestIdentity>
}
