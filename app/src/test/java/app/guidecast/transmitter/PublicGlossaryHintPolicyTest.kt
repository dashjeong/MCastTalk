package app.guidecast.transmitter

import app.guidecast.core.translation.GlossaryTerm
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicGlossaryHintPolicyTest {
    private val publicTerm = GlossaryTerm("ko", "en", "정전", "power outage")

    @Test fun ambiguousOrMilitarySourceDoesNotForceAnElectricalSense() {
        listOf("우리는 여전히 정전이라는 이름의 시간을 살고 있습니다.",
            "전쟁은 끝나지 않았고 정전이 이어집니다.", "정전 때문에 멈췄습니다.").forEach {
            assertFalse(it, PublicGlossaryHintPolicy.permits(it, publicTerm, false))
        }
    }

    @Test fun explicitCurrentElectricalEventsKeepTheHint() {
        listOf("정전 때문에 신호등이 꺼졌습니다.", "그런데 어젯밤 정전 때문에 냉장고가 멈췄습니다.",
            "전력 공급 장애로 정전이 발생했습니다.", "정전으로 전원 공급이 중단됐습니다.").forEach {
            assertTrue(it, PublicGlossaryHintPolicy.permits(it, publicTerm, false))
        }
    }

    @Test fun otherHomonymsDoNotEstablishAnElectricalSense() {
        listOf("정전으로 총성이 멎자 병사들이 전원 귀환했습니다.",
            "장군의 전기는 정전 직전의 상황을 다룹니다.",
            "정전 체결에 전력을 다했습니다.", "정전 협상의 성과를 조명합니다.").forEach {
            assertFalse(it, PublicGlossaryHintPolicy.permits(it, publicTerm, false))
        }
    }

    @Test fun mixedQuotedAndMetalinguisticSensesAbstain() {
        listOf("군사적 정전과 전기 정전을 비교합니다.", "전기 공급 중단이 아니라 군사적 정전입니다.",
            "'정전'이라는 전기 용어의 뜻입니다.", "정전이라는 말의 의미는 전기가 아닙니다.").forEach {
            assertFalse(it, PublicGlossaryHintPolicy.permits(it, publicTerm, false))
        }
    }

    @Test fun userOverridesAndOtherEntriesAreNeverFilteredByThisPolicy() {
        assertTrue(PublicGlossaryHintPolicy.permits("정전이 이어집니다.", publicTerm, true))
        assertTrue(PublicGlossaryHintPolicy.permits("정전이 이어집니다.", publicTerm.copy(preferredTerm = "armistice"), false))
        assertTrue(PublicGlossaryHintPolicy.permits("정전기 실험입니다.", publicTerm.copy(sourceTerm = "정전기"), false))
        assertTrue(PublicGlossaryHintPolicy.permits("평화를 바랍니다.", publicTerm.copy(sourceTerm = "평화"), false))
    }
}
