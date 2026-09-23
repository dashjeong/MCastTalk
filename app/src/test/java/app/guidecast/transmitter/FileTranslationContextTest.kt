package app.guidecast.transmitter

import app.guidecast.core.translation.ContextualTextTranslationEngine
import app.guidecast.core.translation.TextTranslationEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileTranslationContextTest {
    @Test fun contextualEngineReceivesPriorMeaningSeparatelyFromCurrentText() = runTest {
        val calls = mutableListOf<List<String?>>()
        val engine = object : ContextualTextTranslationEngine {
            override suspend fun translateWithContext(text: String, contextBefore: String?,
                sourceLanguageTag: String, targetLanguageTag: String): String {
                calls += listOf(text, contextBefore, sourceLanguageTag, targetLanguageTag)
                return "The armistice is still in place."
            }
        }
        val result = translateFileChunkWithContext(engine, "정전 상태가 이어집니다.",
            "전쟁과 평화 협정을 설명했습니다.", "ko", "en")
        assertEquals(listOf(listOf("정전 상태가 이어집니다.", "전쟁과 평화 협정을 설명했습니다.", "ko", "en")), calls)
        assertEquals("The armistice is still in place.", result)
    }

    @Test fun nonContextualEngineNeverTranslatesPreviousSentenceAgain() = runTest {
        val calls = mutableListOf<String>()
        val engine = TextTranslationEngine { text, _, _ -> calls += text; "Only the current sentence." }
        assertEquals("Only the current sentence.", translateFileChunkWithContext(engine,
            "현재 문장", "앞의 문장은 참고입니다.", "ko", "en"))
        assertEquals(listOf("현재 문장"), calls)
    }

    @Test fun overBudgetSentenceIsOmittedWholeWithoutRemovingItsNegation() {
        assertNull(fileWholeTranslationContext(listOf("이전 내용", "허용하지 않습니다. " + "설명 ".repeat(120))))
        assertEquals("허용하지 않습니다.", fileWholeTranslationContext(listOf("x".repeat(300), "허용하지 않습니다.")))
    }

    @Test fun retryUsesPrecedingOriginalSegmentsAndChunkContextKeepsOrder() {
        val source = listOf(FileSpeechSegment(1, 0, 500, "첫 문장", "ko"),
            FileSpeechSegment(2, 500, 1000, "두 번째 문장", "ko"),
            FileSpeechSegment(3, 1000, 1500, "재시도 문장", "ko"))
        val prior = fileTranslationContexts(source)[source.last()]
        assertEquals("첫 문장 두 번째 문장", prior)
        assertEquals("첫 문장 두 번째 문장 앞쪽 구문", fileWholeTranslationContext(listOfNotNull(prior) + "앞쪽 구문"))
    }
}
