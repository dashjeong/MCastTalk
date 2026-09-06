package app.guidecast.transmitter

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import app.guidecast.provider.gemma.translation.GemmaBroadcastCapability
import app.guidecast.provider.gemma.translation.GemmaTranslationProvider

enum class MultiLanguageResourceState {
    SAFE,
    CAUTION,
    PRESSURE,
}

data class DeviceMemorySnapshot(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val systemLowMemory: Boolean,
    val valuesValid: Boolean = true,
) {
    companion object {
        private const val DEFAULT_MATERIAL_MEMORY_DELTA_BYTES = 50L * 1024L * 1024L

        fun detect(context: Context): DeviceMemorySnapshot {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return DeviceMemorySnapshot(0L, 0L, 0L, false, valuesValid = false)
            val memory = ActivityManager.MemoryInfo()
            return runCatching {
                manager.getMemoryInfo(memory)
                fromRaw(
                    totalMemoryBytes = memory.totalMem,
                    availableMemoryBytes = memory.availMem,
                    lowMemoryThresholdBytes = memory.threshold,
                    systemLowMemory = memory.lowMemory,
                )
            }.getOrElse {
                DeviceMemorySnapshot(0L, 0L, 0L, false, valuesValid = false)
            }
        }

        fun fromRaw(
            totalMemoryBytes: Long,
            availableMemoryBytes: Long,
            lowMemoryThresholdBytes: Long,
            systemLowMemory: Boolean,
        ): DeviceMemorySnapshot {
            val valid = totalMemoryBytes > 0L &&
                availableMemoryBytes >= 0L &&
                lowMemoryThresholdBytes >= 0L
            val safeTotal = totalMemoryBytes.coerceAtLeast(0L)
            val safeAvailable = availableMemoryBytes.coerceAtLeast(0L).let { available ->
                if (safeTotal > 0L) available.coerceAtMost(safeTotal) else available
            }
            return DeviceMemorySnapshot(
                totalMemoryBytes = safeTotal,
                availableMemoryBytes = safeAvailable,
                lowMemoryThresholdBytes = lowMemoryThresholdBytes.coerceAtLeast(0L),
                systemLowMemory = systemLowMemory,
                valuesValid = valid,
            )
        }
    }

    /**
     * ActivityManager values fluctuate continuously. Treat small available-memory movement as
     * telemetry noise so the Compose tree and accessibility services are not invalidated every
     * polling interval. Pressure/validity/threshold changes are always material.
     */
    fun materiallyDiffersFrom(
        other: DeviceMemorySnapshot,
        availableMemoryDeltaBytes: Long = DEFAULT_MATERIAL_MEMORY_DELTA_BYTES,
    ): Boolean = valuesValid != other.valuesValid ||
        systemLowMemory != other.systemLowMemory ||
        totalMemoryBytes != other.totalMemoryBytes ||
        lowMemoryThresholdBytes != other.lowMemoryThresholdBytes ||
        absoluteDifference(availableMemoryBytes, other.availableMemoryBytes) >=
        availableMemoryDeltaBytes.coerceAtLeast(0L)

    private fun absoluteDifference(left: Long, right: Long): Long = when {
        left >= right -> left - right
        else -> right - left
    }

}

/**
 * A sparingly sampled, whole-app view across the main and private inference processes.
 *
 * PSS proportionally accounts for shared pages and is useful for a point-in-time device
 * snapshot; private dirty highlights allocations that cannot simply be dropped from a file. The
 * values are diagnostic only and never remove a selected language or lower its voice quality.
 */
