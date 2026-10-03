package app.guidecast.core.translation

import java.math.BigDecimal

/** Durable reservations happen before dispatch. Unknown usage retains its reservation across restart. */
class OnlineRequestBudget(
    initialKnown: BigDecimal = BigDecimal.ZERO,
    initialHeld: BigDecimal = BigDecimal.ZERO,
    private val persist: (Snapshot) -> Boolean = { true },
) {
    data class Snapshot(val known: BigDecimal, val held: BigDecimal) {
        val committed: BigDecimal get() = known + held
    }
    data class Reservation internal constructor(val id: Long, val maximum: BigDecimal)
    private var state = Snapshot(initialKnown, initialHeld)
    private var nextId = 0L
    private val active = mutableMapOf<Long, Reservation>()
    init { require(initialKnown.signum() >= 0 && initialHeld.signum() >= 0) }
    @Synchronized fun snapshot(): Snapshot = state
    @Synchronized fun reserve(maximum: BigDecimal, limit: BigDecimal): Reservation? {
        if (maximum.signum() <= 0 || limit.signum() <= 0 || state.committed + maximum > limit) return null
        val next = state.copy(held = state.held + maximum)
        if (!persist(next)) return null
        state = next
        return Reservation(++nextId, maximum).also { active[it.id] = it }
    }
    /** Duplicate callbacks cannot refund or charge twice. Null usage never releases held funds. */
    @Synchronized fun settle(reservation: Reservation, actual: BigDecimal?): Boolean {
        if (active[reservation.id] != reservation) return false
        require(actual == null || actual.signum() >= 0)
        val next = if (actual == null) state else Snapshot(state.known + actual, state.held - reservation.maximum)
        if (!persist(next)) {
            active.remove(reservation.id) // Keep the durable conservative reservation on storage failure.
            return false
        }
        state = next
        active.remove(reservation.id)
        return true
    }
}
