package app.guidecast.transmitter

internal enum class RecognitionUsableSourceNoticeKind { SOURCE_NOT_UPDATED, SOURCE_UPDATED }

/** Counts/times only. Signal activity is not proof of spoken words or provider failure. */
internal data class RecognitionUsableSourceNotice(
    val kind: RecognitionUsableSourceNoticeKind,
    val unusableAttempts: Long,
    val millisWithoutUsableSource: Long,
    val observedAtMillis: Long,
) {
    val message: String get() = when (kind) {
        RecognitionUsableSourceNoticeKind.SOURCE_NOT_UPDATED ->
            "입력 신호가 이어지지만 인식된 원문이 갱신되지 않았습니다. " +
                "원문이 없는 인식 시도 ${unusableAttempts}회 · 입력과 선택한 원문 언어를 확인하고 " +
                "필요하면 ‘통역 다시 연결’을 누르세요. 원음 방송과 듣기 채널은 계속됩니다."
        RecognitionUsableSourceNoticeKind.SOURCE_UPDATED ->
            "인식된 원문이 다시 갱신되고 있습니다. 문맥을 유지하며 통역을 계속합니다."
    }
}

/**
 * Session-level usable-source visibility across ordinary recognition attempt replacements.
 * This never stops input, clears text/audio, selects a provider or creates a final result.
 * The existing per-attempt recovery watchdog still owns recognition restart decisions.
 */
internal class RecognitionUsableSourceWatchdog(
    private val noticeAfterMillis: Long = 20_000L,
    private val minimumActivityMillis: Long = 6_000L,
    private val recentActivityMillis: Long = 2_000L,
    private val repeatNoticeMillis: Long = 60_000L,
    private val quietResetMillis: Long = 20_000L,
) {
    private var nextGeneration = 0L
    private var captureGeneration: Long? = null
    private var captureStartedMillis = 0L
    private var lastUsableSourceMillis: Long? = null
    private var activityStartedMillis: Long? = null
    private var lastInputMillis: Long? = null
    private var lastActivityMillis: Long? = null
    private var activityMillis = 0L
    private var unusableAttempts = 0L
    private var warning: RecognitionUsableSourceNotice? = null
    private var latestNotice: RecognitionUsableSourceNotice? = null
    init {
        require(noticeAfterMillis > 0 && minimumActivityMillis > 0 && recentActivityMillis > 0)
        require(repeatNoticeMillis >= noticeAfterMillis && quietResetMillis > recentActivityMillis)
    }

    /** Called for a new capture/configuration generation, never for an ordinary STT attempt. */
    @Synchronized fun beginCapture(nowMillis: Long): Long {
        require(nowMillis >= 0)
        check(nextGeneration < Long.MAX_VALUE)
        captureGeneration = ++nextGeneration
        captureStartedMillis = nowMillis
        lastUsableSourceMillis = null
        activityStartedMillis = null
        lastInputMillis = null
        lastActivityMillis = null
        activityMillis = 0L
        unusableAttempts = 0L
        warning = null
        latestNotice = null
        return nextGeneration
    }

    @Synchronized fun observeInput(generation: Long, nowMillis: Long, active: Boolean, frameMillis: Long) {
        if (generation != captureGeneration) return
        lastInputMillis = nowMillis
        if (!active) return
        val previous = lastActivityMillis
        if (previous == null || nowMillis - previous >= quietResetMillis) {
            // Only a long quiet/missing-input interval receives a fresh activity grace.
            // Natural short pauses and ordinary ASR attempt replacements keep accumulated
            // activity and the source clock. Recent activity remains a separate notice gate.
            activityStartedMillis = nowMillis
            activityMillis = 0L
        }
        lastActivityMillis = nowMillis
        activityMillis = (activityMillis + frameMillis.coerceIn(0L, 1_000L))
            .coerceAtMost(minimumActivityMillis)
    }

    /** Caller supplies only actual accepted, changed, nonblank provider source captions. */
    @Synchronized fun onUsableSource(generation: Long, nowMillis: Long): RecognitionUsableSourceNotice? {
        if (generation != captureGeneration) return null
        val priorWarning = warning
        lastUsableSourceMillis = nowMillis
        activityMillis = 0L
        unusableAttempts = 0L
        warning = null
        latestNotice = priorWarning?.let {
            RecognitionUsableSourceNotice(RecognitionUsableSourceNoticeKind.SOURCE_UPDATED, 0, 0, nowMillis)
        }
        return latestNotice
    }

    /** A normal empty endpoint/no-match is counted, but never resets the session source clock. */
    @Synchronized fun onUnusableAttempt(generation: Long) {
        if (generation == captureGeneration && unusableAttempts < Long.MAX_VALUE) unusableAttempts++
    }

    @Synchronized fun poll(generation: Long, nowMillis: Long): RecognitionUsableSourceNotice? {
        if (generation != captureGeneration) return null
        val input = lastInputMillis ?: return null
        val activity = lastActivityMillis ?: return null
        val activityStart = activityStartedMillis ?: return null
        if (nowMillis - input > recentActivityMillis || nowMillis - activity > recentActivityMillis ||
            activityMillis < minimumActivityMillis) return null
        val sourceBaseline = maxOf(captureStartedMillis, lastUsableSourceMillis ?: captureStartedMillis)
        val age = (nowMillis - sourceBaseline).coerceAtLeast(0L)
        val activityGraceAge = (nowMillis - activityStart).coerceAtLeast(0L)
        if (age < noticeAfterMillis || activityGraceAge < noticeAfterMillis ||
            warning?.let { nowMillis - it.observedAtMillis < repeatNoticeMillis } == true) return null
        return RecognitionUsableSourceNotice(RecognitionUsableSourceNoticeKind.SOURCE_NOT_UPDATED,
            unusableAttempts, age, nowMillis).also { warning = it; latestNotice = it }
    }

    /** Frozen warning text survives ready/restart statuses; counts update only at a notice edge. */
    @Synchronized fun currentWarning(generation: Long): RecognitionUsableSourceNotice? =
        warning.takeIf { generation == captureGeneration }

    /** Status ownership check and non-suspending publication share the capture-generation lock. */
    @Synchronized fun ifCurrent(generation: Long, action: () -> Unit): Boolean {
        if (generation != captureGeneration) return false
        action()
        return true
    }

    /** A delayed notice cannot replace a newer warning or source recovery in the same capture. */
    @Synchronized fun publishIfCurrentNotice(generation: Long, notice: RecognitionUsableSourceNotice,
        action: () -> Unit): Boolean {
        if (generation != captureGeneration || notice !== latestNotice) return false
        action()
        return true
    }

    @Synchronized fun endCapture(generation: Long) {
        if (generation == captureGeneration) captureGeneration = null
    }
}
