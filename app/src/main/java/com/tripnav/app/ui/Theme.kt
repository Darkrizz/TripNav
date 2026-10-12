package com.tripnav.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tripnav.app.R

/**
 * TripNav design tokens: a graphite dark theme with a single ember accent. Text colours are chosen for
 * at-a-glance reading on a handlebar mount: TextHi ≈ 17:1, TextMid ≈ 8:1, TextLo ≈ 4.6:1 on [Bg].
 */
object TN {
    val Bg = Color(0xFF090B0F)
    val Surface1 = Color(0xFF12151B)   // panels
    val Surface2 = Color(0xFF1A1E26)   // raised controls, inputs
    val Surface3 = Color(0xFF242A34)   // pressed / selected
    val Outline = Color(0xFF2B313C)
    val OutlineStrong = Color(0xFF3A4250)

    val TextHi = Color(0xFFF3F5F8)
    val TextMid = Color(0xFFAEB6C2)
    val TextLo = Color(0xFF7A8391)

    val Ember = Color(0xFFFF6A1F)
    val EmberHot = Color(0xFFFF9447)
    val Info = Color(0xFF47D7FF)
    val Go = Color(0xFF2EDC85)
    val Warn = Color(0xFFFFC53D)
    val Stop = Color(0xFFFF4D5A)

    /** Translucent glass for overlays drawn on top of the map. */
    val Glass = Color(0xEB12151B)

    val EmberGradient = Brush.horizontalGradient(listOf(Ember, EmberHot))

    /** Brand brass from the logo; kept for the badge, the loading screen and the wordmark. */
    val Brass = Color(0xFFD9A84A)
    val BrassGradient = Brush.linearGradient(listOf(Color(0xFFF9E3A0), Brass, Color(0xFF8F6020)))
    /** Matches the system splash background (res/values/themes.xml). */
    val Splash = Color(0xFF060708)
}

/** Barlow: highway-signage roots, very legible at speed; Condensed for headlines and big numerals. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)
val BarlowCondensed = FontFamily(
    Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlowcondensed_bold, FontWeight.Bold),
    Font(R.font.barlowcondensed_extrabold, FontWeight.ExtraBold),
    Font(R.font.barlowcondensed_extrabold, FontWeight.Black),
)

private val Scheme = darkColorScheme(
    primary = TN.Ember, onPrimary = Color(0xFF1A0A00),
    primaryContainer = Color(0xFF3A1A08), onPrimaryContainer = TN.EmberHot,
    secondary = TN.Info, onSecondary = Color(0xFF00222C),
    secondaryContainer = TN.Surface3, onSecondaryContainer = TN.TextHi,
    tertiary = TN.Go, onTertiary = Color(0xFF00210F),
    tertiaryContainer = Color(0xFF0E2E1E), onTertiaryContainer = TN.Go,
    background = TN.Bg, onBackground = TN.TextHi,
    surface = TN.Bg, onSurface = TN.TextHi,
    surfaceVariant = TN.Surface2, onSurfaceVariant = TN.TextMid,
    surfaceTint = Color.Transparent,
    surfaceDim = TN.Bg, surfaceBright = TN.Surface3,
    surfaceContainerLowest = TN.Bg, surfaceContainerLow = TN.Surface1, surfaceContainer = TN.Surface1,
    surfaceContainerHigh = TN.Surface2, surfaceContainerHighest = TN.Surface3,
    inverseSurface = TN.TextHi, inverseOnSurface = TN.Bg, inversePrimary = Color(0xFFB84300),
    outline = TN.OutlineStrong, outlineVariant = TN.Outline,
    error = TN.Stop, onError = Color.White, errorContainer = Color(0xFF3B0D12), onErrorContainer = Color(0xFFFFB3B8),
    scrim = Color.Black,
)

private fun body(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = size + 6) =
    TextStyle(fontFamily = Barlow, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

private fun cond(size: Int, weight: FontWeight = FontWeight.Bold, spacing: Double = 0.0) =
    TextStyle(fontFamily = BarlowCondensed, fontWeight = weight, fontSize = size.sp, lineHeight = (size * 1.1).sp, letterSpacing = spacing.sp)

private val Type = Typography(
    displayLarge = cond(64, FontWeight.ExtraBold),
    displayMedium = cond(48, FontWeight.ExtraBold),
    displaySmall = cond(40, FontWeight.ExtraBold),
    headlineLarge = cond(34, FontWeight.ExtraBold, 0.2),
    headlineMedium = cond(30, FontWeight.ExtraBold, 0.2),
    headlineSmall = cond(24, FontWeight.Bold),
    titleLarge = body(21, FontWeight.SemiBold, 26),
    titleMedium = body(17, FontWeight.SemiBold, 22),
    titleSmall = body(15, FontWeight.SemiBold, 20),
    bodyLarge = body(17, line = 24),
    bodyMedium = body(15, line = 21),
    bodySmall = body(13, line = 18),
    labelLarge = body(15, FontWeight.SemiBold, 20),
    labelMedium = body(13, FontWeight.SemiBold, 16),
    labelSmall = TextStyle(fontFamily = Barlow, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp),
)

private val Shape = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** TripNav is always dark: it's used at night, in tunnels and on a handlebar, and the map has its own day style. */
@Composable
fun TripNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, shapes = Shape, content = content)
}
