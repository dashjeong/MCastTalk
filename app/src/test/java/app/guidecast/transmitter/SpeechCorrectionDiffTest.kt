package app.guidecast.transmitter

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechCorrectionDiffTest {
    @Test fun highlightsChangedSpanAndKeepsUnicodeWhole() {
        assertEquals(SpeechCorrectionChange("디엠지 평", "아", "화", " 걷기"),
            speechCorrectionChange("디엠지 평아 걷기", "디엠지 평화 걷기"))
        assertEquals(SpeechCorrectionChange("", "😀", "😁", " 안내"),
            speechCorrectionChange("😀 안내", "😁 안내"))
        assertEquals(SpeechCorrectionChange("안내", "", "입니다", ""), speechCorrectionChange("안내", "안내입니다"))
        assertEquals(SpeechCorrectionChange("", "안", "", "내"), speechCorrectionChange("안내", "내"))
        assertEquals(SpeechCorrectionChange("안내", "", "", ""), speechCorrectionChange("안내", "안내"))
    }
}
