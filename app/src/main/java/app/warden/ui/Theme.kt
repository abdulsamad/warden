package app.warden.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import kotlin.math.pow

/*
 * Brand fallback for Android 8-11, which has no wallpaper colors. Every role is set: anything left
 * out falls back to Material's default purple, which used to leak into cards, tonal buttons and
 * outlines. Tones come from the Material color utilities' palettes for the brand blue #2B5CE6.
 * Light primary is tone 40 (#1A51DC) rather than the seed itself: the seed is a little too light
 * for small text on cards (4.3:1), tone 40 passes WCAG AA (5:1).
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A51DC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001456),
    inversePrimary = Color(0xFFB4C4FF),
    secondary = Color(0xFF585E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEE1F9),
    onSecondaryContainer = Color(0xFF161B2C),
    tertiary = Color(0xFF2E7D5B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA4F3CA),
    onTertiaryContainer = Color(0xFF002113),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF1A51DC),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9DE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F3F7),
    surfaceContainer = Color(0xFFEFEDF1),
    surfaceContainerHigh = Color(0xFFEAE7EC),
    surfaceContainerHighest = Color(0xFFE4E1E6),
)

// The neutral surfaces here are the standard dark tones; amoled() takes them down to true black.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C4FF),
    onPrimary = Color(0xFF002B75),
    primaryContainer = Color(0xFF0E42A6),
    onPrimaryContainer = Color(0xFFDCE1FF),
    inversePrimary = Color(0xFF1A51DC),
    secondary = Color(0xFFC0C6DD),
    onSecondary = Color(0xFF2B3042),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFF93D5AE),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF005236),
    onTertiaryContainer = Color(0xFFA4F3CA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceTint = Color(0xFFB4C4FF),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF303034),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    surfaceBright = Color(0xFF39393C),
    surfaceDim = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A2A2D),
    surfaceContainerHighest = Color(0xFF343438),
)

/**
 * Follows the system light/dark setting. Android 12+ takes its colors from the wallpaper; older
 * versions use the brand palettes above. Dark is always true black (see [amoled]).
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    // A wallpaper or dark-mode change recreates the activity, so this only needs to key on these.
    val colors = remember(context, dark) {
        val scheme = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> DarkColors
            else -> LightColors
        }
        if (dark) scheme.amoled() else scheme
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/**
 * True black for OLED screens: the background and the top bar are pure black, so those pixels are
 * off. Each surface level keeps its own hue (wallpaper or brand) but drops to a few tones above
 * black. Cards, dialogs, sheets and menus still stand apart, without the usual grey slabs. Text and
 * accent colors are unchanged, so every text-on-surface pairing gains contrast.
 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    // Bottom sheet.
    surfaceContainerLow = surfaceContainerLow.atTone(6f),
    // Top bar once content scrolls under it, menus.
    surfaceContainer = surfaceContainer.atTone(8f),
    surfaceContainerHigh = surfaceContainerHigh.atTone(10f),
    // Cards and dialogs.
    surfaceContainerHighest = surfaceContainerHighest.atTone(12f),
    surfaceBright = surfaceBright.atTone(18f),
    // Inset panels inside cards (the adb command), and the idle status card.
    surfaceVariant = surfaceVariant.atTone(18f),
)

/**
 * This color's hue and saturation at Material tone [tone] (CIE L*, 0-100). Scaling linear RGB
 * scales luminance without moving the color's chromaticity, so a wallpaper tint stays the same tint.
 */
private fun Color.atTone(tone: Float): Color {
    val targetY = if (tone > 8f) ((tone + 16f) / 116f).pow(3) else tone / 903.2963f
    val y = luminance()
    if (y <= 0f) return Color.Black
    val k = targetY / y
    val linear = convert(ColorSpaces.LinearSrgb)
    return Color(
        red = (linear.red * k).coerceIn(0f, 1f),
        green = (linear.green * k).coerceIn(0f, 1f),
        blue = (linear.blue * k).coerceIn(0f, 1f),
        alpha = alpha,
        colorSpace = ColorSpaces.LinearSrgb,
    ).convert(ColorSpaces.Srgb)
}
