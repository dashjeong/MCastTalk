package app.guidecast.core.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptResponseCacheTest {
    @Test
    fun `fifty concurrent listeners share one serialized channel representation`() {
        val serializationCount = AtomicInteger(0)
        val cache = TranscriptResponseCache { lines ->
            serializationCount.incrementAndGet()
            Thread.sleep(5L)
            lines.toTranscriptsJson().encodeToByteArray()
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)

        try {
            val responses = List(50) {
                executor.submit<TranscriptHttpRepresentation> {
                    start.await()
                    cache.responseFor(listOf(transcriptLine()), "en")
                }
            }
            start.countDown()
            val completed = responses.map { future -> future.get(2L, TimeUnit.SECONDS) }

            assertEquals(1, serializationCount.get())
            completed.drop(1).forEach { response -> assertSame(completed.first(), response) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `revisioned publication skips row scans and serialization until revision changes`() {
        val serializationCount = AtomicInteger(0)
        val cache = TranscriptResponseCache { lines ->
            serializationCount.incrementAndGet()
            lines.toTranscriptsJson().encodeToByteArray()
        }
        val initial = GuideCastTranscriptSnapshot(4L, listOf(transcriptLine()))

        val first = cache.responseFor(initial, "en")
        val sameRevisionDifferentObject = cache.responseFor(
            GuideCastTranscriptSnapshot(
                revision = 4L,
                lines = listOf(transcriptLine(translations = mapOf("en" to "ignored by contract"))),
            ),
            "en",
        )
        val changed = cache.responseFor(
            GuideCastTranscriptSnapshot(
                revision = 5L,
                lines = listOf(transcriptLine(translations = mapOf("en" to "Peaceful walk"))),
            ),
            "en",
        )

        assertSame(first, sameRevisionDifferentObject)
        assertEquals(2, serializationCount.get())
        assertFalse(first.etag == changed.etag)
        assertTrue(changed.body.decodeToString().contains("Peaceful walk"))
    }

    @Test
    fun `unchanged channel projection reuses bytes and ignores sibling translation changes`() {
        var serializationCount = 0
        val cache = TranscriptResponseCache { lines ->
            serializationCount += 1
            lines.toTranscriptsJson().encodeToByteArray()
        }
        val initial = transcriptLine(
            translations = linkedMapOf("en" to "Peace", "ja" to "平和"),
        )

        val firstEnglish = cache.responseFor(listOf(initial), "en")
        val equalEnglish = cache.responseFor(
            listOf(initial.copy(translations = initial.translations.toMap())),
            "en",
        )
        val firstJapanese = cache.responseFor(listOf(initial), "ja")
        val japaneseChanged = initial.copy(
            translations = linkedMapOf("en" to "Peace", "ja" to "平和です"),
        )
        val englishAfterJapaneseChange = cache.responseFor(listOf(japaneseChanged), "en")
        val japaneseAfterChange = cache.responseFor(listOf(japaneseChanged), "ja")

        assertSame(firstEnglish, equalEnglish)
        assertSame(firstEnglish, englishAfterJapaneseChange)
        assertEquals(firstEnglish.etag, englishAfterJapaneseChange.etag)
        assertFalse(firstJapanese.etag == japaneseAfterChange.etag)
        assertEquals(3, serializationCount)
        assertTrue(firstEnglish.body.decodeToString().contains("Peace"))
        assertFalse(firstEnglish.body.decodeToString().contains("平和"))
    }

    @Test
    fun `frozen snapshot detects a mutable provider map changing in place`() {
        val translations = linkedMapOf("en" to "First")
        val line = transcriptLine(translations = translations)
        val cache = TranscriptResponseCache()

        val first = cache.responseFor(listOf(line), "en")
        translations["en"] = "Second"
        val second = cache.responseFor(listOf(line), "en")

        assertFalse(first.etag == second.etag)
        assertTrue(second.body.decodeToString().contains("Second"))
        assertFalse(second.body.decodeToString().contains("First"))
    }

    @Test
    fun `if none match accepts strong weak list and wildcard validators only`() {
        val etag = TranscriptResponseCache()
            .responseFor(listOf(transcriptLine()), "en")
            .etag

        assertTrue(etag.matches(Regex("\\\"gc-[0-9a-f]{64}\\\"")))
        assertTrue(etag.matchesIfNoneMatch(etag))
        assertTrue("W/$etag".matchesIfNoneMatch(etag))
        assertTrue("\"obsolete\", W/$etag".matchesIfNoneMatch(etag))
        assertTrue("*".matchesIfNoneMatch(etag))
        assertFalse(null.matchesIfNoneMatch(etag))
        assertFalse("w/$etag".matchesIfNoneMatch(etag))
        assertFalse("${etag}extra".matchesIfNoneMatch(etag))
    }

    private fun transcriptLine(
        translations: Map<String, String> = mapOf("en" to "Peace"),
    ) = GuideCastTranscriptLine(
        sequence = 7L,
        sourceText = "평화",
        isFinal = true,
        capturedAtElapsedRealtimeNanos = 12_000L,
        translations = translations,
        translationLatencyMillis = mapOf("en" to 110L, "ja" to 130L),
        firstAudioLatencyMillis = mapOf("en" to 510L, "ja" to 530L),
        synthesisLatencyMillis = mapOf("en" to 210L, "ja" to 230L),
    )
}
