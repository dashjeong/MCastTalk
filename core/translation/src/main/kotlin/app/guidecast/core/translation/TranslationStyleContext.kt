package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class TranslationStyle { AUTO, FORMAL, CONVERSATIONAL }

/** Explicit developer experiment. Absence retains the provider's original prompt. */
class TranslationStyleContext(val style: TranslationStyle) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TranslationStyleContext>
}

/** Fixed instructions shared by local text, cloud text and supported native audio routes. */
fun TranslationStyle.interpretationInstructions(): String = when (this) {
    TranslationStyle.AUTO -> "Match the original situation and register: natural spoken phrasing for dialogue, formal phrasing for announcements."
    TranslationStyle.CONVERSATIONAL -> "Use natural conversational phrasing in the target language, as a fluent native speaker would address this audience. Use context-appropriate politeness and formality, short speakable sentences and idiomatic word order; avoid stiff literal translation."
    TranslationStyle.FORMAL -> "Use a clear formal register and well-structured written sentences in the target language, appropriate to the domain and audience; avoid stiff literal translation."
} + " Preserve every original fact, number, name, negation, condition, technical term, quotation, uncertainty and intent. Do not embellish, infer emotions, invent examples or omit information. Change wording only when meaning is unchanged."
