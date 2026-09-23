package app.guidecast.transmitter

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

internal fun refreshTestAccessibilityCache() {
    // API 35 regression evidence: the screenshot showed "녹음만 시작" after a mode toggle,
    // while the cached hierarchy retained "녹음·받아쓰기 시작" beside the new mode's footer.
    // Refresh only the test connection; keep the actual label/action assertions unchanged.
    // clearCache is a public API from Android 14, so older test devices keep their existing path.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
    }
}

/** A constrained screen may also expose horizontal chips; target the tallest viewport. */
internal fun UiDevice.findTextByVerticalScroll(label: String): UiObject2? {
    val selector = By.text(label)
    // Starting a broadcast moves its controls above the previous idle scroll position.
    // Search both directions, as a user does, rather than declaring an above-screen control absent.
    fun visibleRows(viewport: UiObject2): List<Pair<String, Rect>>? = runCatching {
        viewport.findObjects(By.text(java.util.regex.Pattern.compile(".+")))
            .map { it.text to it.visibleBounds }
    }.getOrNull()
    for (towardTop in listOf(true, false)) for (attempt in 0 until 24) {
        waitForIdle()
        refreshTestAccessibilityCache()
        wait(Until.findObject(selector), 500L)?.let { return it }
        val viewport = findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }
        val bounds = viewport?.visibleBounds ?: break
        if (bounds.height() > 0 && bounds.width() > 0) {
            val before = visibleRows(viewport)
            val upper = bounds.top + bounds.height() * 3 / 10
            val lower = bounds.top + bounds.height() * 8 / 10
            // Some Compose viewports omit scroll events; inspect visible content instead of
            // treating UiObject2.scroll's event timeout as reaching the end of the page.
            swipe(bounds.centerX(), if (towardTop) upper else lower,
                bounds.centerX(), if (towardTop) lower else upper, 30)
            waitForIdle()
            refreshTestAccessibilityCache()
            wait(Until.findObject(selector), 500L)?.let { return it }
            val after = findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() }?.let(::visibleRows)
            if (!before.isNullOrEmpty() && before == after) break
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
        refreshTestAccessibilityCache()
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
    refreshTestAccessibilityCache()
}
