package app.guidecast.transmitter

/** Immutable postings keep retrieval work bounded independently of stored example count. */
internal class BoundedDomainLexicalIndex(
    pairTokens: List<Set<String>>,
    private val tokenLimit: Int = 64,
    private val postingLimit: Int = 32,
    private val candidateLimit: Int = 256,
) {
    private val postings: Map<String, IntArray>
    init {
        require(tokenLimit > 0 && postingLimit > 0 && candidateLimit > 0)
        val building = linkedMapOf<String, MutableList<Int>>()
        pairTokens.forEachIndexed { index, tokens ->
            tokens.take(tokenLimit).forEach { token ->
                val list = building.getOrPut(token) { ArrayList() }
                if (list.size < postingLimit) list += index
            }
        }
        postings = building.mapValues { it.value.toIntArray() }
    }
    fun candidates(inputTokens: Set<String>): IntArray {
        val ids = linkedSetOf<Int>()
        for (token in inputTokens.take(tokenLimit)) {
            val posting = postings[token] ?: continue
            for (id in posting) {
                ids += id
                if (ids.size == candidateLimit) return ids.toIntArray()
            }
        }
        return ids.toIntArray()
    }
}