data class AppProcessMemorySnapshot(
    val processCount: Int,
    val totalPssBytes: Long,
    val totalPrivateDirtyBytes: Long,
    val valuesValid: Boolean,
) {
    companion object {
        private const val KIBIBYTE_BYTES = 1_024L
        private const val DEFAULT_MATERIAL_DELTA_BYTES = 32L * 1_024L * 1_024L

        fun unavailable() = AppProcessMemorySnapshot(
            processCount = 0,
            totalPssBytes = 0L,
            totalPrivateDirtyBytes = 0L,
            valuesValid = false,
        )

        fun detect(context: Context): AppProcessMemorySnapshot {
            val manager = context.getSystemService(ActivityManager::class.java)
                ?: return unavailable()
            return runCatching {
                val packageName = context.packageName
                val processes = manager.runningAppProcesses.orEmpty().filter { process ->
                    process.uid == Process.myUid() &&
                        (process.processName == packageName ||
                            process.processName.startsWith("$packageName:"))
                }
                if (processes.isEmpty()) return@runCatching unavailable()
                val memory = manager.getProcessMemoryInfo(
                    processes.map { process -> process.pid }.toIntArray(),
                )
                if (memory.size != processes.size) return@runCatching unavailable()
                fromKilobytes(
                    totalPssKilobytes = memory.map { info -> info.totalPss.toLong() },
                    totalPrivateDirtyKilobytes = memory.map { info ->
                        info.totalPrivateDirty.toLong()
                    },
                )
            }.getOrElse { unavailable() }
        }

        internal fun fromKilobytes(
            totalPssKilobytes: List<Long>,
            totalPrivateDirtyKilobytes: List<Long>,
        ): AppProcessMemorySnapshot {
            if (totalPssKilobytes.isEmpty() ||
                totalPssKilobytes.size != totalPrivateDirtyKilobytes.size ||
                totalPssKilobytes.any { it < 0L } ||
                totalPrivateDirtyKilobytes.any { it < 0L }
            ) {
                return unavailable()
            }
            return AppProcessMemorySnapshot(
                processCount = totalPssKilobytes.size,
                totalPssBytes = saturatingKilobytesToBytes(totalPssKilobytes),
                totalPrivateDirtyBytes = saturatingKilobytesToBytes(
                    totalPrivateDirtyKilobytes,
                ),
                valuesValid = true,
            )
        }

        private fun saturatingKilobytesToBytes(values: List<Long>): Long {
            var totalKilobytes = 0L
            values.forEach { value ->
                totalKilobytes = if (totalKilobytes > Long.MAX_VALUE - value) {
                    Long.MAX_VALUE
                } else {
                    totalKilobytes + value
                }
            }
            return if (totalKilobytes > Long.MAX_VALUE / KIBIBYTE_BYTES) {
                Long.MAX_VALUE
            } else {
                totalKilobytes * KIBIBYTE_BYTES
            }
        }
    }

    fun materiallyDiffersFrom(
        other: AppProcessMemorySnapshot,
        deltaBytes: Long = DEFAULT_MATERIAL_DELTA_BYTES,
    ): Boolean = valuesValid != other.valuesValid ||
        processCount != other.processCount ||
        absoluteDifference(totalPssBytes, other.totalPssBytes) >= deltaBytes.coerceAtLeast(0L) ||
        absoluteDifference(totalPrivateDirtyBytes, other.totalPrivateDirtyBytes) >=
        deltaBytes.coerceAtLeast(0L)

    private fun absoluteDifference(left: Long, right: Long): Long = when {
        left >= right -> left - right
        else -> right - left
    }
}

data class LanguageResourcePlan(
    val languageTag: String,
    val translationPlan: String,
    val synthesisPlan: String,
    val estimatedAdditionalReservationBytes: Long,
)

data class MultiLanguageResourceDiagnostics(
    val state: MultiLanguageResourceState,
    val memory: DeviceMemorySnapshot,
    /** Measured current app-process memory; absent values never become a capacity decision. */
    val appProcessMemory: AppProcessMemorySnapshot,
    val selectedChannelCount: Int,
    /** Conservative admission reservation, not measured RSS or a capacity guarantee. */
    val estimatedAdditionalReservationBytes: Long,
    val projectedAvailableMemoryBytes: Long,
    val gemmaColdLoadFloorApplies: Boolean,
    val requiresSequentialPreparation: Boolean,
    val summary: String,
    val recommendation: String,
    val languagePlans: List<LanguageResourcePlan>,
    /** Coarse initial memory class only; it is not a throughput or device-performance result. */
    val initialMemoryProfileLabel: String,
    /** Memory-only estimate, not measured throughput; null is unavailable and zero is no SAFE estimate. */
    val recommendedChannelCount: Int? = null,
)

internal data class MultiLanguageResourceInput(
    val selectedLanguageTags: List<String>,
    val useGemma: Boolean,
    val gemmaModelReady: Boolean,
    val gemmaWorkerPrepared: Boolean = false,
    val speechRecognitionReady: Boolean,
    val translationReadyLanguageTags: Set<String>,
    val synthesisReadyLanguageTags: Set<String>,
    val synthesisFallbackReadyLanguageTags: Set<String>,
)

/**
 * Pure, side-effect-free resource planner. It never loads a model, starts STT/TTS, changes the
 * selected languages, or decides whether the operator may open a broadcast.
 */
