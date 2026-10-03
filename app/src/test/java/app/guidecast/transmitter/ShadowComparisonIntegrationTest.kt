package app.guidecast.transmitter

import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.TextTranslationEngine
import app.guidecast.core.translation.TranslationStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShadowComparisonIntegrationTest {
    private class Repository : DomainCorpusRepository(null) {
        var version = 7L
        var calls = 0
        var failCapture = false
        override suspend fun match(text: String, source: String, target: String, style: TranslationStyle): DomainCorpusMatch {
            calls++
            if (failCapture) error("Unavailable corpus")
            return DomainCorpusMatch(null, "", version)
        }
    }
    private fun options() = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
        model = "gpt-realtime-2.1-mini", allowOnline = true, hasKey = true, alwaysLearnOnline = true)

    @Test fun primaryReturnsBeforeComparisonAndComparisonKeepsInputAndCapturedRevision() = runTest {
        val repo = Repository()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<List<String?>>()
        val local = object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(text: String, contextBefore: String?, sourceLanguageTag: String, targetLanguageTag: String): String {
                received += listOf(text, contextBefore, sourceLanguageTag, targetLanguageTag)
                release.await()
                return "Hi"
            }
            override suspend fun translate(text: String, sourceLanguageTag: String, targetLanguageTag: String) =
                translateWithContext(text, null, sourceLanguageTag, targetLanguageTag)
        }
        val options = options()
        val wire = FixtureRealtimeWire(realtimeFixture()).apply { afterReceive = { repo.version = 8 } }
        val service = TranslationApiService({ options }, { true }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(wire), { true }, backgroundScope)
        val engine = service.engine(DomainCorpusTranslationEngine(local, repo)) as ContextualTextTranslationEngine
        assertEquals("Hello", engine.translateWithContext("안녕", "인사", "ko", "en"))
        assertEquals(0L, service.shadow.value.completed)
        runCurrent()
        assertEquals(listOf(listOf("안녕", "인사", "ko", "en")), received)
        release.complete(Unit); runCurrent()
        assertEquals(1, repo.calls)
        assertEquals(7L, service.shadow.value.last!!.corpusRevision)
        assertEquals("Hello", service.shadow.value.last!!.online)
        assertEquals("Hi", service.shadow.value.last!!.offline)
        assertEquals(1, wire.opens)
    }

    @Test fun captureFailureCannotFailSelectedOnlineBroadcast() = runTest {
        val options = options()
        val repo = Repository().apply { failCapture = true }
        val service = TranslationApiService({ options }, { true }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(FixtureRealtimeWire(realtimeFixture())), { true }, backgroundScope)
        assertEquals("Hello", service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> error("No shadow") }, repo))
            .translate("안녕", "ko", "en"))
        assertEquals(1L, service.shadow.value.skipped)
    }

    @Test fun modeRevocationPreventsQueuedComparison() = runTest {
        var options = options()
        var localCalls = 0
        val service = TranslationApiService({ options }, { it == options && it.allowOnline }, { "synthetic" }, { error("No HTTP") },
            OpenAiRealtimeTransport(FixtureRealtimeWire(realtimeFixture())), { true }, backgroundScope)
        assertEquals("Hello", service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> localCalls++; "Hi" }, Repository()))
            .translate("안녕", "ko", "en"))
        options = options.copy(provider = TranslationApiProvider.LOCAL, allowOnline = false)
        runCurrent()
        assertEquals(0, localCalls)
        assertEquals(0L, service.shadow.value.completed)
    }
}
