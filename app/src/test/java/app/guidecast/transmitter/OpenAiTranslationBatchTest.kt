package app.guidecast.transmitter

import app.guidecast.core.translation.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiTranslationBatchTest {
    private val targets = listOf("en", "zh", "ja", "ru")
    private val values = linkedMapOf("en" to "Hello.", "zh" to "你好。", "ja" to "こんにちは。", "ru" to "Здравствуйте.")
    private val options = TranslationApiOptions(provider = TranslationApiProvider.OPENAI, hasKey = true, allowOnline = true)
    private fun response(content: String = JSONObject(values).toString(), status: String = "completed") = JSONObject()
        .put("status", status).put("id", "response-fixture")
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("content",
            JSONArray().put(JSONObject().put("type", "output_text").put("text", content)))))
        .put("usage", JSONObject().put("input_tokens", 100).put("input_tokens_details", JSONObject().put("cached_tokens", 20))
            .put("output_tokens", 45).put("output_tokens_details", JSONObject().put("reasoning_tokens", 5)).put("total_tokens", 145)).toString()

    private class Connection(url: URL, val body: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun getOutputStream() = sent
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = -1L
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "synthetic"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun fourLanguageWorkersShareOneBodyAndOneRawUsageReceipt() = runBlocking {
        var calls = 0
        val connection = Connection(URL(options.endpoint), response())
        val service = TranslationApiService({ options }, { it == options }, { "synthetic-key" }, {
            BoundedCloudHttps(connectionFactory = { calls++; connection })
        })
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val batch = SharedTranslationBatchContext("shared-openai", targets, parent, { true })
            val results = targets.map { target -> async {
                withContext(batch + TranslationRequestIdentity("shared-openai", 1)) {
                    service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") }).translate("안녕하세요.", "ko", target)
                }
            } }.awaitAll()
            assertEquals(values.values.toList(), results)
            assertEquals(1, calls)
            val request = JSONObject(connection.sent.toString(Charsets.UTF_8.name()))
            assertFalse(request.getBoolean("store"))
            assertEquals(1, request.getJSONArray("input").length())
            assertEquals("안녕하세요.", JSONObject(request.getJSONArray("input").getJSONObject(0).getString("content")).getString("current_text"))
            assertEquals(targets.toSet(), request.getJSONObject("text").getJSONObject("format").getJSONObject("schema")
                .getJSONObject("properties").keys().asSequence().toSet())
            val usage = service.usage.value
            assertEquals(1L, usage.requests); assertEquals(4, usage.sharedTargetCount)
            assertEquals(100L, usage.reportedInputTokens); assertEquals(45L, usage.reportedOutputTokens)
            assertEquals(5L, usage.reportedReasoningTokens); assertEquals(145L, usage.reportedTotalTokens)
            assertEquals(45L, usage.textOutputTokens)
            assertNull(usage.reportedCandidateTokens)
        } finally { parent.cancel() }
    }

    @Test fun bothOfficialProtocolsHaveExactStrictLanguageSchemas() {
        for (protocol in TranslationApiProtocol.entries) {
            val row = JSONObject(OpenAiTranslationBatch.request(options.copy(protocol = protocol), TranslationStyle.CONVERSATIONAL,
                "안녕하세요.", null, "ko", targets))
            val schema = if (protocol == TranslationApiProtocol.RESPONSES) row.getJSONObject("text").getJSONObject("format")
                else row.getJSONObject("response_format").getJSONObject("json_schema")
            assertTrue(schema.getBoolean("strict"))
            assertFalse(schema.getJSONObject("schema").getBoolean("additionalProperties"))
            assertEquals(4, schema.getJSONObject("schema").getJSONArray("required").length())
            assertFalse(row.getBoolean("store"))
        }
    }

    private suspend fun sharedUsage(body: String): TranslationUsageSummary {
        var calls = 0
        val service = TranslationApiService({ options }, { it == options }, { "synthetic-key" }, {
            BoundedCloudHttps(connectionFactory = { calls++; Connection(it, body) })
        })
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val batch = SharedTranslationBatchContext("usage-validation", targets, parent, { true })
            val results = coroutineScope { targets.map { target -> async {
                withContext(batch + TranslationRequestIdentity("usage-validation", 1)) {
                    service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") }).translate("안녕하세요.", "ko", target)
                }
            } }.awaitAll() }
            assertEquals(values.values.toList(), results); assertEquals(1, calls)
            return service.usage.value
        } finally { parent.cancel() }
    }

    @Test fun malformedCountsRemainUnknownInBothProviderReportAndAccumulation() = runBlocking {
        for ((field, value) in listOf("input_tokens" to "100", "output_tokens" to 45.25)) {
            val root = JSONObject(response()).apply { getJSONObject("usage").put(field, value) }
            val usage = sharedUsage(root.toString())
            assertEquals(1L, usage.requests); assertEquals(1L, usage.unconfirmedUsage)
            assertEquals(0L, usage.textInputTokens); assertEquals(0L, usage.textOutputTokens)
            assertEquals(0L, usage.cachedInputTokens); assertEquals(0, usage.estimatedUsd.signum())
            if (field == "input_tokens") assertNull(usage.reportedInputTokens) else assertNull(usage.reportedOutputTokens)
            assertNull(usage.reportedTotalsMatch)
        }
    }

    @Test fun inconsistentTotalsStayVisibleButCannotBePricedOrAddedToVerifiedUsage() = runBlocking {
        val root = JSONObject(response()).apply { getJSONObject("usage").put("total_tokens", 170) }
        val usage = sharedUsage(root.toString())
        assertEquals(100L, usage.reportedInputTokens); assertEquals(45L, usage.reportedOutputTokens)
        assertEquals(170L, usage.reportedTotalTokens); assertEquals(false, usage.reportedTotalsMatch)
        assertEquals(1L, usage.requests); assertEquals(1L, usage.unconfirmedUsage)
        assertEquals(0L, usage.textInputTokens); assertEquals(0L, usage.textOutputTokens)
        assertEquals(0L, usage.cachedInputTokens); assertEquals(0, usage.estimatedUsd.signum())
    }

    @Test fun incompleteDuplicateOrTruncatedLanguageResultsCannotBeAccepted() {
        for (content in listOf(JSONObject(values - "ru").toString(), "{\"en\":\"Hi\",\"\\u0065n\":\"Again\"}", "{\"en\":\"Hi\""))
            assertTrue(runCatching { OpenAiTranslationBatch.result(options, response(content), targets) }.isFailure)
        assertTrue(runCatching { OpenAiTranslationBatch.result(options, response(status = "incomplete"), targets) }.isFailure)
    }

    @Test fun missingLanguageFailsAllSubscribersWithoutASecondPaidAttempt() = runBlocking {
        var calls = 0
        val service = TranslationApiService({ options }, { true }, { "synthetic-key" }, {
            BoundedCloudHttps(connectionFactory = { calls++; Connection(it, response(JSONObject(values - "ru").toString())) })
        })
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val batch = SharedTranslationBatchContext("bad-batch", targets, parent, { true })
            val results = targets.map { target -> async {
                runCatching { withContext(batch + TranslationRequestIdentity("bad-batch", 1)) {
                    service.engine(TextTranslationEngine { _, _, _ -> error("No fallback") }).translate("안녕하세요.", "ko", target)
                } }
            } }.awaitAll()
            assertTrue(results.all { it.isFailure }); assertEquals(1, calls); assertEquals(1L, service.usage.value.requests)
        } finally { parent.cancel() }
    }

    @Test fun reasoningAndCacheRemainPartsOfReportedTotalsRatherThanAddedAgain() {
        val usage = openAiBatchUsage(response())
        assertEquals(100L, usage.input); assertEquals(20L, usage.cached); assertEquals(45L, usage.output)
        assertEquals(5L, usage.reasoning); assertEquals(145L, usage.total); assertEquals(true, usage.totalsMatch)
        assertNotNull(usage.responseIdDigest)
        assertEquals(false, openAiBatchUsage(response().replace("\"total_tokens\":145", "\"total_tokens\":170")).totalsMatch)
    }

    @Test fun missingInvalidUsageStaysUnknownAndReportedZeroIsPreserved() {
        val missing = openAiBatchUsage("{}"); assertNull(missing.input); assertNull(missing.output); assertNull(missing.reasoning)
        val invalid = openAiBatchUsage("""{"usage":{"input_tokens":"2","output_tokens":1.5,"total_tokens":-1}}""")
        assertNull(invalid.input); assertNull(invalid.output); assertNull(invalid.total)
        assertEquals(0L, openAiBatchUsage("""{"usage":{"input_tokens":0}}""").input)
    }
}
