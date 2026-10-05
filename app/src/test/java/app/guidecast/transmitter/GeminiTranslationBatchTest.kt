package app.guidecast.transmitter

import app.guidecast.core.translation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiTranslationBatchTest {
    private val targets = listOf("en", "zh", "ja", "ru")
    private val values = linkedMapOf("en" to "Hello.", "zh" to "你好。", "ja" to "こんにちは。", "ru" to "Здравствуйте.")
    private val options = TranslationApiOptions(provider = TranslationApiProvider.GEMINI,
        model = "gemini-3.5-flash-lite", baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        hasKey = true, allowOnline = true)
    private fun response(translations: Map<String, String> = values, finish: String = "STOP") = JSONObject()
        .put("candidates", JSONArray().put(JSONObject().put("finishReason", finish)
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", JSONObject(translations).toString()))))))
        .put("usageMetadata", JSONObject().put("promptTokenCount", 100).put("cachedContentTokenCount", 20)
            .put("candidatesTokenCount", 40).put("thoughtsTokenCount", 5).put("totalTokenCount", 145)).toString()

    private class Connection(url: URL, private val response: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = -1L
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun everyFlashPresetSendsOneSelectedModelRequestForFourTargetsEvenWithoutKnownPrice() = runBlocking {
        for (choice in serviceModelChoices(options)) {
            val selected = options.copy(model = choice.id)
            val endpoints = mutableListOf<String>()
            val service = TranslationApiService({ selected }, { it == selected }, { "synthetic-key" }, {
                BoundedCloudHttps(connectionFactory = { url -> endpoints += url.toString(); Connection(url, response()) })
            })
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val batch = SharedTranslationBatchContext("selected-model", targets, parent, { true })
            try {
                val results = targets.map { target -> async {
                    withContext(batch + TranslationRequestIdentity("selected-model", 1)) {
                        service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") })
                            .translate("안녕하세요.", "ko", target)
                    }
                } }.awaitAll()
                assertEquals(values.values.toList(), results)
                assertEquals(listOf(selected.endpoint), endpoints)
                assertEquals(choice.id, service.usage.value.lastModel)
                assertEquals(1L, service.usage.value.requests)
                assertEquals(145L, service.usage.value.reportedTotalTokens)
            } finally { parent.cancel() }
        }
    }

    @Test fun fourIndependentWorkersSendInputOnceAndCountReportedUsageOnce() = runBlocking {
        val calls = AtomicInteger()
        val connection = Connection(URL(options.endpoint), response())
        val service = TranslationApiService({ options }, { it == options }, { "synthetic-key" },
            { BoundedCloudHttps(connectionFactory = { calls.incrementAndGet(); connection }) })
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batch = SharedTranslationBatchContext("input-session", targets, parent, { true })
        try {
            val translated = withTimeout(5_000) { targets.map { target -> async {
                withContext(batch + TranslationRequestIdentity("input-session", 1)) {
                    service.engine(TextTranslationEngine { _, _, _ -> error("Local inference forbidden") })
                        .translate("안녕하세요.", "ko", target)
                }
            } }.awaitAll() }
            assertEquals(values.values.toList(), translated)
            assertEquals(1, calls.get())
            val sent = JSONObject(connection.sent.toString())
            assertEquals(1, sent.getJSONArray("contents").length())
            assertEquals(1, sent.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").length())
            assertEquals(1L, service.usage.value.requests)
            assertEquals(100L, service.usage.value.textInputTokens)
            assertEquals(20L, service.usage.value.cachedInputTokens)
            assertEquals(45L, service.usage.value.textOutputTokens)
            assertEquals(145L, service.usage.value.reportedTotalTokens)
            assertEquals(4, service.usage.value.sharedTargetCount)
        } finally { parent.cancel() }
    }

    @Test fun incompleteLanguageSetCannotReleaseAnyResultOrTriggerRetries() = runBlocking {
        val calls = AtomicInteger()
        val service = TranslationApiService({ options }, { true }, { "synthetic" }, {
            BoundedCloudHttps(connectionFactory = { calls.incrementAndGet(); Connection(it, response(values - "ru")) })
        })
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val batch = SharedTranslationBatchContext("session", targets, parent, { true })
        try {
            val results = targets.map { target -> async {
                runCatching { withContext(batch + TranslationRequestIdentity("session", 1)) {
                    service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") }).translate("안녕하세요.", "ko", target)
                } }
            } }.awaitAll()
            assertTrue(results.all { it.isFailure })
            assertEquals(1, calls.get())
            assertEquals(1L, service.usage.value.requests)
        } finally { parent.cancel() }
    }

    @Test fun maxTokensNeverBecomesACompleteFourLanguageResponse() {
        assertTrue(runCatching { GeminiTranslationBatch.result(options, response(finish = "MAX_TOKENS"), targets) }.isFailure)
    }

    @Test fun rawUsageRetainsThoughtCandidateAndTotalWithoutAddingCacheAgain() {
        val usage = geminiBatchUsage(response())
        assertEquals(100L, usage.prompt); assertEquals(20L, usage.cached)
        assertEquals(40L, usage.candidates); assertEquals(5L, usage.thoughts)
        assertEquals(145L, usage.total); assertEquals(true, usage.totalsMatch)
        assertEquals(false, geminiBatchUsage(response().replace("\"totalTokenCount\":145", "\"totalTokenCount\":165")).totalsMatch)
    }

    @Test fun missingStringFractionalNegativeAndImpossibleCachedCountsStayUnknown() {
        val usage = geminiBatchUsage("""{"usageMetadata":{"promptTokenCount":"100","candidatesTokenCount":1.5,"thoughtsTokenCount":-1}}""")
        assertNull(usage.prompt); assertNull(usage.candidates); assertNull(usage.thoughts); assertNull(usage.total)
        assertNull(geminiBatchUsage("{}").prompt)
        assertNull(geminiBatchUsage("""{"usageMetadata":{"promptTokenCount":10,"cachedContentTokenCount":11}}""").cached)
        assertEquals(0L, geminiBatchUsage("""{"usageMetadata":{"promptTokenCount":0}}""").prompt)
    }
}
