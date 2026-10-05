package app.guidecast.transmitter

import org.junit.Assert.*
import org.junit.Test

class BoundedDomainLexicalIndexTest {
    @Test fun frequentTokenCannotVisitTheWholeCorpus() {
        val index = BoundedDomainLexicalIndex((0 until 10000).map { setOf("common", "term$it") })
        assertEquals(32, index.candidates(setOf("common")).size)
        assertArrayEquals(intArrayOf(9999), index.candidates(setOf("term9999")))
    }
    @Test fun unionHasAHardCandidateBudgetAcrossManyPostings() {
        val tokens = (0 until 64).map { "token$it" }
        val index = BoundedDomainLexicalIndex(tokens.flatMap { token -> List(32) { setOf(token) } })
        assertEquals(256, index.candidates(tokens.toSet()).size)
    }
    @Test fun inputTokensHaveAHardBudgetAndCandidatesAreDeduplicated() {
        val index = BoundedDomainLexicalIndex(listOf(setOf("a", "b"), setOf("c")), tokenLimit = 2)
        assertArrayEquals(intArrayOf(0), index.candidates(linkedSetOf("a", "b", "c")))
    }
    @Test fun unrelatedInputReturnsNoExamplesAndLookupsCannotMutateIndex() {
        val index = BoundedDomainLexicalIndex(listOf(setOf("topic")))
        assertEquals(0, index.candidates(setOf("else")).size)
        val result = index.candidates(setOf("topic")); result[0] = 999
        assertArrayEquals(intArrayOf(0), index.candidates(setOf("topic")))
    }
}
