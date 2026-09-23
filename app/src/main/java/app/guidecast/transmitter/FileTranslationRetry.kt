package app.guidecast.transmitter

/** An explicit conversion uses current lab/dictionary settings; retries preserve successful targets. */
internal fun shouldTranslateFileTarget(entry: FileLibraryEntry, target: String,
    engine: FileTranslationEngine, retryOnly: Boolean): Boolean = !retryOnly ||
    (sourceOnlyFileTranslation(entry, target) == null &&
    (target !in entry.translations || entry.translationModes[target] != engine ||
    entry.translations[target]?.any(String::isBlank) == true ||
    entry.qualityNotes.any { it.startsWith("$target 번역을 완료하지 못했습니다.") }))
