package app.guidecast.transmitter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenQuantityReviewTest {
    @Test fun sourceQuantityCanRepairADraftButCannotBeRemovedOrChanged() {
        val source = "계약은 이십삼 개월입니다."
        val draft = "The contract lasts 3 months."
        assertTrue(conservativeReviewAccepted(source, draft, "The contract lasts 23 months.", "en"))
        assertFalse(conservativeReviewAccepted(source, draft, "The contract lasts 3 months.", "en"))
        assertFalse(conservativeReviewAccepted(source, draft, "The contract continues.", "en"))
    }
}
