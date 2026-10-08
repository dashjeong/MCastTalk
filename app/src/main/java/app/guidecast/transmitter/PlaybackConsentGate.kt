package app.guidecast.transmitter

/** A cancelled or outdated Android consent result must never start a new capture. */
internal class PlaybackConsentGate {
    private data class Request(val inputId: Int?, val target: String?, val epoch: Long, val cancelled: Boolean = false)
    private var request: Request? = null

    fun begin(inputId: Int?, target: String?, epoch: Long = 0) {
        check(request == null)
        request = Request(inputId, target, epoch)
    }

    fun cancel() { request = request?.copy(cancelled = true) }

    fun consume(inputId: Int?, target: String?, epoch: Long = 0): Boolean {
        val pending = request
        request = null
        return pending != null && !pending.cancelled && pending.epoch == epoch &&
            pending.inputId == inputId && pending.target == target
    }
}
