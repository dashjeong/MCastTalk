package app.guidecast.transmitter

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCastDesignSystemRegressionTest {
    private val source by lazy(::mainActivitySource)

    @Test
    fun coreForegroundBackgroundPairsMeetWcagAaContrast() {
        val themeSource = source.substring(
            startIndex = source.indexOf("val colorScheme = lightColorScheme(").also {
                check(it >= 0) { "GuideCast light color scheme was not found." }
            },
            endIndex = source.indexOf("\n    MaterialTheme(").also {
                check(it >= 0) { "GuideCast MaterialTheme application was not found." }
            },
        )
        val colors = namedColors(source) + namedColors(themeSource)
        val pairs = listOf(
            ContrastPair("primary action", "onPrimary", "primary"),
            ContrastPair("primary container", "onPrimaryContainer", "primaryContainer"),
            ContrastPair("secondary action", "onSecondary", "secondary"),
            ContrastPair("secondary container", "onSecondaryContainer", "secondaryContainer"),
            ContrastPair("tertiary action", "onTertiary", "tertiary"),
            ContrastPair("tertiary container", "onTertiaryContainer", "tertiaryContainer"),
            ContrastPair("app background", "onBackground", "background"),
            ContrastPair("default surface", "onSurface", "surface"),
            ContrastPair("secondary surface text", "onSurfaceVariant", "surfaceVariant"),
            ContrastPair("inverse surface", "inverseOnSurface", "inverseSurface"),
            ContrastPair("inverse primary", "inversePrimary", "inverseSurface"),
            ContrastPair("error action", "onError", "error"),
            ContrastPair("error container", "onErrorContainer", "errorContainer"),
            ContrastPair("success status", "GuideCastSuccess", "GuideCastSuccessContainer"),
            ContrastPair("warning status", "GuideCastWarning", "GuideCastWarningContainer"),
            ContrastPair("error status", "GuideCastError", "surface"),
            ContrastPair("muted status", "GuideCastMuted", "surface"),
            ContrastPair("live primary text", "onPrimary", "GuideCastLiveSurface"),
            ContrastPair("live accent", "GuideCastLiveAccent", "GuideCastLiveSurface"),
            ContrastPair("live secondary text", "GuideCastLiveMuted", "GuideCastLiveSurface"),
            ContrastPair("live status chip", "GuideCastLiveAccent", "GuideCastLiveChip"),
        )

        pairs.forEach { pair ->
            val foreground = requireNotNull(colors[pair.foreground]) {
                "Missing foreground color token: ${pair.foreground}"
            }
            val background = requireNotNull(colors[pair.background]) {
                "Missing background color token: ${pair.background}"
            }
            val ratio = contrastRatio(foreground, background)
            assertTrue(
                "${pair.label} contrast is ${"%.2f".format(ratio)}:1; " +
                    "${pair.foreground}/${pair.background} must be at least $WCAG_AA_NORMAL_TEXT:1.",
                ratio >= WCAG_AA_NORMAL_TEXT,
            )
        }
    }

    @Test
    fun customTouchTargetsDeclareAtLeast48DpAndPrimaryActionsDeclare56Dp() {
        listOf(
            "OperatorHeader",
            "LiveSentenceMonitor",
            "SettingsInformationCard",
            "LicenseEntryCard",
            "OfflineLicenseDocumentScreen",
            "InputChoiceCard",
            "AccessModeButton",
        ).forEach { functionName ->
            assertHasMinimumTouchHeight(functionName, minimumDp = 48)
        }

        listOf("InputControls", "BroadcastControls").forEach { functionName ->
            assertHasMinimumTouchHeight(functionName, minimumDp = 56)
        }

        val header = functionBody("OperatorHeader")
        assertTrue(
            "The compact header action must retain a 48.dp minimum width as well as height.",
            Regex("""minWidth\s*=\s*(?:4[8-9]|[5-9]\d|\d{3,})\.dp""").containsMatchIn(header),
        )
    }

    @Test
    fun materialThemeAppliesCompleteTypographyAndShapeTokenCoverage() {
        val typography = callBody("private val GuideCastTypography = Typography")
        val declaredTypographyRoles = Regex("""(?m)^\s*(\w+)\s*=\s*TextStyle\(""")
            .findAll(typography)
            .map { it.groupValues[1] }
            .toSet()
        val usedTypographyRoles = Regex("""MaterialTheme\.typography\.(\w+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val componentTypographyRoles = setOf("labelLarge")
        val missingTypographyRoles = usedTypographyRoles + componentTypographyRoles - declaredTypographyRoles
        assertTrue(
            "GuideCastTypography does not cover every app/component text role: $missingTypographyRoles",
            missingTypographyRoles.isEmpty(),
        )

        val shapes = callBody("private val GuideCastShapes = Shapes")
        val declaredShapeRoles = Regex("""(?m)^\s*(\w+)\s*=\s*RoundedCornerShape\(""")
            .findAll(shapes)
            .map { it.groupValues[1] }
            .toSet()
        val requiredShapeRoles = setOf("extraSmall", "small", "medium", "large", "extraLarge")
        assertTrue(
            "GuideCastShapes must define the complete Material shape scale; missing=" +
                (requiredShapeRoles - declaredShapeRoles),
            declaredShapeRoles.containsAll(requiredShapeRoles),
        )
        val usedShapeRoles = Regex("""MaterialTheme\.shapes\.(\w+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(
            "A used MaterialTheme shape has no GuideCast token: ${usedShapeRoles - declaredShapeRoles}",
            declaredShapeRoles.containsAll(usedShapeRoles),
        )

        val materialTheme = callBody("MaterialTheme", startAt = source.lastIndexOf("MaterialTheme("))
        assertTrue(
            "MaterialTheme must apply GuideCastTypography.",
            Regex("""typography\s*=\s*GuideCastTypography""").containsMatchIn(materialTheme),
        )
        assertTrue(
            "MaterialTheme must apply GuideCastShapes.",
            Regex("""shapes\s*=\s*GuideCastShapes""").containsMatchIn(materialTheme),
        )
    }

    private fun assertHasMinimumTouchHeight(functionName: String, minimumDp: Int) {
        val heights = Regex("""minHeight\s*=\s*(\d+)\.dp""")
            .findAll(functionBody(functionName))
            .map { it.groupValues[1].toInt() }
            .toList()
        assertTrue(
            "$functionName must declare a touch target height of at least $minimumDp.dp; found=$heights",
            heights.any { it >= minimumDp },
        )
    }

    private fun namedColors(text: String): Map<String, Long> {
        val colorAssignment = Regex(
            """(?m)^\s*(?:(?:private|internal)\s+val\s+)?(\w+)\s*=\s*""" +
                """(?:Color\(0x([0-9A-Fa-f]{8})\)|Color\.(White|Black))""",
        )
        return colorAssignment.findAll(text).associate { match ->
            val name = match.groupValues[1]
            val hex = match.groupValues[2]
            val named = match.groupValues[3]
            val argb = when {
                hex.isNotEmpty() -> hex.toLong(radix = 16)
                named == "White" -> 0xFFFFFFFFL
                named == "Black" -> 0xFF000000L
                else -> error("Unsupported color assignment: ${match.value}")
            }
            check((argb ushr 24) == 0xFFL) { "$name must be an opaque foreground/background token." }
            name to argb
        }
    }

    private fun contrastRatio(foreground: Long, background: Long): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        return (max(foregroundLuminance, backgroundLuminance) + 0.05) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun linearChannel(shift: Int): Double {
            val srgb = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (srgb <= 0.04045) {
                srgb / 12.92
            } else {
                ((srgb + 0.055) / 1.055).pow(2.4)
            }
        }
        val red = linearChannel(16)
        val green = linearChannel(8)
        val blue = linearChannel(0)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }

    private fun functionBody(functionName: String): String {
        val marker = "private fun $functionName("
        val start = source.indexOf(marker)
        check(start >= 0) { "Function not found: $functionName" }
        return braceBody(start)
    }

    private fun braceBody(startAt: Int): String {
        val openingBrace = source.indexOf('{', startIndex = startAt)
        check(openingBrace >= 0) { "Opening brace not found after source index $startAt" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("Closing brace not found after source index $startAt")
    }

    private fun callBody(marker: String, startAt: Int = source.indexOf(marker)): String {
        check(startAt >= 0) { "Call not found: $marker" }
        val openingParenthesis = source.indexOf('(', startIndex = startAt)
        check(openingParenthesis >= 0) { "Opening parenthesis not found for $marker" }
        var depth = 0
        for (index in openingParenthesis until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openingParenthesis + 1, index)
                }
            }
        }
        error("Closing parenthesis not found for $marker")
    }

    private fun mainActivitySource(): String {
        val candidates = listOf(
            File("src/main/java/app/guidecast/transmitter/MainActivity.kt"),
            File("app/src/main/java/app/guidecast/transmitter/MainActivity.kt"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("MainActivity.kt was not found: ${candidates.joinToString()}")
        return file.readText() + "\n" + File(file.parentFile, "GuideCastDesignSystem.kt").readText() +
            "\n" + File(file.parentFile, "OperatorWorkspace.kt").readText()
    }

    private data class ContrastPair(
        val label: String,
        val foreground: String,
        val background: String,
    )

    private companion object {
        const val WCAG_AA_NORMAL_TEXT = 4.5
    }
}
