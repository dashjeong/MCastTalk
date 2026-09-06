package app.guidecast.provider.mlkit.translation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Immutable ownership token for one language and one private worker-process slot. */
internal data class MlKitWorkerSlotLease(
    val languageTag: String,
    val slot: Int,
    val generation: Long,
)

/** Ownership token used only while disconnecting or draining a retiring language process. */
internal data class MlKitWorkerSlotRetirement(
    val lease: MlKitWorkerSlotLease,
)

/**
 * Holds one target language out of service for the complete model-file removal transaction.
 *
 * [retirement] is null when the target did not own a worker slot at reservation time. The
 * operation identifier still blocks a new lease for that target until model deletion reaches a
 * real terminal state.
 */
internal data class MlKitWorkerTargetRemoval(
    val languageTag: String,
    val backendModel: String,
    val operationId: Long,
    val retirement: MlKitWorkerSlotRetirement?,
    val sharedBackendInUse: Boolean = false,
)

/**
 * Assigns one target language to one private process for the lifetime of a live session.
 *
 * Assignment and generation capture happen under the same lock. A retiring slot remains occupied
 * until its connection is detached, so an engine racing retirement can neither capture the old
 * language with a new epoch nor initialize that process after another language takes the slot.
 */
internal class MlKitWorkerSlotAllocator(private val slotCount: Int) {
    private data class SlotState(
        var languageTag: String? = null,
        var generation: Long = INITIAL_GENERATION,
        var retiring: Boolean = false,
    )

    private val lock = Any()
    private val states = Array(slotCount) { SlotState() }
    private val changes = MutableStateFlow(0L)
    private val targetRemovals = mutableMapOf<String, Long>()
    private var acceptingLeases = true
    private var sessionGeneration = INITIAL_GENERATION
    private var preparationGeneration = INITIAL_GENERATION
    private var nextRemovalOperationId = INITIAL_GENERATION

    init {
        require(slotCount > 0)
    }

    fun leaseFor(languageTag: String): MlKitWorkerSlotLease = synchronized(lock) {
        check(acceptingLeases) { "ML Kit worker slots are being reset" }
        leaseOrNullLocked(languageTag)
            ?: error("No isolated ML Kit worker slot remains for $languageTag")
    }

    /** Captures the owning session so a waiting engine cannot survive a stop/restart boundary. */
    fun captureSessionGeneration(): Long = synchronized(lock) { sessionGeneration }

    /** Inspects an existing active assignment without allocating a slot as a side effect. */
    fun existingLeaseFor(languageTag: String): MlKitWorkerSlotLease? = synchronized(lock) {
        if (!acceptingLeases || isTargetBlockedLocked(languageTag)) return@synchronized null
        val slot = states.indexOfFirst { it.languageTag == languageTag && !it.retiring }
        states.getOrNull(slot)?.lease(slot)
    }

    /** Checks whether any active slot or pending removal is utilizing the shared backend model. */
    fun isBackendInUse(backendModel: String): Boolean = synchronized(lock) {
        states.any { state ->
            state.languageTag != null && !state.retiring && state.languageTag?.toMlKitLanguage() == backendModel
        } || backendModel in targetRemovals
    }

    private fun isTargetBlockedLocked(languageTag: String): Boolean {
        val backend = languageTag.toMlKitLanguage()
        return backend in targetRemovals
    }

    /** Suspends only this language while an obsolete process is safely drained for slot reuse. */
    suspend fun awaitLeaseFor(
        languageTag: String,
        expectedSessionGeneration: Long,
        expectedPreparationGeneration: Long? = null,
    ): MlKitWorkerSlotLease {
        while (true) {
            val observedChange = changes.value
            val lease = synchronized(lock) {
                check(acceptingLeases && sessionGeneration == expectedSessionGeneration) {
                    "ML Kit translation session was replaced"
                }
                check(
                    expectedPreparationGeneration == null ||
                        preparationGeneration == expectedPreparationGeneration,
                ) { "ML Kit model preparation was superseded" }
                leaseOrNullLocked(languageTag)
            }
            if (lease != null) return lease
            changes.first { it != observedChange }
        }
    }

    /**
     * Invalidates stale settings waiters without waiting for their download/JNI work to finish.
     * This is intentionally a short allocator mutation; no Binder or model work runs under it.
     */
    fun activatePreparationGeneration(generation: Long) = synchronized(lock) {
        require(generation > 0L) { "ML Kit preparation generation must be positive" }
        if (preparationGeneration != generation) {
            preparationGeneration = generation
            signalChangeLocked()
        }
    }

    private fun leaseOrNullLocked(languageTag: String): MlKitWorkerSlotLease? {
        if (isTargetBlockedLocked(languageTag)) return null
        val existingSlot = states.indexOfFirst { it.languageTag == languageTag }
        if (existingSlot >= 0) {
            val state = states[existingSlot]
            return if (state.retiring) null else state.lease(existingSlot)
        }

        val availableSlot = states.indexOfFirst { it.languageTag == null && !it.retiring }
        if (availableSlot < 0) return null
        return states[availableSlot].apply {
            this.languageTag = languageTag
            retiring = false
        }.lease(availableSlot).also { signalChangeLocked() }
    }

