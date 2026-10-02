package app.guidecast.provider.gemma.translation

/** The same frozen observable contracts also guard model-generated fallback results. */
internal fun requireGemmaProtectedMeaning(source: String, output: String, sourceTag: String, targetTag: String) =
    app.guidecast.core.translation.requireProtectedTranslationMeaning(source, output, sourceTag, targetTag)
