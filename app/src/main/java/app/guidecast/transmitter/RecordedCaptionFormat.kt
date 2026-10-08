package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject

internal data class RecordedCaption(val part: Long, val sequence: Long, val original: String,
    val translations: Map<String, String>, val final: Boolean, val monotonicNanos: Long,
    val alignment: String, val outputState: String?, val endReason: String?,
    val nativeMetadataVersion: Int? = null, val nativeAudioSessionId: Long? = null,
    val liveSegmentLanguage: String? = null, val sourceLanguageTag: String? = null,
    val liveSourceFinal: Boolean? = null, val liveSourceFailed: Boolean = false,
    val liveSourceExpired: Boolean = false)

internal fun recordedCaptionStorageJson(part: Long, line: TranslationTranscriptLine): JSONObject =
    recordedCaptionStorageJson(RecordedCaption(part, line.sequence, line.sourceText, line.translations,
        line.isFinal, line.capturedAtElapsedRealtimeNanos, line.recordingAlignment.name,
        line.liveOutputState?.name, line.liveEndReason?.name,
        nativeMetadataVersion = if (line.nativeAudioSessionId != null && line.liveSegmentLanguage != null &&
            line.sourceLanguageTag != null) 1 else null,
        nativeAudioSessionId = line.nativeAudioSessionId, liveSegmentLanguage = line.liveSegmentLanguage,
        sourceLanguageTag = line.sourceLanguageTag, liveSourceFinal = line.liveSourceFinal,
        liveSourceFailed = line.liveSourceFailed, liveSourceExpired = line.liveSourceExpired))

internal fun recordedCaptionStorageJson(row: RecordedCaption): JSONObject = JSONObject()
    .put("part", row.part).put("sequence", row.sequence).put("original", row.original)
    .put("translations", JSONObject(row.translations)).put("final", row.final)
    .put("monotonicNanos", row.monotonicNanos).put("alignment", row.alignment)
    .put("outputState", row.outputState ?: JSONObject.NULL).put("endReason", row.endReason ?: JSONObject.NULL)
    .put("nativeMetadataVersion", row.nativeMetadataVersion ?: JSONObject.NULL)
    .put("nativeAudioSessionId", row.nativeAudioSessionId ?: JSONObject.NULL)
    .put("liveSegmentLanguage", row.liveSegmentLanguage ?: JSONObject.NULL)
    .put("sourceLanguageTag", row.sourceLanguageTag ?: JSONObject.NULL)
    .put("liveSourceFinal", row.liveSourceFinal ?: JSONObject.NULL)
    .put("liveSourceFailed", row.liveSourceFailed).put("liveSourceExpired", row.liveSourceExpired)

internal fun readRecordedCaption(row: JSONObject): RecordedCaption {
    val targets = row.getJSONObject("translations")
    return RecordedCaption(row.getLong("part"), row.getLong("sequence"), row.getString("original"),
        targets.keys().asSequence().associateWith { targets.getString(it) }, row.getBoolean("final"), row.getLong("monotonicNanos"),
        // Historical native rows already have an output state, but no portable run/lane metadata.
        if (!row.isNull("outputState") && row.has("outputState")) "NATIVE_PAIR_UNCONFIRMED" else row.getString("alignment"),
        row.optionalCaptionString("outputState"), row.optionalCaptionString("endReason"),
        row.optionalCaptionLong("nativeMetadataVersion")?.takeIf { it == 1L }?.toInt(),
        row.optionalCaptionLong("nativeAudioSessionId"), row.optionalCaptionString("liveSegmentLanguage"),
        row.optionalCaptionString("sourceLanguageTag"), row.opt("liveSourceFinal") as? Boolean,
        row.opt("liveSourceFailed") as? Boolean ?: false, row.opt("liveSourceExpired") as? Boolean ?: false)
}

private fun JSONObject.optionalCaptionString(name: String): String? =
    (opt(name) as? String)?.takeUnless { it.isBlank() || it == "null" }

