package app.guidecast.transmitter

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

enum class TranslationRunStage(val operatorLabel: String) {
    STARTING("통역 시작"),
    SPEECH_MODEL("한국어 음성인식 준비"),
    TRANSLATION_MODEL("번역 모델 준비"),
    VOICE_MODEL("통역 음성 준비"),
    LISTENING("한국어 음성인식 실행"),
    TRANSLATING("번역 실행"),
    SPEAKING("통역 음성 생성"),
    RUNNING("실시간 통역 실행"),
}

/** Stores no speech or transcript; only the last native-pipeline stage and Android exit reason. */
class TranslationSessionDiagnostics(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun begin(stage: TranslationRunStage = TranslationRunStage.STARTING) {
        preferences.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_STAGE, stage.name)
            .commit()
    }

    @Synchronized
    fun stage(stage: TranslationRunStage) {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return
        preferences.edit().putString(KEY_STAGE, stage.name).commit()
    }

    @Synchronized
    fun end() {
        preferences.edit()
            .remove(KEY_ACTIVE)
            .remove(KEY_STARTED_AT)
            .remove(KEY_STAGE)
            .commit()
    }

    @Synchronized
    fun consumePreviousInterruptedSession(): String? {
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return null
        val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)
        val stage = runCatching {
            TranslationRunStage.valueOf(
                preferences.getString(KEY_STAGE, null) ?: TranslationRunStage.STARTING.name,
            )
        }.getOrDefault(TranslationRunStage.STARTING)
        val exitOutcome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            findRecentExitOutcome(startedAt)
        } else {
            null
        }
        end()

        if (exitOutcome?.expected == true) return null
        val reason = exitOutcome?.operatorReason
            ?: "완료되지 않은 실행 세션"
        return "이전 통역이 '${stage.operatorLabel}' 단계에서 종료됐습니다 · $reason. " +
            "앱은 안전 경로로 다시 시작됐습니다. 입력과 통역 시험을 확인하세요."
    }

    @TargetApi(Build.VERSION_CODES.R)
    private fun findRecentExitOutcome(startedAt: Long): ExitOutcome? {
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val exits = activityManager
            .getHistoricalProcessExitReasons(applicationContext.packageName, 0, 32)
            .asSequence()
            .filter { it.timestamp + EXIT_TIMESTAMP_TOLERANCE_MILLIS >= startedAt }
            .sortedByDescending(ApplicationExitInfo::getTimestamp)
            .toList()
        val exit = exits.firstOrNull { it.processName == applicationContext.packageName }
            ?: exits.firstOrNull()
            ?: return null
        val operatorReason = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "네이티브 음성 엔진 오류"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "단말 메모리 부족"
            ApplicationExitInfo.REASON_CRASH -> "앱 실행 오류"
            ApplicationExitInfo.REASON_ANR -> "앱 응답 지연"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "단말 자원 제한"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "엔진 초기화 실패"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "보조 음성 작업 종료"
            ApplicationExitInfo.REASON_SIGNALED -> "시스템 신호로 종료"
            else -> "Android 종료 원인 코드 ${exit.reason}"
        }
        return ExitOutcome(
            expected = exit.reason in setOf(
                ApplicationExitInfo.REASON_EXIT_SELF,
                ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_USER_STOPPED,
            ),
            operatorReason = operatorReason,
        )
    }

    private data class ExitOutcome(
        val expected: Boolean,
        val operatorReason: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "guidecast_translation_session"
        const val KEY_ACTIVE = "active"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_STAGE = "stage"
        const val EXIT_TIMESTAMP_TOLERANCE_MILLIS = 5_000L
    }
}
