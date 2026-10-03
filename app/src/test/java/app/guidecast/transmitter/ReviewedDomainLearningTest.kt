package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class ReviewedDomainLearningTest {
    @Test fun approvalIsRequiredAndUnicodeSourceIsNormalized() {
        assertThrows(IllegalArgumentException::class.java) { reviewedDomainPair("회의", "Meeting", "en", false) }
        val pair = reviewedDomainPair("  가  ", "  Meeting  ", "en", true)
        assertEquals("가", pair.normalizedSource)
        assertEquals("Meeting", pair.corrected)
    }
    @Test fun invalidOrSensitiveExamplesCannotBecomeLocalReferences() {
        listOf("", "x".repeat(501), "line\nline", "token\tvalue", "sk-" + "a".repeat(50)).forEach { text ->
            assertThrows(text, IllegalArgumentException::class.java) { reviewedDomainPair("회의", text, "en", true) }
        }
        assertThrows(IllegalArgumentException::class.java) { reviewedDomainPair("회의", "한국어입니다", "en", true) }
    }
}
