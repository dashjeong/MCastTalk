package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaTranslationPromptTest {
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
