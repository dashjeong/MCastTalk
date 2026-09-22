package app.guidecast.transmitter

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeveloperCloudMemoryDeviceTest {
    @Test fun exactRegisterMemoryProtectsHumanCorrectionsDuringAiUpdateAndImport(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "sentence-memory-regression.db"
        context.deleteDatabase(name)
        val user = SentenceMemoryEntry(sourceLanguageTag = "ko-KR", targetLanguageTag = "en-US",
            translationRegister = TranslationRegister.FORMAL, original = "서울로 갑니다", corrected = "We travel to Seoul.", origin = SentenceMemoryOrigin.USER)
        SentenceTranslationMemory(context, name).use { memory ->
            assertTrue(memory.upsert(user))
            assertFalse(memory.upsert(user.copy(corrected = "Changed by AI", origin = SentenceMemoryOrigin.AI)))
            assertEquals(0, memory.importRecords(listOf(user.copy(corrected = "Old backup wording"))))
            assertEquals("We travel to Seoul.", memory.lookup("ko-kr", "en", TranslationRegister.FORMAL, "  서울로  갑니다  ")?.corrected)
            assertEquals("We travel to Seoul.", memory.lookup("ko", "en-US", TranslationRegister.FORMAL, user.original)?.corrected)
            assertNull(memory.lookup("ko-kr", "en", TranslationRegister.CONVERSATIONAL, user.original))
            assertTrue(memory.upsert(user.copy(translationRegister = TranslationRegister.CONVERSATIONAL, corrected = "Let's go to Seoul.", origin = SentenceMemoryOrigin.AI)))
            assertEquals(listOf("서울로 갑니다"), memory.recognitionHints("ko-KR"))
            val ai = memory.loadPage().single { it.origin == SentenceMemoryOrigin.AI }
            assertTrue(memory.confirm(ai.id))
            assertEquals(2, memory.loadPage(query = "서울").size)
            memory.delete(ai.id)
            assertEquals(1, memory.loadPage().size)
        }
        SentenceTranslationMemory(context, name).use { reopened ->
            assertEquals("We travel to Seoul.", reopened.lookup("ko-kr", "en", TranslationRegister.FORMAL, user.original)?.corrected)
        }
        context.deleteDatabase(name)
    }

    @Test fun plaintextKeyNeverPersistsAndPortableSettingsNeverContainCredentialsOrConsent() {
        val original = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(original) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = original.getSharedPreferences("cloud-regression-$name", mode)
        }
        val key = "synthetic-device-key-not-valid-for-any-api"
        val settings = DeveloperLabSettings(context)
        assertTrue(settings.setApiKey(key))
        assertTrue(DeveloperLabSettings(context).state.value.hasApiKey)
        val encrypted = context.getSharedPreferences("developer_lab_encrypted_credentials", Context.MODE_PRIVATE).all.values
        assertTrue(encrypted.isNotEmpty())
        assertTrue(encrypted.none { it.toString().contains(key) })
        settings.setCloudReviewEnabled(true)
        assertFalse(settings.portableOptions().cloudReviewEnabled)
        assertFalse(settings.portableOptions().toString().contains(key))
        settings.clearApiKey()
        assertFalse(DeveloperLabSettings(context).state.value.hasApiKey)
        original.deleteSharedPreferences("cloud-regression-developer_lab")
        original.deleteSharedPreferences("cloud-regression-developer_lab_encrypted_credentials")
    }

    @Test fun structuredRequestsSendOnlyCurrentTextPairAndRejectRefusalOrExtraContent() {
        val request = CloudReviewRequest(CloudReviewProvider.OPENAI, "gpt-5.4-mini", "ko", "en",
            TranslationRegister.FORMAL, "synthetic original", "synthetic draft", false)
        val openai = JSONObject(CloudReviewJson.request(request))
        assertFalse(openai.getBoolean("store"))
        assertTrue(openai.getJSONObject("text").getJSONObject("format").getBoolean("strict"))
        val pair = JSONObject(openai.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(setOf("original", "draft"), pair.keys().asSequence().toSet())
        assertEquals(2, pair.length())
        val contextual = JSONObject(CloudReviewJson.request(request.copy(contextBefore = "synthetic previous sentence", situation = "guided tour")))
        val contextualData = JSONObject(contextual.getJSONArray("input").getJSONObject(0).getString("content"))
        assertEquals(setOf("original", "draft", "preceding_context", "situation"), contextualData.keys().asSequence().toSet())
        assertEquals("synthetic previous sentence", contextualData.getString("preceding_context"))
        assertFalse(contextualData.has("related_examples"))
        val result = JSONObject().put("accepted", true).put("corrected", "synthetic corrected").toString()
        val response = JSONObject().put("status", "completed").put("output", JSONArray().put(JSONObject()
            .put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", result)))))
        assertEquals("synthetic corrected", CloudReviewJson.corrected(CloudReviewProvider.OPENAI, response.toString()))
        response.put("status", "incomplete")
        assertNull(CloudReviewJson.corrected(CloudReviewProvider.OPENAI, response.toString()))
        val google = JSONObject(CloudReviewJson.request(request.copy(provider = CloudReviewProvider.GOOGLE, modelId = "gemini-2.5-flash-lite")))
        // generateContent TextResponseFormat uses a protobuf enum, not a MIME string.
        // https://ai.google.dev/api/generate-content#TextResponseFormat
        assertEquals("APPLICATION_JSON", google.getJSONObject("generationConfig").getJSONObject("responseFormat").getJSONObject("text").getString("mimeType"))
        val googleResponse = JSONObject().put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP")
            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", result))))))
        assertEquals("synthetic corrected", CloudReviewJson.corrected(CloudReviewProvider.GOOGLE, googleResponse.toString()))
        assertNull(CloudReviewJson.corrected(CloudReviewProvider.GOOGLE, googleResponse.toString() + "{}"))
    }
}
