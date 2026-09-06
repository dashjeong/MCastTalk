package app.guidecast.core.server

import java.security.MessageDigest

/**
 * Keeps one immutable JSON representation per listener scope (all channels or one channel).
 *
 * [GuideCastLocalServer]'s provider intentionally returns snapshots rather than exposing mutable
 * server state. The cache still freezes every changed projection so an accidentally mutable map
 * cannot make a previously issued strong ETag point at different bytes.
 */
internal class TranscriptResponseCache(
    private val serialize: (List<GuideCastTranscriptLine>) -> ByteArray = { lines ->
        lines.toTranscriptsJson().encodeToByteArray()
    },
) {
    private val entries = mutableMapOf<String?, CacheEntry>()

    @Synchronized
    fun responseFor(
        providerSnapshot: List<GuideCastTranscriptLine>,
        requestedChannel: String?,
    ): TranscriptHttpRepresentation {
        val cached = entries[requestedChannel]
        if (cached?.projectedSnapshot?.matches(providerSnapshot, requestedChannel) == true) {
            return cached.representation
        }

        val projectedSnapshot = providerSnapshot.map { line ->
            line.frozenProjection(requestedChannel)
        }
        val body = serialize(projectedSnapshot)
        val representation = TranscriptHttpRepresentation(
            body = body,
            etag = body.strongSha256Etag(),
        )
        entries[requestedChannel] = CacheEntry(
            sourceRevision = null,
            projectedSnapshot = projectedSnapshot,
            representation = representation,
        )
        return representation
    }

    /** O(1) unchanged-poll path for the product's revisioned immutable publication. */
    @Synchronized
    fun responseFor(
        providerSnapshot: GuideCastTranscriptSnapshot,
        requestedChannel: String?,
    ): TranscriptHttpRepresentation {
        val cached = entries[requestedChannel]
        if (cached?.sourceRevision == providerSnapshot.revision) return cached.representation

        val projectedSnapshot = providerSnapshot.lines.map { line ->
            line.frozenProjection(requestedChannel)
        }
        val body = serialize(projectedSnapshot)
        val representation = TranscriptHttpRepresentation(
            body = body,
            etag = body.strongSha256Etag(),
        )
        entries[requestedChannel] = CacheEntry(
            sourceRevision = providerSnapshot.revision,
            // Revision is the equality contract on this path. Retaining another graph of up to
            // 1,000 projected rows per language would duplicate the immutable publication after
            // its JSON bytes have already been frozen.
            projectedSnapshot = null,
            representation = representation,
        )
        return representation
    }

    private data class CacheEntry(
        val sourceRevision: Long?,
        val projectedSnapshot: List<GuideCastTranscriptLine>?,
        val representation: TranscriptHttpRepresentation,
    )
}

internal data class TranscriptHttpRepresentation(
    val body: ByteArray,
    val etag: String,
)

/** If-None-Match uses weak comparison for GET, so W/ and strong forms both revalidate. */
internal fun String?.matchesIfNoneMatch(currentEtag: String): Boolean {
    if (this == null) return false
    return split(',').any { rawCandidate ->
        val candidate = rawCandidate.trim()
        candidate == "*" || candidate == currentEtag ||
            candidate.removePrefix("W/") == currentEtag
    }
}

private fun List<GuideCastTranscriptLine>.matches(
    providerSnapshot: List<GuideCastTranscriptLine>,
    requestedChannel: String?,
): Boolean {
    if (size != providerSnapshot.size) return false
    return indices.all { index ->
        this[index].matchesProjection(providerSnapshot[index], requestedChannel)
    }
}

private fun GuideCastTranscriptLine.matchesProjection(
    providerLine: GuideCastTranscriptLine,
    requestedChannel: String?,
): Boolean {
    if (
        sequence != providerLine.sequence ||
        sourceText != providerLine.sourceText ||
        isFinal != providerLine.isFinal ||
        capturedAtElapsedRealtimeNanos != providerLine.capturedAtElapsedRealtimeNanos
    ) {
        return false
    }
    if (requestedChannel == null) {
        return translations == providerLine.translations &&
            translationLatencyMillis == providerLine.translationLatencyMillis &&
            firstAudioLatencyMillis == providerLine.firstAudioLatencyMillis &&
            synthesisLatencyMillis == providerLine.synthesisLatencyMillis
    }
    return translations[requestedChannel] == providerLine.translations[requestedChannel] &&
        translationLatencyMillis[requestedChannel] ==
        providerLine.translationLatencyMillis[requestedChannel] &&
        firstAudioLatencyMillis[requestedChannel] ==
        providerLine.firstAudioLatencyMillis[requestedChannel] &&
        synthesisLatencyMillis[requestedChannel] ==
        providerLine.synthesisLatencyMillis[requestedChannel]
}

private fun GuideCastTranscriptLine.frozenProjection(
    requestedChannel: String?,
): GuideCastTranscriptLine = if (requestedChannel == null) {
    copy(
        translations = translations.toMap(),
        translationLatencyMillis = translationLatencyMillis.toMap(),
        firstAudioLatencyMillis = firstAudioLatencyMillis.toMap(),
        synthesisLatencyMillis = synthesisLatencyMillis.toMap(),
    )
} else {
    copy(
        translations = translations.copyEntry(requestedChannel),
        translationLatencyMillis = translationLatencyMillis.copyEntry(requestedChannel),
        firstAudioLatencyMillis = firstAudioLatencyMillis.copyEntry(requestedChannel),
        synthesisLatencyMillis = synthesisLatencyMillis.copyEntry(requestedChannel),
    )
}

private fun <Value : Any> Map<String, Value>.copyEntry(key: String): Map<String, Value> =
    get(key)?.let { value -> mapOf(key to value) } ?: emptyMap()

private fun ByteArray.strongSha256Etag(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    return buildString(69) {
        append('"').append("gc-")
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
        append('"')
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
