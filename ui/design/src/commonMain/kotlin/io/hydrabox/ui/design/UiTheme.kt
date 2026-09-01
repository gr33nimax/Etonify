package io.hydrabox.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ComponentLevel { EXPRESSIVE, STANDARD }

enum class MotionScheme { SPRING, DURATION }

data class UiCapabilities(
    val components: ComponentLevel,
    val motion: MotionScheme,
    val reducedMotion: Boolean = false,
)

data class UiMotion(
    val largeTransitionMillis: Int,
    val enterMillis: Int,
    val exitMillis: Int,
    val standardMillis: Int,
    val enterEasing: Easing,
    val exitEasing: Easing,
)

object UiTokens {
    val spacing: Dp = 8.dp
    val radii: List<Dp> = listOf(0.dp, 4.dp, 8.dp, 12.dp, 16.dp, 20.dp, 24.dp, 28.dp, 32.dp)

    /** One shape per role, all nine radii from one list. Rounder than stock M3 on purpose:
     *  the connection control is a circle, and the containers around it echo it. */
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(radii[1]),
        small = RoundedCornerShape(radii[2]),
        medium = RoundedCornerShape(radii[4]),
        large = RoundedCornerShape(radii[6]),
        extraLarge = RoundedCornerShape(radii[8]),
    )
}

private val BrandSeed = Color.hsl(hue = 141f, saturation = 0.65f, lightness = 0.25f)
private val StandardCapabilities = UiCapabilities(ComponentLevel.STANDARD, MotionScheme.DURATION)

val LocalUiCapabilities = staticCompositionLocalOf { StandardCapabilities }
val LocalUiMotion = staticCompositionLocalOf { durationMotion(false) }

@Composable
fun HydraTheme(
    dark: Boolean = isSystemInDarkTheme(),
    capabilities: UiCapabilities = uiCapabilities(),
    content: @Composable () -> Unit,
) {
    val scheme = platformColorScheme(dark)
        ?: if (dark) darkColorScheme(primary = BrandSeed) else lightColorScheme(primary = BrandSeed)
    CompositionLocalProvider(
        LocalUiCapabilities provides capabilities,
        LocalUiMotion provides if (capabilities.motion == MotionScheme.SPRING) springMotion(capabilities.reducedMotion) else durationMotion(capabilities.reducedMotion),
    ) {
        // `MaterialExpressiveTheme` is internal in Compose Multiplatform 1.8.2, so the
        // expressive language is carried by our own tokens: shape scale, tonal surfaces,
        // typography roles and the spring motion above. Recorded in the substitution table.
        MaterialTheme(colorScheme = scheme, shapes = UiTokens.shapes, content = content)
    }
}

private fun springMotion(reduced: Boolean) = if (reduced) durationMotion(true) else UiMotion(
    largeTransitionMillis = 500,
    enterMillis = 400,
    exitMillis = 200,
    standardMillis = 300,
    enterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    exitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
)

private fun durationMotion(reduced: Boolean) = UiMotion(
    largeTransitionMillis = if (reduced) 0 else 500,
    enterMillis = if (reduced) 0 else 400,
    exitMillis = if (reduced) 0 else 200,
    standardMillis = if (reduced) 0 else 300,
    enterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    exitEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f),
)

expect fun uiCapabilities(): UiCapabilities