private fun JSONObject.optionalCaptionLong(name: String): Long? =
    (opt(name) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

/** A stored run is not a shared utterance ID; this restores evidence for conservative presentation. */
internal fun RecordedCaption.nativePresentationLine(): TranslationTranscriptLine? {
    if (nativeMetadataVersion != 1 || alignment != "NATIVE_PAIR_UNCONFIRMED" ||
        nativeAudioSessionId == null || nativeAudioSessionId < 0 || liveSegmentLanguage.isNullOrBlank() || sourceLanguageTag.isNullOrBlank()) return null
    return TranslationTranscriptLine(sequence, original, monotonicNanos, final, translations,
        sourceLanguageTag = sourceLanguageTag, liveSegmentLanguage = liveSegmentLanguage,
        nativeAudioSessionId = nativeAudioSessionId, liveSourceFinal = liveSourceFinal,
        liveSourceFailed = liveSourceFailed, liveSourceExpired = liveSourceExpired,
        liveOutputState = LiveOutputState.entries.firstOrNull { it.name == outputState },
        liveEndReason = NativeAudioEndReason.entries.firstOrNull { it.name == endReason })
}

/** One source row may share a visual card only under the existing uncertain-alignment policy. */
internal fun recordedNativeDisplayGroups(rows: List<RecordedCaption>): Map<Pair<Long, Long>, Long> =
    rows.groupBy { it.part }.flatMap { (part, members) ->
        relayCaptionPresentation(members.map { it.nativePresentationLine() ?:
            TranslationTranscriptLine(it.sequence, it.original, it.monotonicNanos, it.final, it.translations) })
            .filter { it.alignment == RelayCaptionAlignment.NEARBY_NATIVE_UNCONFIRMED }
            .flatMap { group -> group.segments.map { (part to it.sequence) to group.segments.first().sequence } }
    }.toMap()

internal fun recordedReplayCaptionJson(rows: List<RecordedCaption>, allowedLanguageTags: Set<String>? = null,
    allowNativeGroups: Boolean = false): String {
    // A cursor page cannot establish whether matching/competing segments exist just outside it.
    val groups = if (allowNativeGroups && rows.size < 100) recordedNativeDisplayGroups(rows) else emptyMap()
    return JSONArray(rows.map { row ->
        val visible = row.translations.filterKeys { allowedLanguageTags == null || it in allowedLanguageTags }
        val statusVisible = allowedLanguageTags == null ||
            (row.liveSegmentLanguage?.let { it in allowedLanguageTags } ?: (row.translations.isEmpty() || visible.isNotEmpty()))
        JSONObject().put("part", row.part).put("sequence", row.sequence).put("sourceText", row.original)
            .put("isFinal", row.final).put("translations", JSONObject(visible))
            .put("capturedAtElapsedRealtimeNanos", row.monotonicNanos).put("alignment", row.alignment)
            .put("outputState", if (statusVisible) row.outputState?.takeIf { value -> LiveOutputState.entries.any { it.name == value } } ?: JSONObject.NULL else JSONObject.NULL)
            .put("endReason", if (statusVisible) row.endReason?.takeIf { value -> NativeAudioEndReason.entries.any { it.name == value } } ?: JSONObject.NULL else JSONObject.NULL)
            .apply {
                row.nativePresentationLine()?.let { native ->
                    put("nativeMetadataVersion", 1).put("nativeAudioSessionId", native.nativeAudioSessionId)
                    put("liveSegmentLanguage", native.liveSegmentLanguage).put("sourceLanguageTag", native.sourceLanguageTag)
                    put("liveSourceFinal", native.liveSourceFinal ?: JSONObject.NULL)
                    put("liveSourceFailed", native.liveSourceFailed).put("liveSourceExpired", native.liveSourceExpired)
                    groups[row.part to row.sequence]?.let { put("displayGroupSequence", it) }
                }
            }
    }).toString()
}
