package app.guidecast.transmitter

import app.guidecast.core.server.GuideCastTranscriptLine
import app.guidecast.core.server.GuideCastTranscriptSnapshot
import java.util.Collections

/**
 * Builds one immutable web transcript publication per actual transcript change.
 *
 * Hotspot listeners poll concurrently. Runtime/diagnostic updates that retain the same transcript
 * list, and archive pending-count/warning updates that retain the same archived line list, take the
 * lock-free identity path and do not filter, sort, map or copy transcript rows again.
 */
internal class BroadcastTranscriptPublication(
    private val archiveSessionId: Long?,
    private val archiveSnapshot: () -> TranscriptArchiveSnapshot,
    private val runtimeSnapshot: () -> BroadcastSnapshot,
) {
    @Volatile
    private var cached: CachedPublication? = null
    private var nextRevision = 0L

    fun snapshot(): GuideCastTranscriptSnapshot {
        val archivedLines = archiveSnapshot().lines
        val currentLines = runtimeSnapshot().transcripts
        cached?.takeIf { current ->
            current.archivedLines === archivedLines && current.currentLines === currentLines
        }?.let { current -> return current.snapshot }

        return synchronized(this) {
            // The optimistic references above can be older than a publication completed by
            // another request while this caller was waiting for the lock. Re-sample only on this
            // changed slow path so a late request can never overwrite the cache with older rows.
            val latestArchivedLines = archiveSnapshot().lines
            val latestCurrentLines = runtimeSnapshot().transcripts
            cached?.takeIf { current ->
                current.archivedLines === latestArchivedLines &&
                    current.currentLines === latestCurrentLines
            }?.snapshot ?: rebuild(latestArchivedLines, latestCurrentLines)
        }
    }

    private fun rebuild(
        archivedLines: List<ArchivedTranscriptLine>,
        currentLines: List<TranslationTranscriptLine>,
    ): GuideCastTranscriptSnapshot {
        val archivedForSession = archiveSessionId?.let { sessionId ->
            archivedLines.asSequence()
                .filter { archived -> archived.key.sessionId == sessionId }
                .map(ArchivedTranscriptLine::line)
                .toList()
        }.orEmpty()
        val publishedLines = mergeTranscriptLines(
            archivedFinalLines = archivedForSession,
            currentLines = currentLines,
        ).map { line ->
            // Freeze nested maps once per source revision. The HTTP cache can safely reuse its
            // strong ETag/body without defensive copies on every listener request.
            GuideCastTranscriptLine(
                sequence = line.sequence,
                sourceText = line.sourceText,
                isFinal = line.isFinal,
                capturedAtElapsedRealtimeNanos = line.capturedAtElapsedRealtimeNanos,
                translations = line.translations.toMap(),
                translationLatencyMillis = line.translationLatencyMillis.toMap(),
                firstAudioLatencyMillis = line.firstAudioLatencyMillis.toMap(),
                synthesisLatencyMillis = line.synthesisLatencyMillis.toMap(),
            )
        }.let { lines -> Collections.unmodifiableList(ArrayList(lines)) }

        val previous = cached
        val snapshot = if (previous?.snapshot?.lines == publishedLines) {
            // SQLite may publish the same final row already present in the live runtime. Advance
            // source identities, but do not invalidate six channel-specific JSON/ETag entries.
            previous.snapshot
        } else {
            check(nextRevision < Long.MAX_VALUE) { "Transcript revision space was exhausted" }
            nextRevision += 1L
            GuideCastTranscriptSnapshot(nextRevision, publishedLines)
        }
        cached = CachedPublication(archivedLines, currentLines, snapshot)
        return snapshot
    }

    private data class CachedPublication(
        val archivedLines: List<ArchivedTranscriptLine>,
        val currentLines: List<TranslationTranscriptLine>,
        val snapshot: GuideCastTranscriptSnapshot,
    )
}
