package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictTranslationBatchJsonTest {

    @Test
    fun testValidFourLanguagesWithEscapesWhitespaceAndOrder() {
        val expectedTargets = listOf("ja", "en", "ru", "zh")
        val json = """{
            "zh" : "注意，广播即将开始。\u4e16\u754c", 
            "ru" : "Внимание, трансляция скоро начнется. Цитата: \"Старт\" \\ Путь: a\/b", 
            "en" : "Attention, \"broadcast\" will begin \\ soon. Slash: a\/b", 
            "ja" : "まもなく放送が始まります。"
        }"""

        val result = parseStrictTranslationBatch(json, expectedTargets)

        assertEquals(4, result.size)
        assertEquals("Attention, \"broadcast\" will begin \\ soon. Slash: a/b", result["en"])
        assertEquals("まもなく放送が始まります。", result["ja"])
        assertEquals("注意，广播即将开始。世界", result["zh"])
        assertEquals("Внимание, трансляция скоро начнется. Цитата: \"Старт\" \\ Путь: a/b", result["ru"])
        assertEquals(expectedTargets, result.keys.toList())
    }

    @Test
    fun testValidSurrogatePair() {
        val expectedTargets = listOf("en")
        val json = """{"en": "Message \uD83D\uDE00 with emoji \uD83C\uDF0D"}"""
        val result = parseStrictTranslationBatch(json, expectedTargets)
        assertEquals("Message \uD83D\uDE00 with emoji \uD83C\uDF0D", result["en"])
    }

    @Test
    fun testNegativeDuplicateRawKey() {
        val json = """{"en": "first", "ja": "hai", "en": "second"}"""
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(json, listOf("en", "ja")) }
    }

    @Test
    fun testNegativeDuplicateEscapedKey() {
        val json = """{"en": "first", "\u0065n": "second"}"""
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(json, listOf("en")) }
    }

    @Test
    fun testNegativeDuplicateExpectedTargets() {
        val json = """{"en": "hello"}"""
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(json, listOf("en", "en")) }
    }

    @Test
    fun testNegativeExtraKey() {
        val json = """{"en": "hello", "fr": "bonjour"}"""
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(json, listOf("en")) }
    }

    @Test
    fun testNegativeMissingKey() {
        val json = """{"en": "hello"}"""
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(json, listOf("en", "ja")) }
    }

    @Test
    fun testNegativeNonStringValues() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": 12345}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": null}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": true}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": false}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": ["hello"]}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": {"sub": "val"}}""", listOf("en")) }
    }

    @Test
    fun testNegativeBlankValue() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": ""}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "   \t   "}""", listOf("en")) }
    }

    @Test
    fun testNegativeDecodedAndControlCharacters() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello\u0000world"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello\u001fworld"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello\u007fworld"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello\nworld"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello\tworld"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{\"en\": \"hello\nworld\"}", listOf("en")) }
    }

    @Test
    fun testNegativeSyntaxErrors() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello"""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en" "hello"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello",}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello"}{"en": "world"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "hello"} trailing""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "bad \x escape"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "bad \' escape"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "bad \u123 escape"}""", listOf("en")) }
    }

    @Test
    fun testNegativeNonJsonWhitespace() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{\u00A0\"en\": \"hello\"}", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{\"en\": \u000C\"hello\"}", listOf("en")) }
    }

    @Test
    fun testNegativeSurrogates() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "bad \uD83D alone"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "bad \uDE00 alone"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en": "bad \uD83D\u0041 pair"}""", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{\"en\": \"bad \uD83D alone\"}", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{\"en\": \"bad \uDE00 alone\"}", listOf("en")) }
    }

    @Test
    fun testLengthBoundsExactAndPlusOne() {
        val exact8000 = "a".repeat(8000)
        val json8000 = """{"en": "$exact8000"}"""
        val res8000 = parseStrictTranslationBatch(json8000, listOf("en"))
        assertEquals(8000, res8000["en"]?.length)

        val over8001 = "a".repeat(8001)
        val json8001 = """{"en": "$over8001"}"""
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(json8001, listOf("en")) }

        val baseJson = """{"en": "valid phrase"}"""
        val exact32000 = baseJson + " ".repeat(32000 - baseJson.length)
        assertEquals(32000, exact32000.length)
        val res32000 = parseStrictTranslationBatch(exact32000, listOf("en"))
        assertEquals("valid phrase", res32000["en"])

        val over32001 = baseJson + " ".repeat(32001 - baseJson.length)
        assertEquals(32001, over32001.length)
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch(over32001, listOf("en")) }
    }

    @Test
    fun testNoPayloadInErrorMessage() {
        val secret = "SENSITIVE_SYNTHETIC_PAYLOAD_TOKEN_778899"
        val jsonWithSecret = """{"en": "$secret", "en": "duplicate"}"""

        try {
            parseStrictTranslationBatch(jsonWithSecret, listOf("en"))
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            val message = e.message ?: ""
            assertFalse("Error message must not contain raw payload", message.contains(secret))
        }
    }

    @Test fun emptyAndInvalidExpectedTargetsAreRejected() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{}", emptyList()) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{}", listOf("not a language")) }
    }

    @Test fun c1ControlsAreRejectedInRawAndEscapedStrings() {
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("{\"en\":\"x\u0085y\"}", listOf("en")) }
        assertThrowsIllegalArgumentException { parseStrictTranslationBatch("""{"en":"x\u0085y"}""", listOf("en")) }
    }

    private fun assertThrowsIllegalArgumentException(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException was not thrown")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }
}
