package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regression checks. No provider connection or microphone input. */
class NativeContextBoundaryRegressionTest {
    private fun references(count: Int): String = JSONObject().put("references", JSONArray().also { rows ->
        repeat(count) { rows.put(JSONObject().put("title", "Terms").put("kind", "TERMS").put("text", "42")) }
    }).toString()

    @Test fun directProviderSetupsAdmitAtMostFourReferenceEntries() {
        val maximum = references(4)
        val overflow = references(5)
        assertTrue(maximum.length <= 600)
        assertTrue(overflow.length <= 600)
        assertEquals(4, nativeReferencePreview(maximum).size)
        nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, "", "", maximum)
        geminiLiveSetup(GEMINI_LIVE_AGENT, "en", references = maximum)
        openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", references = maximum)
        assertThrows(IllegalArgumentException::class.java) { nativeReferencePreview(overflow) }
        assertThrows(IllegalArgumentException::class.java) {
            nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, "", "", overflow)
        }
        assertThrows(IllegalArgumentException::class.java) { geminiLiveSetup(GEMINI_LIVE_AGENT, "en", references = overflow) }
        assertThrows(IllegalArgumentException::class.java) {
            openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", references = overflow)
        }
    }

    @Test fun savedAndDirectDomainValidationShareUnicodeControlAndLengthLimits() {
        val valid = listOf("", "반도체 강의 😀", "a".repeat(300))
        for (value in valid) {
            assertTrue(validInterpreterDomain(value))
            assertTrue(validTranslationApiOptions(TranslationApiOptions(domainPrompt = value)))
            nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, value, "", "")
            geminiLiveSetup(GEMINI_LIVE_AGENT, "en", domainPrompt = value)
            openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", domain = value)
        }
        val invalid = listOf("bad\uD800", "bad\uDC00", "bad\uD800text", "bad\u0000", "bad\u007f",
            "bad\n", "bad\r", "bad\t", "a".repeat(301), "sk-" + "a".repeat(30))
        for (value in invalid) {
            assertFalse(validInterpreterDomain(value))
            assertFalse(validTranslationApiOptions(TranslationApiOptions(domainPrompt = value)))
            assertThrows(IllegalArgumentException::class.java) {
                nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, value, "", "")
            }
            assertThrows(IllegalArgumentException::class.java) { geminiLiveSetup(GEMINI_LIVE_AGENT, "en", domainPrompt = value) }
            assertThrows(IllegalArgumentException::class.java) {
                openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", domain = value)
            }
        }
    }

    @Test fun storedTextAndAllowedReferencesRequireConsentWhenTheyBecomeReachable() {
        val translate = TranslationApiOptions(provider = TranslationApiProvider.GEMINI_LIVE,
            model = GEMINI_LIVE_TRANSLATE, baseUrl = "https://generativelanguage.googleapis.com/v1beta")
        val agent = translate.copy(model = GEMINI_LIVE_AGENT, interpretationMode = OnlineInterpretationMode.PROFESSIONAL)
        assertFalse(nativeContextTransmissionExpands(translate, agent))
        assertTrue(nativeContextTransmissionExpands(translate.copy(domainPrompt = "Lecture"), agent.copy(domainPrompt = "Lecture")))
        assertTrue(nativeContextTransmissionExpands(translate.copy(interpreterInstructions = "Preserve abbreviations."),
            agent.copy(interpreterInstructions = "Preserve abbreviations.")))
        assertTrue(nativeContextTransmissionExpands(translate.copy(allowDomainReferences = true), agent.copy(allowDomainReferences = true)))
        assertFalse(nativeContextTransmissionExpands(agent.copy(domainPrompt = "Lecture"), translate.copy(domainPrompt = "Lecture")))
        assertFalse(nativeContextTransmissionExpands(agent, agent.copy(domainPrompt = "Lecture")))
        // A saved domain is excluded in CONTINUOUS mode; it does not enlarge the actual wire data.
        assertFalse(nativeContextTransmissionExpands(translate.copy(domainPrompt = "Lecture"),
            agent.copy(domainPrompt = "Lecture", interpretationMode = OnlineInterpretationMode.CONTINUOUS)))
        val openAi = TranslationApiOptions(provider = TranslationApiProvider.OPENAI_REALTIME,
            model = "gpt-realtime-2.1-mini", realtimeAudio = true, interpreterInstructions = "Preserve abbreviations.")
        assertFalse(nativeContextTransmissionExpands(openAi, openAi.copy(model = "gpt-realtime-2")))
    }
}