internal object MultiLanguageResourcePlanner {
    private const val CAUTION_MARGIN_BYTES = 512L * 1024L * 1024L
    private const val MANY_CHANNELS = 4
    private val RECOMMENDATION_LANGUAGE_TAGS =
        PRIMARY_TRANSLATION_LANGUAGE_TAGS + listOf("nl", "es")

    fun evaluate(
        memory: DeviceMemorySnapshot,
        input: MultiLanguageResourceInput,
        appProcessMemory: AppProcessMemorySnapshot = AppProcessMemorySnapshot.unavailable(),
    ): MultiLanguageResourceDiagnostics {
        val choices = recommendationLanguageChoices(input.selectedLanguageTags)
        val recommended = if (!memory.valuesValid) null else (1..choices.size).lastOrNull { count ->
            evaluateOnce(memory, input.copy(selectedLanguageTags = choices.take(count)), appProcessMemory)
                .state == MultiLanguageResourceState.SAFE
        } ?: 0
        return evaluateOnce(memory, input, appProcessMemory).copy(recommendedChannelCount = recommended)
    }

    private fun evaluateOnce(
        memory: DeviceMemorySnapshot,
        input: MultiLanguageResourceInput,
        appProcessMemory: AppProcessMemorySnapshot,
    ): MultiLanguageResourceDiagnostics {
        val selected = input.selectedLanguageTags.distinct()
            .take(MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES)
        val perWorkerReservation = NativeSupportMemoryBudget.DEFAULT_NEW_WORKER_RESERVATION_BYTES

        var totalReservation = if (input.speechRecognitionReady || selected.isEmpty()) {
            0L
        } else {
            perWorkerReservation
        }
        val gemmaLanguageTags = selected.filterTo(linkedSetOf()) { languageTag ->
            input.useGemma && GemmaTranslationProvider.supportsTargetLanguage(languageTag)
        }

        val languagePlans = selected.map { languageTag ->
            val usesGemma = languageTag in gemmaLanguageTags
            val lightweightReady = languageTag in input.translationReadyLanguageTags
            val synthesisReady = languageTag in input.synthesisReadyLanguageTags
            val fallbackReady = languageTag in input.synthesisFallbackReadyLanguageTags

            // The lightweight model is also the prepared failure-isolation path for every
            // Gemma-capable channel, so a missing fallback still receives one bounded reservation.
            val translationReservation = if (lightweightReady) 0L else perWorkerReservation
            val synthesisReservation = if (synthesisReady || fallbackReady) {
                0L
            } else {
                perWorkerReservation
            }
            val channelReservation = saturatingAdd(
                translationReservation,
                synthesisReservation,
            )
            totalReservation = saturatingAdd(totalReservation, channelReservation)

            LanguageResourcePlan(
                languageTag = languageTag,
                translationPlan = when {
                    usesGemma && input.gemmaModelReady && lightweightReady ->
                        "공유 Gemma · ML Kit 복구 준비됨"
                    usesGemma && input.gemmaModelReady -> "공유 Gemma · ML Kit 복구 준비 필요"
                    usesGemma -> "Gemma 모델 및 ML Kit 복구 준비 필요"
                    lightweightReady -> "ML Kit 오프라인 준비됨"
                    else -> "ML Kit 오프라인 준비 필요"
                },
                synthesisPlan = when {
                    synthesisReady -> "고품질 통역 음성 준비됨"
                    fallbackReady -> "Galaxy 오프라인 음성 준비됨"
                    else -> "통역 음성 준비 필요"
                },
                estimatedAdditionalReservationBytes = channelReservation,
            )
        }

        val projectedAvailable = saturatingSubtract(
            memory.availableMemoryBytes,
            totalReservation,
        )
        val headroomFloor = maxOf(
            NativeSupportMemoryAdmission.MIN_NEW_WORKER_HEADROOM_BYTES,
            memory.lowMemoryThresholdBytes,
        )
        // 2.41 GiB is the model file size, not a RAM estimate. The existing admission rule is a
        // separate minimum-available-memory condition for attempting a cold Gemma load.
        val gemmaColdLoadFloorApplies = gemmaLanguageTags.isNotEmpty() &&
            input.gemmaModelReady && !input.gemmaWorkerPrepared
        val gemmaFloorMissed = gemmaColdLoadFloorApplies &&
            memory.availableMemoryBytes < GemmaBroadcastCapability.MIN_AVAILABLE_MEMORY_BYTES
        val invalidMemory = !memory.valuesValid
        val pressure = invalidMemory || memory.systemLowMemory ||
            memory.availableMemoryBytes <= memory.lowMemoryThresholdBytes ||
            projectedAvailable < headroomFloor || gemmaFloorMissed
        val caution = !pressure && (
            projectedAvailable < saturatingAdd(headroomFloor, CAUTION_MARGIN_BYTES) ||
                (memory.totalMemoryBytes < GemmaBroadcastCapability.STANDARD_MEMORY_BYTES &&
                    selected.size >= MANY_CHANNELS) ||
                (gemmaColdLoadFloorApplies && memory.availableMemoryBytes < saturatingAdd(
                    GemmaBroadcastCapability.MIN_AVAILABLE_MEMORY_BYTES,
                    CAUTION_MARGIN_BYTES,
                ))
            )
        val state = when {
            pressure -> MultiLanguageResourceState.PRESSURE
            caution -> MultiLanguageResourceState.CAUTION
            else -> MultiLanguageResourceState.SAFE
        }
        val sequential = state != MultiLanguageResourceState.SAFE ||
            memory.totalMemoryBytes < GemmaBroadcastCapability.STANDARD_MEMORY_BYTES ||
            selected.size >= MANY_CHANNELS

        return MultiLanguageResourceDiagnostics(
            state = state,
            memory = memory,
            appProcessMemory = appProcessMemory,
            selectedChannelCount = selected.size,
            estimatedAdditionalReservationBytes = totalReservation,
            projectedAvailableMemoryBytes = projectedAvailable,
            gemmaColdLoadFloorApplies = gemmaColdLoadFloorApplies,
            requiresSequentialPreparation = sequential,
            summary = when (state) {
                MultiLanguageResourceState.SAFE -> "현재 선택은 추정 메모리 여유가 있습니다."
                MultiLanguageResourceState.CAUTION ->
                    "추정 메모리 기준으로 언어별 순차 준비를 권장합니다."
                MultiLanguageResourceState.PRESSURE ->
                    "현재 추정 메모리 압력이 높습니다. 준비된 모델 우선 사용과 순차 준비가 필요합니다."
            },
            recommendation = when {
                invalidMemory ->
                    "메모리 추정 불가 · Android 값을 확인하지 못했습니다. 방송은 운영자가 결정하고 상태를 다시 확인하세요."
                gemmaFloorMissed ->
                    "추정 메모리 권장 · Gemma 적재 조건에 못 미칩니다. 다른 앱을 종료하거나 준비된 ML Kit 복구 경로를 사용하세요."
                sequential ->
                    "추정 메모리 권장 · 모델 확인 후 언어별로 순차 준비하세요. 진단은 방송 시작을 차단하지 않습니다."
                else ->
                    "추정 메모리 권장 · 준비된 모델은 추가 적재 피크를 줄입니다. 측정 처리량이 아니며 방송 시작을 차단하지 않습니다."
            },
            languagePlans = languagePlans,
            initialMemoryProfileLabel = initialMemoryProfileLabel(memory),
        )
    }

