package app.guidecast.transmitter

/** Separate optional text requests, never native audio usage or an account billing receipt. */
internal data class DeferredTeacherRequestUsage(
    val dispatchedRequests: Int = 0,
    val maximumRequests: Int = 3,
    val lastPrompt: Long? = null,
    val lastCandidates: Long? = null,
    val lastThoughts: Long? = null,
    val lastTotal: Long? = null,
    val lastTotalsMatch: Boolean? = null,
    val budgetKnownUsd: String? = null,
    val budgetHeldUsd: String? = null,
    val message: String = "Live와 별도인 추가 문장 요청 · 청구액 미확인",
)
