package app.guidecast.core.translation

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Process-wide admission ownership for a native model's first load.
 *
 * The caller initially owns the ticket. A provider transfers it immediately before submitting
 * work to a Binder/native worker. After transfer, cancelling the caller must not release memory
 * admission: only the worker's real terminal callback or Binder death may call [completeNative].
 */
interface NativeColdLoadTicket : AutoCloseable {
    fun transferToNative()

    fun completeNative()
}

/** Carries one admission ticket through existing provider APIs without widening every contract. */
class NativeColdLoadTicketContext(
    val ticket: NativeColdLoadTicket,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<NativeColdLoadTicketContext>
}

/** Returns the ticket owned by the current first-use operation, if it has one. */
suspend fun currentNativeColdLoadTicket(): NativeColdLoadTicket? =
    currentCoroutineContext()[NativeColdLoadTicketContext]?.ticket
