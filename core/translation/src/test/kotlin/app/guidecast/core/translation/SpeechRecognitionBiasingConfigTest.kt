package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechRecognitionBiasingConfigTest {
    @Test
    fun `biasing phrases default to empty`() {
        assertEquals(emptyList<String>(), SpeechRecognitionConfig("ko-KR").biasingPhrases)
    }

    @Test
    fun `normalizer returns NFC request snapshot`() {
        val decomposed = "Cafe\u0301"

        val normalized = normalizeSpeechRecognitionBiasingPhrases(listOf(decomposed))

        assertEquals(listOf("Caf\u00e9"), normalized)
    }

    @Test
    fun `contract accepts exact phrase count length and aggregate caps`() {
        val phrases = List(12) { "가".repeat(80) } + "나".repeat(40)

        val config = SpeechRecognitionConfig("ko-KR", biasingPhrases = phrases)

        assertEquals(13, config.biasingPhrases.size)
        assertEquals(1_000, config.biasingPhrases.sumOf { it.codePointCount(0, it.length) })
        SpeechRecognitionConfig("ko-KR", biasingPhrases = List(32) { "용어$it" })
    }

    @Test
    fun `contract rejects phrase count per phrase and aggregate overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRecognitionConfig("ko-KR", biasingPhrases = List(33) { "용어$it" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("가".repeat(81)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechRecognitionConfig(
                "ko-KR",
                biasingPhrases = List(12) { "가".repeat(80) } + "나".repeat(41),
            )
        }
    }

    @Test
    fun `contract counts Unicode code points rather than UTF16 units`() {
        SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("\ud83d\ude80".repeat(80)))

        assertThrows(IllegalArgumentException::class.java) {
            SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf("\ud83d\ude80".repeat(81)))
        }
    }

    @Test
    fun `contract rejects blank control and bidi override text`() {
        listOf("   ", "line\nbreak", "term\u202Ename", "broken\uD800").forEach { invalidPhrase ->
            assertThrows(IllegalArgumentException::class.java) {
                SpeechRecognitionConfig("ko-KR", biasingPhrases = listOf(invalidPhrase))
            }
        }
    }
}
