package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class TranslationStyle { FORMAL, CONVERSATIONAL }

/** Explicit developer experiment. Absence retains the provider's original prompt. */
class TranslationStyleContext(val style: TranslationStyle) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranslationStyleContext>
}
