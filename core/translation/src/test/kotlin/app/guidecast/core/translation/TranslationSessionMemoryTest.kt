package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSessionMemoryTest {

    @Test
    fun `initial state returns empty memory and empty pairs`() {
        val memory = BroadcastSessionBilingualMemory()
        val context = memory.buildContext("ko", "en")
        assertEquals("", context.memory)
        assertTrue(context.pairs.isEmpty())
    }

    @Test
    fun `records and builds valid json array for matching channel`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "안녕하세요", "Hello")
        val context = memory.buildContext("ko", "en")

        assertEquals(1, context.pairs.size)
        assertEquals("안녕하세요", context.pairs[0].sourceText)
        assertEquals("Hello", context.pairs[0].translatedText)
        assertEquals("[{\"source\":\"안녕하세요\",\"translation\":\"Hello\"}]", context.memory)
    }

    @Test
    fun `isolates memory strictly by source and target language`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "반갑습니다", "Nice to meet you")
        memory.record("ko", "ja", "반갑습니다", "初めまして")

        val enContext = memory.buildContext("ko", "en")
        assertEquals(1, enContext.pairs.size)
        assertEquals("Nice to meet you", enContext.pairs[0].translatedText)

        val jaContext = memory.buildContext("ko", "ja")
        assertEquals(1, jaContext.pairs.size)
        assertEquals("初めまして", jaContext.pairs[0].translatedText)

        val zhContext = memory.buildContext("ko", "zh")
        assertEquals("", zhContext.memory)
        assertTrue(zhContext.pairs.isEmpty())

        // Reverse direction is also isolated
        val reverseContext = memory.buildContext("en", "ko")
        assertEquals("", reverseContext.memory)
        assertTrue(reverseContext.pairs.isEmpty())
    }

    @Test
    fun `enforces maximum pairs limit of two by fifo eviction`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "첫 번째 문장", "First sentence")
        memory.record("ko", "en", "두 번째 문장", "Second sentence")
        memory.record("ko", "en", "세 번째 문장", "Third sentence")

        val context = memory.buildContext("ko", "en")
        assertEquals(2, context.pairs.size)
        assertEquals("두 번째 문장", context.pairs[0].sourceText)
        assertEquals("Second sentence", context.pairs[0].translatedText)
        assertEquals("세 번째 문장", context.pairs[1].sourceText)
        assertEquals("Third sentence", context.pairs[1].translatedText)
    }

    @Test
    fun `omits overlong single sentence whole without polluting memory`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "정상 문장", "Normal sentence")
        // Single sentence exceeding 200 chars
        val overlong = "가".repeat(201)
        memory.record("ko", "en", overlong, "Overlong")
        memory.record("ko", "en", "Overlong source", "A".repeat(201))

        val context = memory.buildContext("ko", "en")
        assertEquals(1, context.pairs.size)
        assertEquals("정상 문장", context.pairs[0].sourceText)
    }

    @Test
    fun `enforces aggregate raw characters limit of 400 by dropping older pairs`() {
        val memory = BroadcastSessionBilingualMemory()
        // Pair 1: 150 + 150 = 300 chars
        val s1 = "가".repeat(150)
        val t1 = "A".repeat(150)
        memory.record("ko", "en", s1, t1)

        // Pair 2: 60 + 60 = 120 chars. Total would be 420 > 400, so Pair 1 is evicted
        val s2 = "나".repeat(60)
        val t2 = "B".repeat(60)
        memory.record("ko", "en", s2, t2)

        val context = memory.buildContext("ko", "en")
        assertEquals(1, context.pairs.size)
        assertEquals(s2, context.pairs[0].sourceText)
    }

    @Test
    fun `drops older pair by whole sentence unit when formatted json exceeds ipc ceiling`() {
        // Test with tight IPC limit to verify unit-level eviction
        val memory = BroadcastSessionBilingualMemory(
            maxPairs = 2,
            maxTotalRawChars = 400,
            maxSingleRawChars = 200,
            maxIpcChars = 70, // One pair fits; the two-pair encoded array exceeds this budget.
        )
        // Pair 1: ~40 chars JSON
        memory.record("ko", "en", "단문 1", "Short 1")
        // Pair 2: two whole pairs exceed the 70-character JSON budget.
        memory.record("ko", "en", "단문 2", "Short 2")

        val context = memory.buildContext("ko", "en")
        // Older pair dropped as whole unit, keeping only the newest valid unit
        assertEquals(1, context.pairs.size)
        assertEquals("단문 2", context.pairs[0].sourceText)
        assertTrue(context.memory.length <= 70)
    }

    @Test
    fun `escapes special json characters properly`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "따옴표 \"와\\역슬래시", "Line 1\nLine 2\tTab")
        val context = memory.buildContext("ko", "en")

        assertEquals(
            "[{\"source\":\"따옴표 \\\"와\\\\역슬래시\",\"translation\":\"Line 1\\nLine 2\\tTab\"}]",
            context.memory,
        )
    }

    @Test
    fun `clear removes all references and prevents further recordings`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "문장", "Sentence")
        assertEquals(1, memory.buildContext("ko", "en").pairs.size)

        memory.clear()

        // Context is cleared
        val contextAfterClear = memory.buildContext("ko", "en")
        assertEquals("", contextAfterClear.memory)
        assertTrue(contextAfterClear.pairs.isEmpty())

        // Further records are ignored after clear
        memory.record("ko", "en", "새 문장", "New sentence")
        assertEquals("", memory.buildContext("ko", "en").memory)
        assertTrue(memory.buildContext("ko", "en").pairs.isEmpty())
    }

    @Test
    fun `ignores blank or empty texts`() {
        val memory = BroadcastSessionBilingualMemory()
        memory.record("ko", "en", "", "Valid")
        memory.record("ko", "en", "Valid", "   ")
        memory.record("ko", "en", "   ", "   ")

        val context = memory.buildContext("ko", "en")
        assertEquals("", context.memory)
        assertTrue(context.pairs.isEmpty())
    }
}
