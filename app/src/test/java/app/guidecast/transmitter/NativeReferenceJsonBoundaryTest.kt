package app.guidecast.transmitter

import app.guidecast.core.translation.TranslationStyle
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class NativeReferenceJsonBoundaryTest {
    private val safe = "{\"references\":[{\"title\":\"Terms\",\"kind\":\"TERMS\",\"text\":\"수율 = yield\"}]}"
    @Test fun trailingCredentialCannotHideOutsideTheParsedReferenceObject() {
        val input = safe + " /*" + "sk-" + "a".repeat(30) + "*/"
        assertThrows(IllegalArgumentException::class.java) {
            nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, "", "", input)
        }
    }

    @Test fun duplicateDecodedFieldsAndRootKeysAreRejectedBeforeSerialization() {
        for (raw in listOf(
            safe.replace("\"title\":\"Terms\"", "\"title\":\"Earlier\",\"t\\u0069tle\":\"Terms\""),
            safe.dropLast(1) + ",\"references\":[]}",
            safe.replace("\"text\":", "\"other\":\"ignored\",\"text\":"))) {
            val error = assertThrows(IllegalArgumentException::class.java) { strictNativeReferenceJson(raw) }
            assertEquals("Invalid native reference data", error.message); assertNull(error.cause)
        }
    }

    @Test fun recognizedCredentialEscapesAreCheckedAfterDecodingInEveryField() {
        val credential = "\\u0073\\u006b-" + "a".repeat(30)
        for (field in listOf("title", "kind", "text")) {
            val original = if (field == "title") "Terms" else if (field == "kind") "TERMS" else "수율 = yield"
            val raw = safe.replace("\"$field\":\"$original\"", "\"$field\":\"$credential\"")
            val error = assertThrows(IllegalArgumentException::class.java) { strictNativeReferenceJson(raw) }
            assertEquals("Invalid native reference data", error.message); assertNull(error.cause)
        }
    }

    @Test fun permissiveSyntaxRawControlsMalformedUnicodeAndTrailingDataCannotEnterSetup() {
        for (raw in listOf(safe + " ignored", safe.replace("[{", "[/* comment */{"),
            safe.replace("\"title\"", "'title'"), safe.replace("\"Terms\"", "Terms"),
            safe.replace("\"Terms\"", "\"bad\nname\""), safe.replace("Terms", "\\uD800"),
            safe.replace("Terms", "\\uDC00"), safe.replace("Terms", "\\q"), safe.replace("]}", ",]}"))) {
            assertThrows(IllegalArgumentException::class.java) { strictNativeReferenceJson(raw) }
        }
    }

    @Test fun safeEscapedTermsEmojiAndLineBreaksRoundTripAsValidatedData() {
        val text = "경로 = C:\\docs\n😀 = smile\n허용하지 않음 = do not allow 20 mg"
        val payload = nativeReferencePayload(listOf(NativeReferenceEntry("Terms 😀", "TERMS", text))).first
        val spaced = " \n" + payload.replace("\"title\"", "\"t\\u0069tle\"") + "\t"
        val validated = strictNativeReferenceJson(spaced)
        assertEquals("Terms 😀", nativeReferencePreview(validated).single().title)
        assertEquals(text, nativeReferencePreview(validated).single().text)
        val instructions = nativeInterpreterInstructions("en", TranslationStyle.CONVERSATIONAL, "", "", spaced)
        assertTrue(instructions.contains(validated)); assertFalse(instructions.contains(spaced))
    }

    @Test fun providerSetupsShareTheCompleteReferenceBoundaryWithoutLeakingErrorInput() {
        val invalid = safe + " /*" + "sk-" + "a".repeat(30) + "*/"
        val first = assertThrows(IllegalArgumentException::class.java) { geminiLiveSetup(GEMINI_LIVE_AGENT, "en", references = invalid) }
        val second = assertThrows(IllegalArgumentException::class.java) { openAiAudioSetup("gpt-realtime-2.1-mini", "ko", "en", references = invalid) }
        assertEquals("Invalid native reference data", first.message); assertEquals(first.message, second.message)
        assertNull(first.cause); assertNull(second.cause)
    }

    @Test fun unsafeLegacyControlsAreExcludedBeforeExcerptRatherThanBlockingAValidDocument() {
        val prepared = nativeReferencePayload(listOf(NativeReferenceEntry("bad\nname", "TERMS", "ignored"),
            NativeReferenceEntry("Safe terms", "TERMS", "수율 = yield")))
        assertEquals(1, prepared.second)
        assertEquals("Safe terms", nativeReferencePreview(prepared.first).single().title)
        assertEquals(1, JSONObject(strictNativeReferenceJson(prepared.first)).getJSONArray("references").length())
    }
}
