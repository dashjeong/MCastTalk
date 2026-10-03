package app.guidecast.provider.gemma.translation

import app.guidecast.core.translation.TranslationStyle
import app.guidecast.core.translation.interpretationInstructions

/** Only fixed enum tokens cross IPC; user/provider text cannot become style instructions. */
internal object GemmaTranslationStylePrompt {
    fun apply(prompt: String, style: String): String = if (style.isEmpty()) prompt else
        TranslationStyle.valueOf(style).interpretationInstructions() +
            " Use provided context only to resolve ambiguity. Translate only the current utterance.\n\n" + prompt
}
