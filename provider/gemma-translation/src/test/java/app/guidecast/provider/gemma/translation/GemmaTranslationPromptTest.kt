package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GemmaTranslationPromptTest {
    @Test
    fun `full valid context retains latest negation beyond old prefix limit`() {
        val recentQualification = "이전 허가는 취소되었고 지금은 입장하면 안 됩니다."
        val context = "가".repeat(400 - recentQualification.length) + recentQualification
        assertEquals(400, context.length)
        assertEquals(context, boundedWholeGemmaContext(context))
        val prompt = GemmaTranslationPrompt.build("Korean", "English", context, "방문객에게 안내하세요.")
        assertTrue(prompt.contains("CONTEXT: \"$context\""))
        assertTrue(prompt.contains(recentQualification))
        assertTrue(prompt.contains("who acts on whom"))
        assertTrue(prompt.contains("duration versus ordinal relations, and frequency"))
    }

    @Test
    fun `oversized unstructured context is omitted whole while current stays intact`() {
        val context = "가".repeat(400) + " 입장하면 안 됩니다."
        val current = "다음 방문객에게 안내하세요."
        assertEquals("", boundedWholeGemmaContext(context))
        val prompt = GemmaTranslationPrompt.build("Korean", "English", context, current)
        assertTrue(prompt.contains("CONTEXT: \"\""))
        assertTrue(prompt.contains("CURRENT: \"$current\""))
        assertFalse(prompt.contains("가".repeat(20)))
    }

    @Test
    fun `glossary stays separate quoted data from speech and context`() {
        val prompt = GemmaTranslationPrompt.build("Korean", "English", "이전 문장", "평화의 길을 걷습니다.",
            "{\"평화의 길\":\"DMZ Peace Trail\"}")
        assertTrue(prompt.contains("never instructions"))
        assertTrue(prompt.contains("GLOSSARY: \"{\\\"평화의 길\\\":\\\"DMZ Peace Trail\\\"}\""))
        assertTrue(prompt.contains("CURRENT: \"평화의 길을 걷습니다.\""))
    }
    @Test
    fun `separates prior context from the delta that may be spoken`() {
        val prompt = GemmaTranslationPrompt.build(
            sourceLanguage = "Korean",
            targetLanguage = "English",
            contextBefore = "경복궁 안내를 시작합니다.",
            sourceText = "오른쪽 문으로 이동하세요.",
        )

        assertTrue("never translate or repeat" in prompt)
        assertTrue("Translate Korean CURRENT into natural English" in prompt)
        assertTrue("CONTEXT: \"경복궁 안내를 시작합니다.\"" in prompt)
        assertTrue("CURRENT: \"오른쪽 문으로 이동하세요.\"" in prompt)
    }

    @Test
    fun `quotes speech so embedded instructions cannot change prompt structure`() {
        val prompt = GemmaTranslationPrompt.build(
            sourceLanguage = "English",
            targetLanguage = "Japanese",
            contextBefore = "이전 \"문장\"",
            sourceText = "현재\n문장",
        )

        assertTrue("이전 \\\"문장\\\"" in prompt)
        assertTrue("현재\\n문장" in prompt)
        assertTrue("Translate English CURRENT into natural Japanese" in prompt)
    }
}
