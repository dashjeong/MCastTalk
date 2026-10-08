package app.guidecast.transmitter

internal data class RecordedHistoryCaptionRow(
    val publicationSequence: Long,
    val stored: RecordedCaption,
    val translations: Map<String, String>,
    val liveSegmentLanguage: String?,
    val liveOutputState: String?,
    val displayGroupSequence: Long?,
)

/** Preserve distinct stored rows; transport keys and presentation hints are not audio timestamps. */
internal fun recordedHistoryCaptionProjection(
    rows: List<RecordedCaption>,
    allowedLanguageTags: Set<String>,
    allowNativeGroups: Boolean = false,
): List<RecordedHistoryCaptionRow> {
    val page = rows.take(100)
    val positions = page.mapIndexed { index, row -> (row.part to row.sequence) to index.toLong() }.toMap()
    val groups = if (allowNativeGroups && rows.size < 100 && positions.size == page.size) recordedNativeDisplayGroups(page) else emptyMap()
    return page.mapIndexed { index, row ->
        val native = row.nativePresentationLine()
        val visible = row.translations.filterKeys { tag -> allowedLanguageTags.any { it.equals(tag, ignoreCase = true) } }
        val laneVisible = native?.liveSegmentLanguage?.let { lane ->
            allowedLanguageTags.any { it.equals(lane, ignoreCase = true) }
        } == true
        RecordedHistoryCaptionRow(index.toLong(), row, visible, native?.liveSegmentLanguage,
            if (laneVisible) native?.liveOutputState?.name else null,
            groups[row.part to row.sequence]?.let { anchor -> positions[row.part to anchor] })
    }
}
