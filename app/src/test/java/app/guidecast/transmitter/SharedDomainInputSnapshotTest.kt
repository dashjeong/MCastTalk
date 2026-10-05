package app.guidecast.transmitter

import app.guidecast.core.translation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedDomainInputSnapshotTest {
    private val targets = listOf("en", "zh", "ja", "ru")
    private val translations = mapOf("en" to "Hello", "zh" to "你好", "ja" to "こんにちは", "ru" to "Здравствуйте")
    private class Repository : DomainCorpusRepository(null) {
        override val revision = MutableStateFlow(7L)
        val calls = AtomicInteger()
        var mutateDuringCapture = false
        override suspend fun match(text: String, source: String, target: String, style: TranslationStyle): DomainCorpusMatch {
            val version = revision.value
            if (calls.incrementAndGet() == 1 && mutateDuringCapture) revision.value = 8L
            return DomainCorpusMatch(null, JSONObject().put("domain", "domain-$target-v$version")
                .put("examples", JSONArray().put(JSONObject().put("source", "안녕")
                    .put("translation", mapOf("en" to "Hello", "zh" to "你好", "ja" to "こんにちは", "ru" to "Здравствуйте").getValue(target)))).toString(), version)
        }
    }
    private class Connection(url: URL, private val beforeSend: () -> Unit) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent.also { beforeSend() }
        override fun getInputStream() = ByteArrayInputStream(JSONObject().put("candidates", JSONArray()
            .put(JSONObject().put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", "{\"en\":\"Hello\",\"zh\":\"你好\",\"ja\":\"こんにちは\",\"ru\":\"Здравствуйте\"}")))))).toString().toByteArray())
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = -1L
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "mock"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun fourTargetReferenceAt600CharactersIsWholeAndIdenticalForEveryLocalContext(): Unit = runBlocking {
        fun reference(padding: Int): String = JSONObject().put("references", JSONArray().apply {
            targets.forEach { target -> put(JSONObject().put("target_language", target)
                .put("reference", JSONObject().put("hint", if (target == "ru") "x".repeat(padding) else target))) }
        }).toString()
        val padding = 600 - reference(0).length
        assertTrue(padding > 0)
        val repository = object : DomainCorpusRepository(null) {
            override val revision = MutableStateFlow(9L)
            override suspend fun match(text: String, source: String, target: String, style: TranslationStyle) =
                DomainCorpusMatch(null, JSONObject().put("hint", if (target == "ru") "x".repeat(padding) else target).toString(), 9L)
        }
        val observed = mutableMapOf<String, DomainTranslationContext>()
        val local = TextTranslationEngine { _, _, target ->
            observed[target] = requireNotNull(currentCoroutineContext()[DomainTranslationContext])
            translations.getValue(target)
        }
        val engine = DomainCorpusTranslationEngine(local, repository)
        val snapshot = engine.captureInputSnapshot("안녕", "ko", targets, TranslationStyle.CONVERSATIONAL)
        assertEquals(600, snapshot.reference.length)
        assertEquals(4, JSONObject(snapshot.reference).getJSONArray("references").length())
        targets.forEach { engine.fromInputSnapshot(snapshot, it).translate("안녕", "ko", it) }
        assertEquals(targets.toSet(), observed.keys)
        observed.values.forEach { assertEquals(snapshot.reference, it.hints); assertEquals(9L, it.revision) }
        val smaller = DomainCorpusTranslationEngine(local, repository, referenceHintBudget = { 599 })
            .captureInputSnapshot("안녕", "ko", targets, TranslationStyle.CONVERSATIONAL)
        val entries = JSONObject(smaller.reference).getJSONArray("references")
        assertEquals(3, entries.length())
        assertTrue(smaller.reference.length < 599)
        assertFalse((0 until entries.length()).any { entries.getJSONObject(it).getString("target_language") == "ru" })
    }

    @Test fun approvedExactPairMustFitSharedEvidenceBeforeItCanBecomeAComparison(): Unit = runBlocking {
        for (oversizedRussianPair in listOf(false, true)) {
            val calls = AtomicInteger()
            val repository = object : DomainCorpusRepository(null) {
                override val revision = MutableStateFlow(9L)
                override suspend fun match(text: String, source: String, target: String, style: TranslationStyle): DomainCorpusMatch {
                    calls.incrementAndGet()
                    val exact = if (oversizedRussianPair && target == "ru") "Здравствуйте".repeat(80) else translations.getValue(target)
                    return DomainCorpusMatch(exact, "", 9L)
                }
            }
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI, model = "gemini-3.5-flash-lite",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta", allowOnline = true, hasKey = true,
                alwaysLearnOnline = true, allowDomainReferences = true)
            val connection = Connection(URL(options.endpoint)) {}
            val requests = AtomicInteger()
            val service = TranslationApiService({ options }, { true }, { "synthetic" },
                { BoundedCloudHttps(connectionFactory = { requests.incrementAndGet(); connection }) },
                shadowAllowed = { true }, shadowScope = parent)
            val batch = SharedTranslationBatchContext("exact", targets, parent, { true })
            try {
                val results = targets.mapIndexed { index, target ->
                    val result = withContext(batch + TranslationRequestIdentity("exact", 1)) {
                        service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> error("Approved exact pairs bypass inference") }, repository))
                            .translate("안녕", "ko", target)
                    }
                    // Keep the bounded low-priority queue unchanged. Exercise each exact
                    // decision after the previous shadow completes, still sharing API1.
                    withTimeout(3_000) { while (service.shadow.value.completed + service.shadow.value.skipped < index + 1L) delay(5) }
                    result
                }
                assertEquals(targets.map(translations::getValue), results)
                val expected = if (oversizedRussianPair) 3L else 4L
                withTimeout(3_000) { while (service.shadow.value.completed < expected) delay(5) }
                assertEquals(expected, service.shadow.value.completed)
                assertEquals(if (oversizedRussianPair) 1L else 0L, service.shadow.value.skipped)
                assertEquals(9L, service.shadow.value.last!!.corpusRevision)
                assertEquals(1, requests.get()); assertEquals(4, calls.get())
                val input = JSONObject(JSONObject(connection.sent.toString(Charsets.UTF_8)).getJSONArray("contents")
                    .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
                val reference = input.getString("related_reference")
                assertTrue(reference.length <= 600)
                assertEquals(expected.toInt(), JSONObject(reference).getJSONArray("references").length())
                assertEquals(9L, input.getLong("corpus_revision"))
            } finally { parent.cancel() }
        }
    }

    @Test fun repositoryUpdateAfterCaptureCannotChangeEitherComparisonEvidenceOrRecordedVersion(): Unit = runBlocking {
        val repository = Repository()
        val releaseLocal = CompletableDeferred<Unit>()
        val observed = java.util.concurrent.ConcurrentHashMap<String, DomainTranslationContext>()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI, model = "gemini-3.5-flash-lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta", allowOnline = true, hasKey = true,
            alwaysLearnOnline = true, allowDomainReferences = true)
        val connection = Connection(URL(options.endpoint)) { repository.revision.value = 8L; releaseLocal.complete(Unit) }
        val requests = AtomicInteger()
        val service = TranslationApiService({ options }, { true }, { "synthetic" },
            { BoundedCloudHttps(connectionFactory = { requests.incrementAndGet(); connection }) },
            shadowAllowed = { true }, shadowScope = parent)
        val batch = SharedTranslationBatchContext("input", targets, parent, { true })
        try {
            targets.map { target -> async {
                withContext(batch + TranslationRequestIdentity("input", 1)) {
                    val local = TextTranslationEngine { _, _, language ->
                        releaseLocal.await()
                        observed[language] = requireNotNull(currentCoroutineContext()[DomainTranslationContext])
                        translations.getValue(language)
                    }
                    service.engine(DomainCorpusTranslationEngine(local, repository)).translate("안녕", "ko", target)
                }
            } }.awaitAll()
            withTimeout(3_000) { while (service.shadow.value.completed + service.shadow.value.skipped != 4L) delay(5) }
            assertTrue(service.shadow.value.completed > 0)
            assertEquals(0L, service.shadow.value.incomplete)
            val input = JSONObject(JSONObject(connection.sent.toString(Charsets.UTF_8)).getJSONArray("contents")
                .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertEquals(7L, input.getLong("corpus_revision"))
            val reference = input.getString("related_reference")
            assertTrue(reference.length <= 600)
            assertTrue(reference.contains("v7")); assertFalse(reference.contains("v8"))
            assertEquals(4, repository.calls.get()); assertEquals(1, requests.get())
            assertEquals(7L, service.shadow.value.last!!.corpusRevision)
            assertEquals(service.shadow.value.completed.toInt(), observed.size)
            assertTrue(targets.containsAll(observed.keys))
            observed.values.forEach { assertEquals(reference, it.hints); assertEquals(7L, it.revision) }
        } finally { parent.cancel() }
    }

    @Test fun updateDuringAllTargetCaptureSkipsComparisonWithoutRecapturingOrSendingMixedEvidence(): Unit = runBlocking {
        val repository = Repository().apply { mutateDuringCapture = true }
        var localCalls = 0
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI, model = "gemini-3.5-flash-lite",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta", allowOnline = true, hasKey = true,
            alwaysLearnOnline = true, allowDomainReferences = true)
        val connection = Connection(URL(options.endpoint)) {}
        val service = TranslationApiService({ options }, { true }, { "synthetic" },
            { BoundedCloudHttps(connectionFactory = { connection }) }, shadowAllowed = { true }, shadowScope = parent)
        val batch = SharedTranslationBatchContext("input", targets, parent, { true })
        try {
            targets.map { target -> async {
                withContext(batch + TranslationRequestIdentity("input", 1)) {
                    service.engine(DomainCorpusTranslationEngine(TextTranslationEngine { _, _, _ -> localCalls++; "Hello" }, repository))
                        .translate("안녕", "ko", target)
                }
            } }.awaitAll()
            val input = JSONObject(JSONObject(connection.sent.toString(Charsets.UTF_8)).getJSONArray("contents")
                .getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            assertFalse(input.has("related_reference"))
            assertEquals(0, localCalls); assertEquals(0L, service.shadow.value.completed)
            assertEquals(4L, service.shadow.value.skipped); assertEquals(4, repository.calls.get())
            assertTrue(service.shadow.value.lastPause!!.contains("동일 자료 버전"))
        } finally { parent.cancel() }
    }
}
