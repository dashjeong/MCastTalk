package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GemmaStreamingAllocationRegressionTest {
    @Test
    fun `parser reads growing buffer without materializing a complete snapshot`() {
        val buffer = StringBuilder("{\"translation\":\"Welcome")
        val view = object : CharSequence {
            override val length: Int get() = buffer.length
            override fun get(index: Int): Char = buffer[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                buffer.subSequence(startIndex, endIndex)
            override fun toString(): String = error("Full output snapshot is unnecessary")
        }
        assertNull(completeGemmaJsonTranslation(view))
        buffer.append(" to the peace walk.\"}")
        assertEquals("Welcome to the peace walk.", completeGemmaJsonTranslation(view))
    }

    @Test
    fun `cumulative snapshots repeated snapshots and deltas preserve exact output`() {
        val output = StringBuilder()
        output.mergeLiteRtChunk("{\"translation\":\"")
        output.mergeLiteRtChunk("{\"translation\":\"안녕")
        output.mergeLiteRtChunk("{\"translation\":\"안녕")
        output.mergeLiteRtChunk("하세요.\"}")
        output.mergeLiteRtChunk("")
        assertEquals("{\"translation\":\"안녕하세요.\"}", output.toString())
        assertEquals("안녕하세요.", completeGemmaJsonTranslation(output))
    }

    @Test
    fun `each cumulative prefix matches prior merge behavior including unicode escapes`() {
        val expected = "{\"translation\":\"こんにちは。 \\uD83D\\uDE00 \\\"Peace\\\"\"}"
        val output = StringBuilder()
        for (end in 1..expected.length) {
            val chunk = expected.substring(0, end)
            output.mergeLiteRtChunk(chunk)
            assertEquals(chunk, output.toString())
            assertEquals(completeGemmaJsonTranslation(chunk), completeGemmaJsonTranslation(output))
        }
        assertEquals("こんにちは。 😀 \"Peace\"", completeGemmaJsonTranslation(output))
    }
}
