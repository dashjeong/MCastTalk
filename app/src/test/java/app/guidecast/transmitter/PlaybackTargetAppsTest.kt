package app.guidecast.transmitter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTargetAppsTest {
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
