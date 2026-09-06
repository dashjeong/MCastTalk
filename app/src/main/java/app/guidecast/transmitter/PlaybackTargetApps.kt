package app.guidecast.transmitter

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

data class PlaybackTargetApp(
    val packageName: String,
    val uid: Int,
    val label: String,
)

private const val PLAYBACK_TARGET_PREFERENCES = "playback-targets"
private const val REGISTERED_PACKAGES = "registered-packages"
private val KNOWN_PLAYBACK_PACKAGES = setOf("com.samsung.android.app.interpreter")
private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

internal fun isValidAndroidPackageName(value: String): Boolean =
    value.length in 3..255 && PACKAGE_NAME.matches(value)

/** Returns launcher apps plus installed packages explicitly registered by the operator. */
fun Context.playbackTargetApps(): List<PlaybackTargetApp> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0L),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(launcherIntent, 0)
    }
    val launcherApps = resolved.mapNotNull { info ->
        val applicationInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
        if (applicationInfo.packageName == packageName) return@mapNotNull null
        applicationInfo.toPlaybackTargetApp(info.loadLabel(packageManager).toString())
    }
    val explicitPackages = KNOWN_PLAYBACK_PACKAGES + getSharedPreferences(
        PLAYBACK_TARGET_PREFERENCES,
        Context.MODE_PRIVATE,
    ).getStringSet(REGISTERED_PACKAGES, emptySet()).orEmpty()
    return (launcherApps + explicitPackages.mapNotNull(::installedPlaybackTargetApp))
        .distinctBy(PlaybackTargetApp::packageName)
        .sortedBy { it.label.lowercase() }
}

fun Context.registerPlaybackTargetApp(rawPackageName: String): Result<PlaybackTargetApp> =
    runCatching {
        val packageName = rawPackageName.trim()
        require(isValidAndroidPackageName(packageName)) {
            "패키지명 형식이 올바르지 않습니다."
        }
        require(packageName != this.packageName) {
            "MCastTalk 앱 자체는 출력 대상으로 등록할 수 없습니다."
        }
        val target = installedPlaybackTargetApp(packageName)
            ?: error("설치된 앱을 찾지 못했거나 Android가 이 패키지를 공개하지 않았습니다.")
        val preferences = getSharedPreferences(PLAYBACK_TARGET_PREFERENCES, Context.MODE_PRIVATE)
        val registered = preferences.getStringSet(REGISTERED_PACKAGES, emptySet())
            .orEmpty()
            .toMutableSet()
            .apply { add(packageName) }
        check(preferences.edit().putStringSet(REGISTERED_PACKAGES, registered).commit()) {
            "출력 대상 앱 등록을 저장하지 못했습니다."
        }
        target
    }

private fun Context.installedPlaybackTargetApp(packageName: String): PlaybackTargetApp? =
    runCatching {
        val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        if (applicationInfo.packageName == this.packageName) return@runCatching null
        applicationInfo.toPlaybackTargetApp(
            packageManager.getApplicationLabel(applicationInfo).toString(),
        )
    }.getOrNull()

private fun ApplicationInfo.toPlaybackTargetApp(rawLabel: String) = PlaybackTargetApp(
    packageName = packageName,
    uid = uid,
    label = rawLabel.trim().ifBlank { packageName },
)
