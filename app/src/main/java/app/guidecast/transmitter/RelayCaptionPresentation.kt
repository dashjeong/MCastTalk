package app.guidecast.transmitter

/** Presentation evidence only; a native session identifies a run, not a microphone utterance. */
internal enum class RelayCaptionAlignment {
    SHARED_UTTERANCE,
    INDEPENDENT_NATIVE,
    NEARBY_NATIVE_UNCONFIRMED,
}

internal data class RelayCaptionGroup(
    val id: String,
    val sourceText: String,
    val sourceLanguageTag: String?,
    val capturedAtElapsedRealtimeNanos: Long,
    val alignment: RelayCaptionAlignment,
    val segments: List<TranslationTranscriptLine>,
    val translations: Map<String, String>,
) {
    val sourceStatusLabel: String get() = segments.map { it.sourceStatusLabel }.distinct()
        .singleOrNull() ?: "언어별 인식 상태 다름"
    val alignmentNotice: String? get() = when (alignment) {
        RelayCaptionAlignment.SHARED_UTTERANCE -> null
        RelayCaptionAlignment.INDEPENDENT_NATIVE -> "언어별 인식 구간 · 발화 정렬 미확인"
        RelayCaptionAlignment.NEARBY_NATIVE_UNCONFIRMED -> "가까운 인식 구간을 함께 표시 · 발화 정렬 미확인"
    }
    fun segmentFor(languageTag: String): TranslationTranscriptLine? =
        segments.firstOrNull { languageTag in it.translations || it.liveSegmentLanguage == languageTag }
}

/**
 * Nearby identical native source captions may share a visual card, never an asserted identity.
 * A repeated language, competing nearby repetition, blank/failed source, or changed run is kept
 * separate. Filtering these groups is display-only and cannot affect requests or audio routes.
 */
internal fun relayCaptionPresentation(
    lines: List<TranslationTranscriptLine>,
    windowMillis: Long = 2_000,
): List<RelayCaptionGroup> {
    require(windowMillis in 0..2_000)
    val windowNanos = windowMillis * 1_000_000
    val ordered = lines.associateBy { Triple(it.nativeAudioSessionId, it.liveSegmentLanguage, it.sequence) }
        .values.sortedWith(compareBy<TranslationTranscriptLine> { it.capturedAtElapsedRealtimeNanos }.thenBy { it.sequence })
    val result = mutableListOf<RelayCaptionGroup>()
    var start = 0
    while (start < ordered.size) {
        val anchor = ordered[start]
        val key = nativeCaptionMatchKey(anchor)
        if (key == null) {
            result += captionGroup(listOf(anchor))
            start++
            continue
        }
        var blockEnd = start + 1
        while (blockEnd < ordered.size && nativeCaptionMatchKey(ordered[blockEnd]) == key) blockEnd++
        val block = ordered.subList(start, blockEnd)
        var cursor = 0
        while (cursor < block.size) {
            val first = block[cursor]
            var end = cursor + 1
            while (end < block.size && block[end].capturedAtElapsedRealtimeNanos -
                first.capturedAtElapsedRealtimeNanos <= windowNanos) end++
            val nearby = block.subList(cursor, end)
            val distinctLanguages = nearby.map { it.liveSegmentLanguage }.distinct().size == nearby.size
            val competingRepetition = nearby.any { row -> block.indices.any { index ->
                index !in cursor until end && block[index].liveSegmentLanguage == row.liveSegmentLanguage &&
                    kotlin.math.abs(block[index].capturedAtElapsedRealtimeNanos - row.capturedAtElapsedRealtimeNanos) <= windowNanos
            } }
            if (nearby.size > 1 && distinctLanguages && !competingRepetition) result += captionGroup(nearby)
            else nearby.forEach { result += captionGroup(listOf(it)) }
            cursor = end
        }
        start = blockEnd
    }
    return result.sortedByDescending { it.capturedAtElapsedRealtimeNanos }
}

private data class NativeCaptionMatchKey(val session: Long, val sourceLanguage: String, val source: String)
private val sourceWhitespace = Regex("\\s+")
private fun nativeCaptionMatchKey(row: TranslationTranscriptLine): NativeCaptionMatchKey? {
    if (row.liveSegmentLanguage == null || row.nativeAudioSessionId == null || row.sourceLanguageTag.isNullOrBlank() ||
        row.sourceText.isBlank() || row.liveSourceFailed || row.liveSourceExpired || row.capturedAtElapsedRealtimeNanos < 0) return null
    return NativeCaptionMatchKey(row.nativeAudioSessionId, row.sourceLanguageTag,
        row.sourceText.trim().replace(sourceWhitespace, " "))
}

private fun captionGroup(segments: List<TranslationTranscriptLine>): RelayCaptionGroup {
    val first = segments.first()
    val native = first.liveSegmentLanguage != null
    return RelayCaptionGroup(
        id = "${first.nativeAudioSessionId ?: "text"}:${first.liveSegmentLanguage ?: "shared"}:${first.sequence}",
        sourceText = first.sourceText,
        sourceLanguageTag = first.sourceLanguageTag,
        capturedAtElapsedRealtimeNanos = first.capturedAtElapsedRealtimeNanos,
        alignment = if (!native) RelayCaptionAlignment.SHARED_UTTERANCE else if (segments.size > 1)
            RelayCaptionAlignment.NEARBY_NATIVE_UNCONFIRMED else RelayCaptionAlignment.INDEPENDENT_NATIVE,
        segments = segments.toList(),
        translations = segments.flatMap { it.translations.entries }.associate { it.key to it.value },
    )
}

internal fun relayCaptionDisplayLanguages(available: List<String>, selectedLanguageTag: String?): List<String> =
    available.distinct().let { languages -> selectedLanguageTag?.takeIf { it in languages }?.let(::listOf) ?: languages }

internal fun relayCaptionLanguageLabel(languageTag: String): String =
    NATIVE_RELAY_TRANSLATION_LANGUAGE_OPTIONS.firstOrNull { it.languageTag == languageTag }?.label?.substringBefore(" · ") ?: languageTag

internal fun relayCaptionDisplayGroups(groups: List<RelayCaptionGroup>, selectedLanguageTag: String?): List<RelayCaptionGroup> =
    if (selectedLanguageTag == null) groups else groups.filter { group ->
        group.alignment == RelayCaptionAlignment.SHARED_UTTERANCE || group.segmentFor(selectedLanguageTag) != null
    }
