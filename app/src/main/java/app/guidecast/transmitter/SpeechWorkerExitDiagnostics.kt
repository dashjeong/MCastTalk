package app.guidecast.transmitter

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Own UID metadata only. No audio, transcript, tombstone payload, or other app data is read. */
internal fun recentSpeechWorkerExit(context: Context, language: String): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val expectedProcess = "${context.packageName}:tts_$language"
    val since = System.currentTimeMillis() - 10 * 60_000L
    val manager = context.getSystemService(ActivityManager::class.java) ?: return null
    val exit = manager
        .getHistoricalProcessExitReasons(context.packageName, 0, 32)
        .filter { it.processName == expectedProcess && it.timestamp >= since }
        .maxByOrNull { it.timestamp } ?: return null
    val reason = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "네이티브 엔진 오류"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "메모리 부족으로 종료"
        ApplicationExitInfo.REASON_CRASH -> "실행 예외"
        ApplicationExitInfo.REASON_ANR -> "응답 지연"
        ApplicationExitInfo.REASON_SIGNALED -> "시스템 신호 ${exit.status}"
        else -> "종료 코드 ${exit.reason}/${exit.status}"
    }
    return "Android 최근 $language 작업자 기록: $reason (PID ${exit.pid})"
}
