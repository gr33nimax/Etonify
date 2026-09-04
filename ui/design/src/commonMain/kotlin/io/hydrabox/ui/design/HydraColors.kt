package io.hydrabox.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The app's own colours.
 *
 * They exist because the alternative was worse: on Android 12 and above the theme always took
 * the system's dynamic palette, and a device whose wallpaper is dark olive produces a scheme
 * where `primary` and `surface` are two shades of the same grey-green. A connected tunnel then
 * looks like a disconnected one, which is the one thing this interface must never do.
 *
 * The scale is a green seed resolved into the Material roles by hand rather than generated: the
 * palette is small, it is the brand, and it should not change under us.
 */
internal fun hydraDarkScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF7DDBA3),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF00522F),
    onPrimaryContainer = Color(0xFF99F7BE),
    inversePrimary = Color(0xFF00693C),
    secondary = Color(0xFFB6CCBB),
    onSecondary = Color(0xFF21352A),
    secondaryContainer = Color(0xFF374B3F),
    onSecondaryContainer = Color(0xFFD2E8D7),
    tertiary = Color(0xFFF1C48C),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF614000),
    onTertiaryContainer = Color(0xFFFFDDB3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101410),
    onBackground = Color(0xFFE0E4DC),
    surface = Color(0xFF101410),
    onSurface = Color(0xFFE0E4DC),
    surfaceVariant = Color(0xFF414940),
    onSurfaceVariant = Color(0xFFC0C9BE),
    surfaceTint = Color(0xFF7DDBA3),
    inverseSurface = Color(0xFFE0E4DC),
    inverseOnSurface = Color(0xFF2D322C),
    outline = Color(0xFF8A9388),
    outlineVariant = Color(0xFF414940),
    surfaceBright = Color(0xFF363B35),
    surfaceDim = Color(0xFF101410),
    surfaceContainerLowest = Color(0xFF0B0F0B),
    surfaceContainerLow = Color(0xFF171B16),
    surfaceContainer = Color(0xFF1B1F1A),
    surfaceContainerHigh = Color(0xFF252A24),
    surfaceContainerHighest = Color(0xFF30352F),
)

internal fun hydraLightScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF00693C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF99F7BE),
    onPrimaryContainer = Color(0xFF00210F),
    inversePrimary = Color(0xFF7DDBA3),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D7),
    onSecondaryContainer = Color(0xFF0C1F14),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF6FBF3),
    onBackground = Color(0xFF181D18),
    surface = Color(0xFFF6FBF3),
    onSurface = Color(0xFF181D18),
    surfaceVariant = Color(0xFFDDE5DA),
    onSurfaceVariant = Color(0xFF414940),
    surfaceTint = Color(0xFF00693C),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2EA),
    outline = Color(0xFF717971),
    outlineVariant = Color(0xFFC0C9BE),
    surfaceBright = Color(0xFFF6FBF3),
    surfaceDim = Color(0xFFD7DBD4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5ED),
    surfaceContainer = Color(0xFFEBF0E8),
    surfaceContainerHigh = Color(0xFFE5EAE2),
    surfaceContainerHighest = Color(0xFFDFE4DC),
)