    fun beginRetirement(languageTag: String): MlKitWorkerSlotRetirement? = synchronized(lock) {
        if (isTargetBlockedLocked(languageTag)) return@synchronized null
        val slot = states.indexOfFirst { it.languageTag == languageTag }
        if (slot < 0) return@synchronized null
        val state = states[slot]
        check(!state.retiring) { "$languageTag ML Kit worker slot is already retiring" }
        state.retiring = true
        state.generation = state.generation.nextGeneration()
        MlKitWorkerSlotRetirement(state.lease(slot)).also { signalChangeLocked() }
    }

    fun beginRetirementsExcept(retainedLanguageTags: Set<String>): List<MlKitWorkerSlotRetirement> =
        synchronized(lock) {
            states.mapIndexedNotNull { slot, state ->
                val languageTag = state.languageTag ?: return@mapIndexedNotNull null
                if (languageTag in retainedLanguageTags || state.retiring) {
                    return@mapIndexedNotNull null
                }
                state.retiring = true
                state.generation = state.generation.nextGeneration()
                MlKitWorkerSlotRetirement(state.lease(slot))
            }.also { retirements ->
                if (retirements.isNotEmpty()) signalChangeLocked()
            }
        }

    /** Begins a reconciliation only if its preparation generation still owns the allocator. */
    fun beginRetirementsExcept(
        retainedLanguageTags: Set<String>,
        expectedPreparationGeneration: Long,
    ): List<MlKitWorkerSlotRetirement>? = synchronized(lock) {
        if (preparationGeneration != expectedPreparationGeneration) return@synchronized null
        states.mapIndexedNotNull { slot, state ->
            val languageTag = state.languageTag ?: return@mapIndexedNotNull null
            if (languageTag in retainedLanguageTags || state.retiring) {
                return@mapIndexedNotNull null
            }
            state.retiring = true
            state.generation = state.generation.nextGeneration()
            MlKitWorkerSlotRetirement(state.lease(slot))
        }.also { retirements ->
            if (retirements.isNotEmpty()) signalChangeLocked()
        }
    }

    /**
     * Reserves a target before model deletion and waits out an earlier retirement/removal.
     *
     * Unlike a slot retirement, this also reserves a target with no current slot. That closes the
     * delete-vs-first-prepare race where a new process could open model files after the catalog was
     * inspected but before RemoteModelManager deleted them.
     */
    suspend fun awaitTargetRemoval(languageTag: String): MlKitWorkerTargetRemoval {
        val backend = languageTag.toMlKitLanguage()
        while (true) {
            val observedChange = changes.value
            val removal = synchronized(lock) {
                check(acceptingLeases) { "ML Kit worker slots are being reset" }
                if (backend in targetRemovals) {
                    null
                } else {
                    val slot = states.indexOfFirst { it.languageTag == languageTag }
                    val state = states.getOrNull(slot)
                    if (state?.retiring == true) {
                        null
                    } else {
                        // Sibling check includes retiring==true siblings so model is not deleted before native drain finishes
                        val siblingInUse = states.any { other ->
                            other.languageTag != null && other.languageTag != languageTag &&
                                other.languageTag?.toMlKitLanguage() == backend
                        }
                        val operationId = nextRemovalOperationId.also {
                            nextRemovalOperationId = nextRemovalOperationId.nextGeneration()
                        }
                        targetRemovals[backend] = operationId
                        val retirement = state?.let {
                            it.retiring = true
                            it.generation = it.generation.nextGeneration()
                            MlKitWorkerSlotRetirement(it.lease(slot))
                        }
                        MlKitWorkerTargetRemoval(
                            languageTag = languageTag,
                            backendModel = backend,
                            operationId = operationId,
                            retirement = retirement,
                            sharedBackendInUse = siblingInUse,
                        ).also { signalChangeLocked() }
                    }
                }
            }
            if (removal != null) return removal
            changes.first { it != observedChange }
        }
    }

    /** Commits successful model deletion and only then makes the target/slot reusable. */
    fun completeTargetRemoval(removal: MlKitWorkerTargetRemoval): Boolean = synchronized(lock) {
        if (targetRemovals[removal.backendModel] != removal.operationId) {
            return@synchronized false
        }
        val retirement = removal.retirement
        if (retirement != null && !retirementMatchesLocked(retirement)) {
            return@synchronized false
        }
        if (retirement != null) {
            val state = states[retirement.lease.slot]
            state.languageTag = null
            state.retiring = false
        }
        targetRemovals.remove(removal.backendModel)
        signalChangeLocked()
        true
    }