    internal fun recommendationLanguageChoices(selectedLanguageTags: List<String>): List<String> =
        (selectedLanguageTags + RECOMMENDATION_LANGUAGE_TAGS)
            .distinct()
            .take(MAX_SIMULTANEOUS_TRANSLATION_LANGUAGES)

    private fun initialMemoryProfileLabel(memory: DeviceMemorySnapshot): String {
        if (!memory.valuesValid) {
            return "메모리 등급 확인 불가 · 초기 추정이며 처리량·성능을 보장하지 않습니다."
        }
        val memoryClass = when {
            memory.totalMemoryBytes >= 13L * 1024L * 1024L * 1024L -> "16GB급"
            memory.totalMemoryBytes >= 9L * 1024L * 1024L * 1024L -> "12GB급"
            else -> "8GB급"
        }
        return "$memoryClass 초기 메모리 추정 · 단일 Gemma 워커 기준 · " +
            "2워커를 자동 사용하지 않으며 처리량·성능을 보장하지 않습니다."
    }

    private fun saturatingAdd(left: Long, right: Long): Long = when {
        left < 0L || right < 0L -> Long.MAX_VALUE
        left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        else -> left + right
    }

    private fun saturatingSubtract(left: Long, right: Long): Long = when {
        left <= 0L -> 0L
        right <= 0L -> left
        right >= left -> 0L
        else -> left - right
    }
}
