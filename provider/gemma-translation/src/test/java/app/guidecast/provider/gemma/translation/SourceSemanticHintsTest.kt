package app.guidecast.provider.gemma.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classifier cases live in core; these assertions cover only prompt rendering/delegation. */
class SourceSemanticHintsTest {
    @Test fun sharedEvidenceRendersWithoutRawSourceOrACannedTranslation() {
        val original = "몇 분이 계속 서 계셔가지고 제가 좀 마음이 불편했습니다."
        val hint = SourceSemanticHints.extract("Korean", original)
        assertTrue(hint.contains("honorific person-count classifier"))
        assertTrue(hint.contains("statement/question"))
        assertFalse(hint.contains(original))
        assertFalse(hint.contains("a few people"))
        assertEquals(hint, SourceSemanticHints.extract("ko-KR", original))
    }

    @Test fun rendererDoesNotRestoreCuesThatTheSharedClassifierDeclined() {
        listOf(
            "선생님께서 몇 분 동안 문 앞에 서 계셨습니다.",
            "몇 분이 서 계셨고 삼십 분이 남아 있었습니다.",
            "몇 분이 계시록을 읽고 있습니다.",
            "몇 분이 계시판을 보고 있습니다.",
        ).forEach { assertEquals(it, "", SourceSemanticHints.extract("ko", it)) }
        assertEquals("", SourceSemanticHints.extract("Japanese", "몇 분이 서 계십니다."))
    }
}
