package io.hydrabox.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * Motion resolved once, in the theme, and never per platform in a screen.
 *
 * Two schemes exist because a spring is the expressive default on Android while desktop
 * degrades to the MD3 curve-and-duration pair. Screens ask for a role — a state change, a
 * container appearing, a value settling — and get whichever the capabilities allow.
 */
object HydraMotion {
    /** The state of the tunnel changed: the one motion a person is meant to notice. */
    @Composable
    @ReadOnlyComposable
    fun <T> stateChange(): AnimationSpec<T> = motion(
        spring = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        durationMillis = LocalUiMotion.current.largeTransitionMillis,
    )

    /** A container, sheet or section arriving. */
    @Composable
    @ReadOnlyComposable
    fun <T> enter(): AnimationSpec<T> = motion(
        spring = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        durationMillis = LocalUiMotion.current.enterMillis,
    )

    /** A value settling: a number, a width, a colour. */
    @Composable
    @ReadOnlyComposable
    fun <T> settle(): AnimationSpec<T> = motion(
        spring = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        durationMillis = LocalUiMotion.current.standardMillis,
    )

    @Composable
    @ReadOnlyComposable
    private fun <T> motion(spring: AnimationSpec<T>, durationMillis: Int): AnimationSpec<T> {
        val capabilities = LocalUiCapabilities.current
        val motion = LocalUiMotion.current
        return when {
            capabilities.reducedMotion -> tween(durationMillis = 0)
            capabilities.motion == MotionScheme.SPRING -> spring
            else -> tween(durationMillis = durationMillis, easing = motion.enterEasing)
        }
    }
}
