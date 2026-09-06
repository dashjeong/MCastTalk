package app.guidecast.transmitter

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val GuideCastSuccess = Color(0xFF146B52)
internal val GuideCastSuccessContainer = Color(0xFFDCEFE7)
internal val GuideCastWarning = Color(0xFF765A00)
internal val GuideCastWarningContainer = Color(0xFFFFF0C2)
internal val GuideCastError = Color(0xFFB3261E)
internal val GuideCastMuted = Color(0xFF52605A)
internal val GuideCastLiveSurface = Color(0xFF173E34)
internal val GuideCastLiveChip = Color(0xFF245447)
internal val GuideCastLiveAccent = Color(0xFFA8F0D1)
internal val GuideCastLiveMuted = Color(0xFFC9DDD5)
internal val GuideCastLiveDivider = Color(0xFF3A665A)

private val GuideCastTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

private val GuideCastShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
internal fun destructiveOutlinedButtonColors() = ButtonDefaults.outlinedButtonColors(
    contentColor = MaterialTheme.colorScheme.error,
)

@Composable
internal fun GuideCastTheme(content: @Composable () -> Unit) {
    // A single daylight palette keeps field status colors predictable on Note9 through S23.
    // Every Material role is specified so components never fall back to the default purple set.
    val colorScheme = lightColorScheme(
        primary = Color(0xFF146B52),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCEFE7),
        onPrimaryContainer = Color(0xFF10382C),
        secondary = Color(0xFF52655D),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE0EAE5),
        onSecondaryContainer = Color(0xFF24352F),
        tertiary = Color(0xFF4E6470),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFDCE8EE),
        onTertiaryContainer = Color(0xFF233942),
        background = Color(0xFFF5F7F4),
        onBackground = Color(0xFF171C19),
        surface = Color.White,
        onSurface = Color(0xFF171C19),
        surfaceVariant = Color(0xFFE9EDE9),
        onSurfaceVariant = Color(0xFF52605A),
        surfaceTint = Color(0xFF146B52),
        inverseSurface = Color(0xFF2C322F),
        inverseOnSurface = Color(0xFFF0F2EF),
        inversePrimary = Color(0xFFA6D7C3),
        outline = Color(0xFF747F79),
        outlineVariant = Color(0xFFD8DEDA),
        scrim = Color.Black,
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        surfaceDim = Color(0xFFD9DEDA),
        surfaceBright = Color(0xFFFBFDFB),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F3F0),
        surfaceContainer = Color(0xFFEAEEEB),
        surfaceContainerHigh = Color(0xFFE5EAE6),
        surfaceContainerHighest = Color.White,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = GuideCastTypography,
        shapes = GuideCastShapes,
        content = content,
    )
}
