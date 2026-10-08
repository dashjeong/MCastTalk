package app.guidecast.transmitter

import org.json.JSONObject

internal enum class GeminiLiveRenewalFailureCode { INVALID_NOTICE, DEADLINE, UNSAFE_CHECKPOINT, OVERFLOW, ROTATION_LIMIT }
internal class GeminiLiveRenewalFailure(val code: GeminiLiveRenewalFailureCode) :
    IllegalStateException("Live renewal stopped: ${code.name}")
internal data class GeminiLiveRenewalNotice(val connectionIndex: Int, val timeLeftMillis: Long)

/** Handles are memory-only capability values. Do not render their contents. */
internal class GeminiLiveResumptionUpdate(val resumable: Boolean, val handle: String?) {
    override fun toString(): String = "GeminiLiveResumptionUpdate(resumable=$resumable,handlePresent=${handle != null})"
}

internal fun geminiGoAwayMillis(value: JSONObject): Long {
    val duration = value.opt("timeLeft") as? String ?: throw GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.INVALID_NOTICE)
    if (!duration.matches(Regex("[0-9]{1,5}(?:\\.[0-9]{1,9})?s")))
        throw GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.INVALID_NOTICE)
    val seconds = duration.removeSuffix("s").toDoubleOrNull()
    if (seconds == null || !seconds.isFinite() || seconds <= 2.0 || seconds > 86_400.0)
        throw GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.INVALID_NOTICE)
    return (seconds * 1_000.0).toLong()
}

internal fun geminiResumptionUpdate(value: JSONObject): GeminiLiveResumptionUpdate {
    val resumable = value.opt("resumable") as? Boolean ?: false
    if (!resumable) return GeminiLiveResumptionUpdate(false, null)
    val handle = value.opt("newHandle") as? String
    if (handle.isNullOrBlank() || handle.length > 8_192 || handle.any(Char::isISOControl))
        throw GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.UNSAFE_CHECKPOINT)
    return GeminiLiveResumptionUpdate(true, handle)
}

internal enum class GeminiLiveRenewalDeliveryState { INITIAL_QUIET, ACTIVE_UNCONFIRMED, DELIVERED_COMPLETE }

/** Only observed quiet/completed state seeds a boundary; its old handle never crosses that boundary. */
internal class GeminiLiveRenewalCheckpoint(
    private var delivery: GeminiLiveRenewalDeliveryState = GeminiLiveRenewalDeliveryState.INITIAL_QUIET) {
    private var handle: String? = null
    fun delivered(event: GeminiLiveEvent) {
        if (event.interrupted) { delivery = GeminiLiveRenewalDeliveryState.ACTIVE_UNCONFIRMED; handle = null }
        else if (event.finished) { delivery = GeminiLiveRenewalDeliveryState.DELIVERED_COMPLETE; handle = null }
        else if (!event.source.isNullOrBlank() || !event.translation.isNullOrBlank() || event.audio.isNotEmpty()) {
            delivery = GeminiLiveRenewalDeliveryState.ACTIVE_UNCONFIRMED; handle = null
        }
        event.resumptionUpdate?.let { update ->
            if (!update.resumable) { delivery = GeminiLiveRenewalDeliveryState.ACTIVE_UNCONFIRMED; handle = null }
            else if (delivery != GeminiLiveRenewalDeliveryState.ACTIVE_UNCONFIRMED) handle = update.handle
        }
    }
    fun afterInputEnd(): GeminiLiveRenewalCheckpoint = GeminiLiveRenewalCheckpoint(delivery)
    fun takeHandle(): String? = handle
}

/** Only never-attempted complete packets are stored; consumption acknowledgements are not assumed. */
internal class GeminiLiveRenewalBuffer(private val maximumBytes: Int) {
    private val packets = java.util.ArrayDeque<ByteArray>()
    private var bytes = 0
    init { require(maximumBytes >= 3_200) }
    fun offer(packet: ByteArray) {
        if (packet.size > maximumBytes - bytes) throw GeminiLiveRenewalFailure(GeminiLiveRenewalFailureCode.OVERFLOW)
        packets.addLast(packet); bytes += packet.size
    }
    fun take(): ByteArray? = packets.pollFirst()?.also { bytes -= it.size }
    fun discard(lost: (Int) -> Unit) { while (true) lost((take() ?: break).size) }
}
