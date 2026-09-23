package app.mcasttalk.windows.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatJsonTest {
    @Test
    fun parsesEscapedFlatControlObject() {
        val value = parseFlatJsonObject(
            """{"type":"JOIN_ROOM","displayName":"A\nB","count":12}"""
        )

        assertEquals("JOIN_ROOM", value.requiredString("type"))
        assertEquals("A\nB", value.requiredString("displayName"))
        assertEquals(12, value.requiredInt("count"))
    }

    @Test
    fun rejectsDuplicateAndNestedValues() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFlatJsonObject("""{"type":"PING","type":"JOIN_ROOM"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFlatJsonObject("""{"type":"PING","payload":{"admin":true}}""")
        }
    }

    @Test
    fun rejectsOversizedMessagesAndTrailingContent() {
        assertThrows(IllegalArgumentException::class.java) {
            parseFlatJsonObject("{\"type\":\"PING\"}", maxChars = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseFlatJsonObject("""{"type":"PING"} true""")
        }
    }

    @Test
    fun encodesTrustedResponsesWithJsonEscaping() {
        val encoded = encodeJson(
            linkedMapOf(
                "message" to "quote=\" newline=\n",
                "values" to listOf(true, null, 3),
            )
        )

        assertTrue(encoded.contains("\\\""))
        assertTrue(encoded.contains("\\n"))
        assertEquals(
            "{\"message\":\"quote=\\\" newline=\\n\",\"values\":[true,null,3]}",
            encoded,
        )
    }
}
