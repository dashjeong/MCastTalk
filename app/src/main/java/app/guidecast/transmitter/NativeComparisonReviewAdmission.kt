package app.guidecast.transmitter

internal fun nativeComparisonIsCurrent(comparison: ShadowComparison, ownsCandidate: Boolean,
    relay: InterpreterRelayOptions, generation: Long, api: TranslationApiOptions,
    authorized: Boolean, corpusRevision: Long): Boolean {
    val identity = comparison.nativeIdentity ?: return false
    return ownsCandidate && relay.compareOffline && authorized && api.allowLiveAudio &&
        serviceExperience(api).supportsNativePairComparison && identity.controlGeneration == generation &&
        identity.settingsRevision == api.revision && identity.provider == api.provider && identity.model == api.model &&
        comparison.corpusRevision == corpusRevision && comparison.style == api.tone &&
        DomainCorpusRepository.normalizeSourceLanguageTag(comparison.source) == DomainCorpusRepository.normalizeSourceLanguageTag(relay.source) &&
        DomainCorpusRepository.normalizeTargetLanguageTag(comparison.target) == DomainCorpusRepository.normalizeTargetLanguageTag(relay.target)
}

/** Serialize final local activation with the session owner and the settings mutation locks. */
class NativeComparisonCommitAdmission internal constructor(private val locks: List<Any>,
    private val atomicGate: ((() -> Unit) -> Unit)? = null, private val current: () -> Boolean) {
    internal fun commit(block: () -> Unit) {
        val gate = atomicGate
        if (gate == null) withLock(0, block) else gate { withLock(0, block) }
    }
    private fun withLock(index: Int, block: () -> Unit) {
        if (index < locks.size) synchronized(locks[index]) { withLock(index + 1, block) }
        else {
            check(current()) { "중계·비교 설정이 바뀌었습니다. 새 예문을 확인하세요." }
            block()
        }
    }
}
