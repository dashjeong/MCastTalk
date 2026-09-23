package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KoreanTranslationInputTest {
    @Test fun onlyTheConfirmedClassifierChangesAndQuestionsRemainQuestions() {
        mapOf(
            "몇 분이 계속 서 계셔가지고 제가 좀 마음이 불편했습니다." to
                "몇 사람이 계속 서 계셔가지고 제가 좀 마음이 불편했습니다.",
            "회의실에는 몇 분이 계신가요?" to "회의실에는 몇 사람이 계신가요?",
            "두분께서 앉아 계십니다." to "두사람께서 앉아 계십니다.",
            "지금  두\t분께서 앉아 계십니다." to "지금  두\t사람께서 앉아 계십니다.",
            "네 분이 아직 복도에 계셔서 안내원이 모시러 갔습니다." to
                "네 사람이 아직 복도에 계셔서 안내원이 모시러 갔습니다.",
            "여러 분께서 입구에 서 계셔서 의자를 더 가져왔습니다." to
                "여러 사람께서 입구에 서 계셔서 의자를 더 가져왔습니다.",
            "한 분이 저기 계실 겁니다." to "한 사람이 저기 계실 겁니다.",
        ).forEach { (original, expected) ->
            assertEquals(expected, KoreanTranslationInput.normalizeForTranslation(original, "ko"))
            // Strings retained by recognition/UI/review callers are not replaced or mutated.
            val evidence = requireNotNull(SourceSemanticEvidence.humanSubject("ko", original))
            assertEquals("분", original.substring(evidence.classifierRange))
        }
    }

    @Test fun timeQuotesMixedUsesNounsAndAudiencePronounsRemainUnchanged() {
        listOf(
            "선생님께서 몇 분 동안 문 앞에 서 계셨습니다.",
            "몇 분이 지나자 원장님께서 방에 들어오셨습니다.",
            "몇 분이 선생님께서 서 계신 시간을 나타냅니다.",
            "몇 분이 서 계셨고 삼십 분이 남아 있었습니다.",
            "몇 분이 서 계셨고 수십 분이 남아 있었습니다.",
            "몇 분이 서 계셨고 스물 분이 남아 있었습니다.",
            "분위기가 조용한데 몇 분이 서 계십니다.",
            "\"몇 분이 서 계십니다\"라고 쓰세요.",
            "몇 분이 계시록을 읽고 있습니다.",
            "몇 분이 계시판을 보고 있습니다.",
            "여러분께서 여기 계십니다.",
            "여러분이 지금 서 계십니다.",
            "한두 분이 서 계십니다.",
            "몇 분이 참석했습니다.",
        ).forEach { original ->
            assertEquals(original, KoreanTranslationInput.normalizeForTranslation(original, "ko"))
        }
    }

    @Test fun otherLanguagesAndUnsupportedSourceSizesDoNotReceivePersonRewrites() {
        val original = "몇 분이 서 계십니다."
        assertEquals(original, KoreanTranslationInput.normalizeForTranslation(original, "ja"))
        assertEquals(original, KoreanTranslationInput.normalizeForTranslation(original, ""))
        val oversized = "가".repeat(600) + " " + original
        assertEquals(oversized, KoreanTranslationInput.normalizeForTranslation(oversized, "ko"))
    }

    @Test fun numericNotationAndPersonEvidenceComposeWithoutChangingTheOriginal() {
        val original = "계약은 십팔 개월이며 여러 분께서 계십니다."
        assertEquals("계약은 18개월이며 여러 사람께서 계십니다.",
            KoreanTranslationInput.normalizeForTranslation(original, "ko"))
        assertEquals("계약은 십팔 개월이며 여러 분께서 계십니다.", original)
    }

    @Test fun wholeComposedOutputFitsTheBudgetOrTheExactOriginalIsReturned() {
        val original = "금액은 일억원이고 몇 분이 서 계십니다."
        val normalized = "금액은 100000000원이고 몇 사람이 서 계십니다."
        assertEquals(normalized, KoreanTranslationInput.normalizeForTranslation(original, "ko", normalized.length))
        assertEquals(original, KoreanTranslationInput.normalizeForTranslation(original, "ko", normalized.length - 1))
        assertEquals(original, KoreanTranslationInput.normalizeForTranslation(original, "ko", 0))
        assertThrows(IllegalArgumentException::class.java) {
            KoreanTranslationInput.normalizeForTranslation(original, "ko", -1)
        }
        val expandingAmount = "일억원 ".repeat(200)
        assertEquals(800, expandingAmount.length)
        assertEquals(expandingAmount, KoreanTranslationInput.normalizeForTranslation(expandingAmount, "ko", 2_000))
    }
}
