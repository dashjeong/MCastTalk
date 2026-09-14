package app.guidecast.transmitter

import app.guidecast.core.translation.SpeechExpressionProfile
import org.junit.Assert.*
import org.junit.Test

class ExperimentalSpeechProfileTest {
    @Test fun onlyExplicitDeveloperOptInAllowsExpressionAndRuntimeAlwaysRetainsPriority() {
        assertFalse(developerSpeechPreviewAllowed(false, true, true, true))
        assertFalse(developerSpeechPreviewAllowed(true, true, false, true))
        assertFalse(developerSpeechPreviewAllowed(true, true, true, false))
        assertTrue(developerSpeechPreviewAllowed(true, false, false, true))
        assertTrue(developerSpeechPreviewAllowed(true, true, true, true))
    }
    @Test fun registeredPunctuationRequestsStayBoundedWithoutInferringAudioEmotion() {
        for (register in TranslationRegister.entries) {
            for (text in listOf("안내합니다.", "도착했습니까?", "Welcome!", "안내 ".repeat(100))) {
                val result = deriveSpeechExpression(text, register)
                assertTrue(result.rate in 0.9f..1.1f)
                assertTrue(result.pitch in 0.9f..1.1f)
            }
        }
        assertTrue(deriveSpeechExpression("안내?", TranslationRegister.CONVERSATIONAL).pitch >
            deriveSpeechExpression("안내.", TranslationRegister.CONVERSATIONAL).pitch)
        assertEquals(SpeechExpressionProfile(0.95f, 1f), SpeechExpressionProfile.BASELINE)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidPitchCannotReachTheEngine() {
        SpeechExpressionProfile(1f, Float.NaN)
    }
}
