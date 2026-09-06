package app.guidecast.provider.gemma.translation

/**
 * Stops automatic worker recreation after a definite in-flight native-process death.
 *
 * This is deliberately process-local and model-scoped. A normal timeout does not trip it, and a
 * successful explicit runtime self-test is the only operation that rearms the selected model.
 */
internal class GemmaNativeCrashCircuitBreaker {
    private val lock = Any()
    private val blockedModelIds = mutableSetOf<String>()

    fun isBlocked(modelId: String): Boolean = synchronized(lock) {
        modelId in blockedModelIds
    }

    fun requireAutomaticAttemptAllowed(modelId: String) {
        check(!isBlocked(modelId)) {
            "Gemma native 작업 공간이 실행 중 종료되어 자동 재시도를 중지했습니다. " +
                "모델 파일 손상 여부는 확인되지 않았습니다. 설정에서 명시적으로 모델을 다시 점검하세요."
        }
    }

    fun recordDefiniteWorkerDeath(modelId: String) {
        synchronized(lock) { blockedModelIds += modelId }
    }

    fun rearmAfterSuccessfulExplicitVerification(modelId: String) {
        synchronized(lock) { blockedModelIds -= modelId }
    }
}

internal fun shouldLatchGemmaNativeWorkerDeath(
    nativeSubmissionPlanned: Boolean,
    nativeFinished: Boolean,
): Boolean = nativeSubmissionPlanned && !nativeFinished

internal class GemmaNativeWorkerDiedException : IllegalStateException(
    "Gemma 추론 작업 공간이 요청 중 종료되었습니다.",
)
