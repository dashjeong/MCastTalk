package app.guidecast.transmitter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Recognizable-key regression matrix; no real credentials or external calls. */
class CloudTextPrivacyBoundaryRegressionTest {
    @Test fun providerKeyShapesAreRecognizedWithAndWithoutLeadingDelimiters() {
        val keys = listOf("sk-" + "a".repeat(20), "AIza" + "a".repeat(30))
        for (key in keys) for (prefix in listOf("", " ", "\n", "x", "_", "문서")) {
            assertTrue("Credential-shaped fixture was not recognized", containsNativeContextCredentialLikeText(prefix + key))
        }
    }

    @Test fun ordinaryWordsAndShortKeyLikeFragmentsRemainAllowed() {
        for (value in listOf("skill", "sketch", "mask", "Semiconductor lecture", "task-abc123",
            "sk-" + "a".repeat(19), "xsk-" + "a".repeat(19),
            "AIza" + "a".repeat(29), "xAIza" + "a".repeat(29))) {
            assertFalse("Ordinary/short fixture was incorrectly blocked", containsNativeContextCredentialLikeText(value))
        }
    }

    @Test fun minimumLengthsCharacterRangesAndOtherPatternsStayUnchanged() {
        assertTrue(containsNativeContextCredentialLikeText("sk-" + "a_-".repeat(7)))
        assertTrue(containsNativeContextCredentialLikeText("AIza" + "a_-".repeat(10)))
        assertFalse(containsNativeContextCredentialLikeText("sk-" + "a".repeat(19) + ".b"))
        assertFalse(containsNativeContextCredentialLikeText("AIza" + "a".repeat(29) + ".b"))
        assertTrue(containsNativeContextCredentialLikeText("-----BEGIN " + "PRIVATE KEY-----"))
        assertTrue(containsNativeContextCredentialLikeText("Authorization: Bearer abcdefgh"))
        assertTrue(containsNativeContextCredentialLikeText("api_key=abcdefgh"))
        assertTrue(containsNativeContextCredentialLikeText("password=abcdefgh"))
        assertFalse(containsNativeContextCredentialLikeText("password=abcdefg"))
        // Assignment-label matching keeps its existing word-boundary policy.
        assertFalse(containsNativeContextCredentialLikeText("xpassword=abcdefgh"))
    }

    @Test fun gluedProviderKeysAreExcludedBeforeReferenceTruncationAndDirectSetup() {
        for (key in listOf("xsk-" + "a".repeat(30), "xAIza" + "a".repeat(30))) {
            assertTrue(containsNativeContextCredentialLikeText(key))
            assertFalse(validInterpreterDomain(key))
            assertFalse(validInterpreterInstructions(key))
            assertEquals("" to 0, nativeReferencePayload(listOf(NativeReferenceEntry("Terms", "TERMS", key))))
            assertEquals("" to 0, nativeReferencePayload(listOf(NativeReferenceEntry(key, "TERMS", "Safe text"))))
            val references = JSONObject().put("references", JSONArray().put(JSONObject()
                .put("title", "Terms").put("kind", "TERMS").put("text", key))).toString()
            assertThrows(IllegalArgumentException::class.java) {
                geminiLiveSetup(GEMINI_LIVE_AGENT, "en", references = references)
            }
            assertThrows(IllegalArgumentException::class.java) {
                openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", references = references)
            }
            assertThrows(IllegalArgumentException::class.java) {
                geminiLiveSetup(GEMINI_LIVE_AGENT, "en", interpreterInstructions = key)
            }
            assertThrows(IllegalArgumentException::class.java) {
                openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", interpreterInstructions = key)
            }
        }
    }
}
