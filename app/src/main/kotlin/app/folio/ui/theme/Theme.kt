package app.folio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Folio's identity: "ink on paper". A deep ink-navy primary, warm paper neutrals,
 * and a single vermilion accent reserved for emphasis (active page, highlights).
 * Dynamic colour is intentionally not used so the brand stays consistent.
 */

private val Ink = Color(0xFF1D2B53)
private val InkSoft = Color(0xFF3B4C7E)
private val Vermilion = Color(0xFFE07A3F)
private val Paper = Color(0xFFF6F4EF)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3F4),
    onPrimaryContainer = Color(0xFF101A38),
    secondary = InkSoft,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8EF),
    onSecondaryContainer = Color(0xFF1C2233),
    tertiary = Color(0xFFB4561F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBE3D3),
    onTertiaryContainer = Color(0xFF3B1A06),
    background = Paper,
    onBackground = Color(0xFF1A1C21),
    surface = Paper,
    onSurface = Color(0xFF1A1C21),
    surfaceVariant = Color(0xFFE7E4DC),
    onSurfaceVariant = Color(0xFF5C5E66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF7),
    surfaceContainer = Color(0xFFEFECE5),
    surfaceContainerHigh = Color(0xFFE9E6DE),
    surfaceContainerHighest = Color(0xFFE3E0D8),
    outline = Color(0xFFB9B6AE),
    outlineVariant = Color(0xFFDAD6CD),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C3F0),
    onPrimary = Color(0xFF142152),
    primaryContainer = Color(0xFF2D3B66),
    onPrimaryContainer = Color(0xFFDDE3F4),
    secondary = Color(0xFFC2C7D6),
    onSecondary = Color(0xFF2B3040),
    secondaryContainer = Color(0xFF353A4A),
    onSecondaryContainer = Color(0xFFDFE2EC),
    tertiary = Color(0xFFF2A577),
    onTertiary = Color(0xFF4A2008),
    tertiaryContainer = Color(0xFF6A3415),
    onTertiaryContainer = Color(0xFFFBE3D3),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2A2D35),
    onSurfaceVariant = Color(0xFFA9ABB4),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF181A20),
    surfaceContainer = Color(0xFF1C1F25),
    surfaceContainerHigh = Color(0xFF23262D),
    surfaceContainerHighest = Color(0xFF2B2E36),
    outline = Color(0xFF4A4D57),
    outlineVariant = Color(0xFF33363F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Colours outside the Material roles. */
@Immutable
data class FolioExtras(
    val accent: Color,
    val pageCanvas: Color,
    val pageShadow: Color,
)

val LocalFolioExtras = staticCompositionLocalOf {
    FolioExtras(accent = Vermilion, pageCanvas = Color(0xFFE4E1D9), pageShadow = Color(0x33000000))
}

private val Sans = FontFamily.SansSerif

private val FolioTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FolioTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors: ColorScheme = if (darkTheme) DarkColors else LightColors
    val extras = if (darkTheme) {
        FolioExtras(accent = Color(0xFFF2A577), pageCanvas = Color(0xFF0C0E12), pageShadow = Color(0x66000000))
    } else {
        FolioExtras(accent = Vermilion, pageCanvas = Color(0xFFE4E1D9), pageShadow = Color(0x26000000))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFolioExtras provides extras) {
        MaterialTheme(colorScheme = colors, typography = FolioTypography, shapes = FolioShapes, content = content)
    }
}
