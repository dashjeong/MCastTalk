package app.guidecast.transmitter

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/** A constrained screen may also expose horizontal chips; target the tallest viewport. */
internal fun UiDevice.findTextByVerticalScroll(label: String): UiObject2? {
    val selector = By.text(label)
    repeat(24) {
        waitForIdle()
        wait(Until.findObject(selector), 500L)?.let { return it }
        val viewport = findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
        val bounds = viewport?.visibleBounds ?: return@repeat
        if (bounds.height() > 0 && bounds.width() > 0) {
            swipe(bounds.centerX(), bounds.top + bounds.height() * 8 / 10,
                bounds.centerX(), bounds.top + bounds.height() * 3 / 10, 30)
            waitForIdle()
        }
    }
    val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
    if (directory != null) {
        takeScreenshot(File(directory, "synthetic-scroll-${label.hashCode()}.png"))
        dumpWindowHierarchy(File(directory, "synthetic-scroll-${label.hashCode()}.xml"))
    }
    return null
}

/** Compose exposes button labels as non-clickable children of the actual click target. */
internal fun UiDevice.clickTextControl(label: String) {
    waitForIdle()
    requireNotNull(findTextByVerticalScroll(label)) { "Control is unreachable: $label" }
    // Accessibility idle does not imply that a Compose fling has finished. In a 320dp
    // viewport a first tap can stop the remaining scroll instead of opening the editor.
    // Observe a stationary label before one actual tap; never retry a failed assertion.
    var previous: Rect? = null
    var stableSamples = 0
    var settled: UiObject2? = null
    val deadline = SystemClock.uptimeMillis() + 3_000L
    while (SystemClock.uptimeMillis() < deadline) {
        val candidate = findObject(By.text(label))
        val bounds = candidate?.visibleBounds
        stableSamples = if (bounds != null && !bounds.isEmpty && bounds == previous) stableSamples + 1 else 0
        previous = bounds
        if (stableSamples >= 6) { settled = candidate; break }
        SystemClock.sleep(75L)
    }
    var target = requireNotNull(settled) { "Control did not settle before touch: $label" }
    while (!target.isClickable && target.parent != null) target = requireNotNull(target.parent)
    check(target.isClickable && target.isEnabled) { "Control is not actionable: $label" }
    target.click()
    waitForIdle()
}
