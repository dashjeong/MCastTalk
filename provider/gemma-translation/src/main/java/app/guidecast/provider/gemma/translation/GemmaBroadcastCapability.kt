package app.guidecast.provider.gemma.translation

import android.app.ActivityManager
import android.content.Context

data class GemmaBroadcastCapability(
    val supported: Boolean,
    /** Uses the same single Gemma lane with conservative preparation on 6 GB-class devices. */
    val constrainedMemoryMode: Boolean,
    /** Current pressure permits unconstrained loading; false now serializes but does not veto. */
    val loadPermittedNow: Boolean,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val systemLowMemory: Boolean,
    val message: String,
) {
    companion object {
        /**
         * Android reports usable physical RAM rather than the marketing capacity. A nominal
         * 6 GB phone commonly reports a little over 5 GiB after reserved regions, while an 8 GB
         * Galaxy reports ~6.8-7.2 GiB.
         * Gemma is mmap-backed in an isolated single inference process. For 5~7GiB devices,
         * we run in constrainedMemoryMode with 1,024 tokens and a single sequential lane.
         */
        const val MIN_TOTAL_MEMORY_BYTES = 5L * 1024 * 1024 * 1024 // 5.0 GiB (~6GB physical RAM devices)
        const val STANDARD_MEMORY_BYTES = 7L * 1024 * 1024 * 1024 // 7.0 GiB (~8GB physical RAM devices)

        // The 2,588,147,712-byte model needs headroom. Initial model load requires 3 GiB.
        const val MIN_AVAILABLE_MEMORY_BYTES = 3L * 1024 * 1024 * 1024

        fun detect(context: Context): GemmaBroadcastCapability {
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            return forMemory(
                totalMemoryBytes = memory.totalMem,
                availableMemoryBytes = memory.availMem,
                systemLowMemory = memory.lowMemory,
            )
        }

        fun forTotalMemory(totalMemoryBytes: Long): GemmaBroadcastCapability = forMemory(
            totalMemoryBytes = totalMemoryBytes,
            availableMemoryBytes = totalMemoryBytes,
            systemLowMemory = false,
        )

        fun forMemory(
            totalMemoryBytes: Long,
            availableMemoryBytes: Long,
            systemLowMemory: Boolean,
        ): GemmaBroadcastCapability {
            require(totalMemoryBytes > 0L)
            require(availableMemoryBytes >= 0L)
            val supported = totalMemoryBytes >= MIN_TOTAL_MEMORY_BYTES
            val constrained = supported && totalMemoryBytes < STANDARD_MEMORY_BYTES
            val loadPermittedNow = supported && !systemLowMemory &&
                availableMemoryBytes >= MIN_AVAILABLE_MEMORY_BYTES
            val totalGiB = "%.1f".format(totalMemoryBytes / 1_073_741_824.0)
            val availableGiB = "%.1f".format(availableMemoryBytes / 1_073_741_824.0)
            return GemmaBroadcastCapability(
                supported = supported,
                constrainedMemoryMode = constrained,
                loadPermittedNow = loadPermittedNow,
                totalMemoryBytes = totalMemoryBytes,
                availableMemoryBytes = availableMemoryBytes,
                systemLowMemory = systemLowMemory,
                message = if (supported) {
                    buildString {
                        append(
                            if (constrained) {
                                "Gemma 6GB급 메모리 절약 모드"
                            } else {
                                "Gemma 실시간 방송 메모리 적합"
                            },
                        )
                        append(" · Android 인식 RAM ${totalGiB} GiB")
                        if (constrained) append(" · Gemma 우선 채널 단일 순차 실행")
                        if (!loadPermittedNow) {
                            append(
                                " · 현재 가용 ${availableGiB} GiB로 콜드 로드 순차 실행" +
                                    " · 선택한 Gemma를 실제 시도" +
                                    " · 실제 초기화 실패 시 해당 문장만 대체 번역",
                            )
                        }
                    }
                } else {
                    "Gemma 4 E2B 실시간 방송에는 Android 인식 RAM 5.0 GiB 이상이 필요합니다 " +
                        "(현재 ${totalGiB} GiB). 앱 종료를 막기 위해 " +
                        "경량 오프라인 번역으로 자동 전환합니다."
                },
            )
        }
    }
}
