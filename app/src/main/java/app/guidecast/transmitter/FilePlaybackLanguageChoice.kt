package app.guidecast.transmitter

internal data class FilePlaybackLanguageChoice(val languageTag: String, val label: String,
    val actionLabel: String)

/** Display stored keys verbatim; choosing a language must not invent another regional target. */
internal fun filePlaybackLanguageChoices(entry: FileLibraryEntry,
    options: Map<String, String>): List<FilePlaybackLanguageChoice> {
    val tags = (entry.translations.keys + options.keys).distinct()
    val names = tags.associateWith { fileLanguageLabel(it, options) }
    val counts = names.values.groupingBy { it }.eachCount()
    return tags.map { tag ->
        val name = names.getValue(tag)
        val label = if (counts.getValue(name) > 1) "$name · $tag" else name
        val saved = entry.translations[tag]
        val sameAsSource = entry.segments.isNotEmpty() && entry.segments.all {
            fileSegmentMatchesTarget(it, entry.sourceLanguageTag, tag)
        }
        val complete = saved != null && saved.size == entry.segments.size && saved.all(String::isNotBlank)
        val action = when {
            sameAsSource -> "원문 보기"
            complete -> "보기"
            saved != null -> "번역 이어서"
            else -> "번역 추가"
        }
        FilePlaybackLanguageChoice(tag, label, action)
    }
}
