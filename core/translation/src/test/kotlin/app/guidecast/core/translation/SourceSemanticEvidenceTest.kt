package app.guidecast.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSemanticEvidenceTest {
    @Test fun fusedAudiencePronounDoesNotBecomePersonCountEvidence() {
        listOf("여러분께서 여기 계십니다.", "여러분이 지금 서 계십니다.").forEach {
            assertTrue(SourceSemanticEvidence.classify("ko", it).isEmpty())
        }
    }
    @Test fun localHonorificPersonSubjectsCarryOnlyTypedGrammaticalEvidence() {
        listOf(
            "몇 분이 계속 서 계셔가지고 제가 좀 마음이 불편했습니다.",
            "여러 분께서 입구에 서 계셔서 의자를 더 가져왔습니다.",
            "네 분이 아직 복도에 계셔서 안내원이 모시러 갔습니다.",
            "두분께서 앉아 계십니다.",
            "회의실에는 몇 분이 계신가요?",
            "한 분이 저기 계실 겁니다.",
        ).forEach { original ->
            assertEquals(original, setOf(SourceSemanticCue.KOREAN_HONORIFIC_PERSON_SUBJECT),
                SourceSemanticEvidence.classify("Korean", original))
        }
    }

    @Test fun timeAndDifferentSubjectsDoNotInheritAnUnrelatedHonorific() {
        assertAbstains(
            "선생님께서 몇 분 동안 문 앞에 서 계셨습니다.",
            "몇 분이 지나자 원장님께서 방에 들어오셨습니다.",
            "몇 분이 지나기에 선생님께서 서 계셨습니다.",
            "몇 분이 소요되어도 선생님께서 계십니다.",
            "몇 분은 선생님이 서 계셨고 그 뒤에는 앉으셨습니다.",
            "몇 분이 선생님께서 서 계신 시간을 나타냅니다.",
            "몇 분이 지나갔는지 모르지만 어르신들이 계십니다.",
            "몇 분이 남았고 선생님께서 서 계셨습니다.",
            "십 분이 흐른 뒤 선생님께서 오셨습니다.",
            "몇 분이 지나고 몇 분이 서 계셨다.",
            "몇 분이 서 계셨고 몇 분 동안 기다리셨습니다.",
            "두 분이 서 계신 지 3분이 되었습니다.",
        )
    }

    @Test fun unsupportedClausesAndQuotedGrammarAbstainInsteadOfGuessing() {
        assertAbstains(
            "‘몇 분이’라는 표현을 적어 놓고 선생님께서 기다리고 계셨습니다.",
            "\"몇 분이 서 계십니다\"라고 쓰세요.",
            "몇 분이 갔습니다. 선생님께서 계십니다.",
            "몇 분이, 선생님께서 계신 곳을 떠났습니다.",
            "몇 분이\n서 계십니다.",
            "몇 분이 서 계란을 보고 있습니다.",
            "몇 분이 참석했습니다.",
            "몇 분이 도착하면 선생님께서 서 계실 겁니다.",
            "몇 분이 정말 오랫동안 함께 조용히 서 계십니다.",
        )
    }

    @Test fun languageSizeAndWholeTokenBoundariesAreEnforced() {
        val source = "두 분이 서 계십니다."
        assertTrue(SourceSemanticEvidence.classify("ko-KR", source).isNotEmpty())
        assertTrue(SourceSemanticEvidence.classify("KO_kr", source).isNotEmpty())
        assertTrue(SourceSemanticEvidence.classify("Japanese", source).isEmpty())
        assertTrue(SourceSemanticEvidence.classify("", source).isEmpty())
        assertAbstains("한두 분이 서 계십니다.", "스물세 분이 서 계십니다.", "가".repeat(601) + " " + source, "")
    }

    @Test fun durationSuffixesNeverForceAnOrdinalInterpretation() {
        assertAbstains(
            "아홉 해째 지금도 매일 수업합니다.",
            "연구 4년째지만 만 3년만 지났습니다.",
            "방송을 제가 한 지가 지금 56년째 하고 있습니다.",
        )
    }

    @Test fun compoundTimeQuantitiesAndAdditionalBunWordsPreventGlobalHints() {
        assertAbstains(
            "몇 분이 서 계셨고 삼십 분이 남아 있었습니다.",
            "몇 분이 서 계셨고 수십 분이 남아 있었습니다.",
            "몇 분이 서 계셨고 스물 분이 남아 있었습니다.",
            // This conservative false negative is intentional: never guess the role of a
            // second 분 while presenting one global grammatical hint to the model.
            "분위기가 조용한데 몇 분이 서 계십니다.",
        )
    }

    @Test fun nounPrefixesAndUnknownInflectionsAreNotHonorificPredicates() {
        assertAbstains(
            "몇 분이 계시록을 읽고 있습니다.",
            "몇 분이 계시판을 보고 있습니다.",
            "몇 분이 계시록입니다.",
            "몇 분이 계신학을 연구합니다.",
        )
    }

    @Test fun evidenceRangesReferToUnmodifiedQuantifierClassifierAndParticle() {
        val original = "지금  두\t분께서 앉아 계십니다."
        val evidence = requireNotNull(SourceSemanticEvidence.humanSubject("ko", original))
        assertEquals("두\t분께서", original.substring(evidence.subjectRange))
        assertEquals("분", original.substring(evidence.classifierRange))
        assertEquals("두", evidence.quantifier)
        assertEquals("께서", evidence.particle)
    }

    private fun assertAbstains(vararg originals: String) {
        originals.forEach { assertEquals(it, emptySet<SourceSemanticCue>(), SourceSemanticEvidence.classify("ko", it)) }
    }
}
