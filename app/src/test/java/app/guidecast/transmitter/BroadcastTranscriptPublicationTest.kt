package app.guidecast.transmitter

import app.guidecast.core.server.GuideCastTranscriptSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastTranscriptPublicationTest {
    @Test
    fun `fifty polls share one immutable publication and only transcript changes advance revision`() {
        val sessionId = 72L
        val line = transcriptLine("Peace")
        var archive = TranscriptArchiveSnapshot()
        var runtime = BroadcastSnapshot(transcripts = listOf(line))
        val publication = BroadcastTranscriptPublication(
            archiveSessionId = sessionId,
            archiveSnapshot = { archive },
            runtimeSnapshot = { runtime },
        )

        val first = publication.snapshot()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = List(50) {
                executor.submit<GuideCastTranscriptSnapshot> {
                    start.await()
                    publication.snapshot()
                }
            }
            start.countDown()
            results.map { it.get(2L, TimeUnit.SECONDS) }
                .forEach { snapshot -> assertSame(first, snapshot) }
        } finally {
            executor.shutdownNow()
        }

        // Operational telemetry and archive queue state retain their transcript list identities.
        runtime = runtime.copy(listenerCount = 50)
        archive = archive.copy(pendingWriteCount = 1, warning = "retry")
        assertSame(first, publication.snapshot())

        // SQLite catching up with the identical live line must not invalidate all web ETags.
        archive = TranscriptArchiveSnapshot(
            lines = listOf(
                ArchivedTranscriptLine(
                    key = TranscriptArchiveKey(sessionId, line.sequence),
                    sessionStartedAtEpochMillis = 1L,
                    sourceLanguageTag = "ko",
                    line = line,
                ),
            ),
        )
        assertSame(first, publication.snapshot())

        runtime = runtime.copy(transcripts = listOf(transcriptLine("Walk for peace")))
        val changed = publication.snapshot()
        assertNotSame(first, changed)
        assertEquals(first.revision + 1L, changed.revision)
        assertEquals("Walk for peace", changed.lines.single().translations["en"])
    }

    @Test
    fun `archive deletion and current line removal invalidate the published snapshot`() {
        val sessionId = 91L
        val line = transcriptLine("Peace")
        var archive = TranscriptArchiveSnapshot(
            lines = listOf(
                ArchivedTranscriptLine(
                    key = TranscriptArchiveKey(sessionId, line.sequence),
                    sessionStartedAtEpochMillis = 1L,
                    sourceLanguageTag = "ko",
                    line = line,
                ),
            ),
        )
        var runtime = BroadcastSnapshot()
        val publication = BroadcastTranscriptPublication(
            archiveSessionId = sessionId,
            archiveSnapshot = { archive },
            runtimeSnapshot = { runtime },
        )

        val beforeDelete = publication.snapshot()
        archive = TranscriptArchiveSnapshot(lines = emptyList())
        val afterDelete = publication.snapshot()

        assertEquals(1, beforeDelete.lines.size)
        assertTrue(afterDelete.lines.isEmpty())
        assertEquals(beforeDelete.revision + 1L, afterDelete.revision)

        runtime = BroadcastSnapshot(transcripts = listOf(line.copy(isFinal = false)))
        val currentAdded = publication.snapshot()
        runtime = runtime.copy(transcripts = emptyList())
        val currentRemoved = publication.snapshot()
        assertEquals(currentAdded.revision + 1L, currentRemoved.revision)
        assertTrue(currentRemoved.lines.isEmpty())
    }

    @Test
    fun `late request with an older source sample cannot overwrite a newer publication`() {
        var runtime = BroadcastSnapshot(transcripts = listOf(transcriptLine("Baseline")))
        val blockNextRuntimeRead = AtomicBoolean(false)
        val olderSampleCaptured = CountDownLatch(1)
        val releaseOlderRequest = CountDownLatch(1)
        val publication = BroadcastTranscriptPublication(
            archiveSessionId = null,
            archiveSnapshot = { TranscriptArchiveSnapshot() },
            runtimeSnapshot = {
                val sampled = runtime
                if (blockNextRuntimeRead.compareAndSet(true, false)) {
                    olderSampleCaptured.countDown()
                    check(releaseOlderRequest.await(2L, TimeUnit.SECONDS)) {
                        "Timed out waiting to release the older transcript request"
                    }
                }
                sampled
            },
        )
        publication.snapshot()

        runtime = BroadcastSnapshot(transcripts = listOf(transcriptLine("Older")))
        blockNextRuntimeRead.set(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lateOlderRequest = executor.submit<GuideCastTranscriptSnapshot> {
                publication.snapshot()
            }
            assertTrue(olderSampleCaptured.await(2L, TimeUnit.SECONDS))

            runtime = BroadcastSnapshot(transcripts = listOf(transcriptLine("Newest")))
            val newerPublication = executor.submit<GuideCastTranscriptSnapshot> {
                publication.snapshot()
            }.get(2L, TimeUnit.SECONDS)
            assertEquals("Newest", newerPublication.lines.single().translations["en"])

            releaseOlderRequest.countDown()
            val lateResult = lateOlderRequest.get(2L, TimeUnit.SECONDS)

            assertSame(newerPublication, lateResult)
            assertEquals("Newest", lateResult.lines.single().translations["en"])
            assertSame(newerPublication, publication.snapshot())
        } finally {
            releaseOlderRequest.countDown()
            executor.shutdownNow()
        }
    }

    private fun transcriptLine(english: String) = TranslationTranscriptLine(
        sequence = 3L,
        sourceText = "평화",
        capturedAtElapsedRealtimeNanos = 300L,
        isFinal = true,
        translations = mapOf("en" to english),
        translationLatencyMillis = mapOf("en" to 80L),
        firstAudioLatencyMillis = mapOf("en" to 450L),
        synthesisLatencyMillis = mapOf("en" to 190L),
    )
}
