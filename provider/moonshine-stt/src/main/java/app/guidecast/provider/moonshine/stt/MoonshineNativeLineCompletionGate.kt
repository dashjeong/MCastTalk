package app.guidecast.provider.moonshine.stt

/**
 * SDK line IDs are opaque: never infer ordering or evict a final ID and accept it again later.
 * Capacity exhaustion ends this provider attempt explicitly; its owner can start a fresh session.
 */
internal class MoonshineNativeLineCompletionGate(private val maximumCompletedLines: Int = 65_536) {
    private val completed = HashSet<Long>()

    init { require(maximumCompletedLines in 1..65_536) }

    @Synchronized
    fun shouldAccept(lineId: Long, isFinal: Boolean): Boolean {
        if (lineId in completed) return false
        if (isFinal) {
            check(completed.size < maximumCompletedLines) {
                "Moonshine 완료 발화 식별자 보존 상한에 도달해 인식 세션을 새로 연결해야 합니다."
            }
            completed += lineId
        }
        return true
    }

    @Synchronized
    fun clear() = completed.clear()
}