    /** Restores prior ownership after drain, deletion, or caller cancellation fails. */
    fun cancelTargetRemoval(removal: MlKitWorkerTargetRemoval): Boolean = synchronized(lock) {
        if (targetRemovals[removal.backendModel] != removal.operationId) {
            return@synchronized false
        }
        val retirement = removal.retirement
        if (retirement != null && !retirementMatchesLocked(retirement)) {
            return@synchronized false
        }
        retirement?.let { states[it.lease.slot].retiring = false }
        targetRemovals.remove(removal.backendModel)
        signalChangeLocked()
        true
    }

    fun isTargetRemovalActive(removal: MlKitWorkerTargetRemoval): Boolean = synchronized(lock) {
        targetRemovals[removal.backendModel] == removal.operationId &&
            (removal.retirement == null || retirementMatchesLocked(removal.retirement))
    }

    /** Invalidates stale engine objects while retaining target ownership for safe later draining. */
    fun invalidateLeasesPreservingAssignments() = synchronized(lock) {
        sessionGeneration = sessionGeneration.nextGeneration()
        states.forEach { state ->
            // beginRetirement already invalidated the prior lease. Preserve its token unchanged so
            // the in-flight drain can still commit or roll back ownership after session teardown.
            if (state.languageTag != null && !state.retiring) {
                state.generation = state.generation.nextGeneration()
            }
        }
        signalChangeLocked()
    }

    fun completeRetirement(retirement: MlKitWorkerSlotRetirement): Boolean = synchronized(lock) {
        val lease = retirement.lease
        val state = states.getOrNull(lease.slot) ?: return@synchronized false
        if (!state.retiring || state.languageTag != lease.languageTag ||
            state.generation != lease.generation
        ) {
            false
        } else {
            state.languageTag = null
            state.retiring = false
            signalChangeLocked()
            true
        }
    }

    /** Keeps a failed drain owned by the same language while allowing a clean retry generation. */
    fun cancelRetirement(retirement: MlKitWorkerSlotRetirement): Boolean = synchronized(lock) {
        val lease = retirement.lease
        val state = states.getOrNull(lease.slot) ?: return@synchronized false
        if (!state.retiring || state.languageTag != lease.languageTag ||
            state.generation != lease.generation
        ) {
            false
        } else {
            state.retiring = false
            signalChangeLocked()
            true
        }
    }

    fun isActive(lease: MlKitWorkerSlotLease): Boolean = synchronized(lock) {
        states.getOrNull(lease.slot)?.let { state ->
            acceptingLeases && !state.retiring && state.languageTag == lease.languageTag &&
                state.generation == lease.generation
        } == true
    }

    fun isActiveForPreparation(
        lease: MlKitWorkerSlotLease,
        expectedPreparationGeneration: Long,
    ): Boolean = synchronized(lock) {
        preparationGeneration == expectedPreparationGeneration &&
            states.getOrNull(lease.slot)?.let { state ->
                acceptingLeases && !state.retiring && state.languageTag == lease.languageTag &&
                    state.generation == lease.generation
            } == true
    }

    fun isRetiring(retirement: MlKitWorkerSlotRetirement): Boolean = synchronized(lock) {
        val lease = retirement.lease
        states.getOrNull(lease.slot)?.let { state ->
            state.retiring && state.languageTag == lease.languageTag &&
                state.generation == lease.generation
        } == true
    }

    /** Atomically blocks every new engine lease before provider-wide disconnect begins. */
    fun beginReset(): List<MlKitWorkerSlotRetirement> = synchronized(lock) {
        acceptingLeases = false
        sessionGeneration = sessionGeneration.nextGeneration()
        states.mapIndexedNotNull { slot, state ->
            state.languageTag?.let {
                state.retiring = true
                state.generation = state.generation.nextGeneration()
                MlKitWorkerSlotRetirement(state.lease(slot))
            }
        }.also { signalChangeLocked() }
    }

    fun completeReset(reopen: Boolean) = synchronized(lock) {
        states.forEach { state ->
            state.languageTag = null
            state.retiring = false
        }
        targetRemovals.clear()
        acceptingLeases = reopen
        signalChangeLocked()
    }

    internal fun snapshot(): Map<String, Int> = synchronized(lock) {
        states.mapIndexedNotNull { slot, state ->
            state.languageTag?.let { it to slot }
        }.toMap()
    }

    private fun SlotState.lease(slot: Int): MlKitWorkerSlotLease = MlKitWorkerSlotLease(
        languageTag = requireNotNull(languageTag),
        slot = slot,
        generation = generation,
    )

    private fun retirementMatchesLocked(retirement: MlKitWorkerSlotRetirement): Boolean {
        val lease = retirement.lease
        return states.getOrNull(lease.slot)?.let { state ->
            state.retiring && state.languageTag == lease.languageTag &&
                state.generation == lease.generation
        } == true
    }

    private fun Long.nextGeneration(): Long = if (this == Long.MAX_VALUE) {
        INITIAL_GENERATION
    } else {
        this + 1L
    }

    private fun signalChangeLocked() {
        changes.value = changes.value.nextGeneration()
    }

    private companion object {
        const val INITIAL_GENERATION = 1L
    }
}
