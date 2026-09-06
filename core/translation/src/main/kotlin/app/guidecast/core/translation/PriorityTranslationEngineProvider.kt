package app.guidecast.core.translation

/**
 * Routes one operator-priority language through the quality provider while every other language
 * uses the prepared per-language provider directly.
 *
 * This is intentionally a target router, not another inference lock. A single large on-device
 * runtime therefore cannot make the remaining language workers spend their independent realtime
 * deadlines waiting in its queue.
 */
class PriorityTranslationEngineProvider(
    val priorityLanguageTag: String,
    private val priority: TranslationEngineProvider,
    private val perLanguage: TranslationEngineProvider,
) : TranslationEngineProvider {
    init {
        require(LANGUAGE_TAG.matches(priorityLanguageTag)) { "Invalid priority language tag" }
    }

    override fun engineFor(targetLanguageTag: String): TextTranslationEngine {
        require(LANGUAGE_TAG.matches(targetLanguageTag)) { "Invalid target language tag" }
        return if (targetLanguageTag == priorityLanguageTag) {
            priority.engineFor(targetLanguageTag)
        } else {
            perLanguage.engineFor(targetLanguageTag)
        }
    }
}
