package app.guidecast.provider.gemma.translation

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 온디바이스 Gemma 추론 레이턴시 워치독 (Galaxy S23+ p95 2,000ms 방어 체계)
 *
 * 야외 장시간 방송 시 모바일 GPU 발열(Thermal Throttling)로 인한 추론 지연 급증을 실시간 감지하여,
 * 오디오 송출 지연 게이트(2,000ms) 위반 전에 경량 오프라인 번역(ML Kit)으로의 선제적 완화(Soft Fallback)를 권고합니다.
 */
internal class GemmaLatencyWatchdog(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val thermalLatencyThresholdMillis: Long = DEFAULT_THERMAL_LATENCY_THRESHOLD_MILLIS,
    private val hardLatencyCeilingMillis: Long = DEFAULT_HARD_LATENCY_CEILING_MILLIS,
) {
    private val lock = ReentrantLock()
    private val history = ArrayDeque<Long>(windowSize)
    private var cooldownCounter = 0

    fun recordInference(durationMillis: Long) = lock.withLock {
        require(durationMillis >= 0L)
        if (history.size >= windowSize) {
            history.removeFirst()
        }
        history.addLast(durationMillis)
        if (cooldownCounter > 0) {
            cooldownCounter--
        }
    }

    fun averageLatencyMillis(): Long = lock.withLock {
        if (history.isEmpty()) return 0L
        history.sum() / history.size
    }

    /**
     * 발열에 의한 스로틀링이나 추론 지연 급증으로 인해 경량 엔진으로의 일시적 스위칭이 필요한지 여부.
     */
    fun shouldThrottleToLightweight(): Boolean = lock.withLock {
        if (cooldownCounter > 0) return true
        if (history.isEmpty()) return false

        // 1. 단일 추론이 1,600ms를 초과한 경우 (TTS 합성 시간 400ms 미확보)
        val lastDuration = history.lastOrNull() ?: 0L
        if (lastDuration >= hardLatencyCeilingMillis) {
            cooldownCounter = COOLDOWN_TURNS
            return true
        }

        // 2. 최근 3회 평균이 1,200ms를 초과한 경우 (지속적 발열 감지)
        if (history.size >= windowSize && averageLatencyMillis() >= thermalLatencyThresholdMillis) {
            cooldownCounter = COOLDOWN_TURNS
            return true
        }

        false
    }

    fun reset() = lock.withLock {
        history.clear()
        cooldownCounter = 0
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 3
        // S23 기준 p95 2,000ms 음성 송출 합격선을 사수하기 위해 번역에 허용된 최대 평균 시간: 1,200ms
        const val DEFAULT_THERMAL_LATENCY_THRESHOLD_MILLIS = 1_200L
        // 단일 문장 번역 허용 상한 (초과 시 즉각 쿨다운 발동)
        const val DEFAULT_HARD_LATENCY_CEILING_MILLIS = 1_600L
        // 스로틀링 발동 시 경량 번역으로 완화할 문장 수
        const val COOLDOWN_TURNS = 2
    }
}
