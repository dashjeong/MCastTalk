package app.guidecast.transmitter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTargetAppsTest {
    @org.junit.Test
    fun `search matches names packages and all query words without changing source list`() {
        val apps = listOf(PlaybackTargetApp("com.example.music", 10, "음악 재생"),
            PlaybackTargetApp("com.example.video", 11, "Video Player"))
        org.junit.Assert.assertEquals(apps.take(1), filterPlaybackApps(apps, "음악"))
        org.junit.Assert.assertEquals(apps.drop(1), filterPlaybackApps(apps, " VIDEO  player "))
        org.junit.Assert.assertEquals(apps.take(1), filterPlaybackApps(apps, "example MUSIC"))
        org.junit.Assert.assertTrue(filterPlaybackApps(apps, "missing").isEmpty())
        org.junit.Assert.assertEquals(apps, filterPlaybackApps(apps, "  "))
    }
    @Test
    fun samsungInterpreterPackageIsAcceptedForManualRegistration() {
        assertTrue(isValidAndroidPackageName("com.samsung.android.app.interpreter"))
    }

    @Test
    fun malformedOrCommandLikePackageNamesAreRejected() {
        listOf(
            "",
            "interpreter",
            "com.samsung/interpreter",
            "com.samsung.android.app.interpreter;id",
            ".com.samsung",
        ).forEach { packageName ->
            assertFalse(packageName, isValidAndroidPackageName(packageName))
        }
    }
}
