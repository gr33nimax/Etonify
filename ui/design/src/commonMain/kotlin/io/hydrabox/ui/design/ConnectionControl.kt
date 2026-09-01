package io.hydrabox.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** How the control reads at a glance, before any text is read. */
enum class ControlTone { IDLE, BUSY, ACTIVE, TROUBLE }

/**
 * The one thing the home screen is for.
 *
 * It is a single control that changes rather than a set of cards that swap: the disc keeps
 * its place and its size, and only its colour, corner radius and ring say what happened.
 * That is what makes the state change readable without reading — and it is why the label
 * lives outside, in the screen, where the words are translated.
 */
@Composable
fun ConnectionControl(
    tone: ControlTone,
    icon: ImageVector,
    enabled: Boolean,
    contentDescription: String,
    stateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (tone) {
            ControlTone.ACTIVE -> scheme.primary
            ControlTone.BUSY -> scheme.secondaryContainer
            ControlTone.TROUBLE -> scheme.errorContainer
            ControlTone.IDLE -> scheme.surfaceContainerHighest
        },
        animationSpec = HydraMotion.stateChange(),
        label = "connection-container",
    )
    val content = when (tone) {
        ControlTone.ACTIVE -> scheme.onPrimary
        ControlTone.BUSY -> scheme.onSecondaryContainer
        ControlTone.TROUBLE -> scheme.onErrorContainer
        ControlTone.IDLE -> scheme.onSurfaceVariant
    }
    // Round when at rest, softened square while working: the shape carries the state too,
    // for anyone who cannot tell the colours apart.
    val corner by animateFloatAsState(
        targetValue = if (tone == ControlTone.BUSY) 38f else 50f,
        animationSpec = HydraMotion.stateChange(),
        label = "connection-corner",
    )
    Box(modifier = modifier.size(CONTROL_SIZE), contentAlignment = Alignment.Center) {
        ControlRing(tone = tone)
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(percent = corner.toInt()),
            color = container,
            contentColor = content,
            modifier = Modifier.size(DISC_SIZE).semantics { this.stateDescription = stateDescription },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(72.dp))
            }
        }
    }
}

/** The ring is the only part that moves: a sweep while working, a halo while protected. */
@Composable
private fun ControlRing(tone: ControlTone) {
    val reduced = LocalUiCapabilities.current.reducedMotion
    val scheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "connection-ring")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400), RepeatMode.Restart),
        label = "connection-spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2200), RepeatMode.Reverse),
        label = "connection-breathe",
    )
    val ringColor by animateColorAsState(
        targetValue = when (tone) {
            ControlTone.ACTIVE -> scheme.primary
            ControlTone.BUSY -> scheme.primary
            ControlTone.TROUBLE -> scheme.error
            ControlTone.IDLE -> scheme.outlineVariant
        },
        animationSpec = HydraMotion.settle(),
        label = "connection-ring-colour",
    )
    Canvas(modifier = Modifier.size(CONTROL_SIZE)) {
        val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
        val inset = stroke.width / 2
        val diameter = size.minDimension - stroke.width
        val offset = androidx.compose.ui.geometry.Offset(inset, inset)
        val ringSize = androidx.compose.ui.geometry.Size(diameter, diameter)
        when (tone) {
            ControlTone.BUSY -> {
                drawArc(
                    color = ringColor.copy(alpha = 0.22f),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = offset, size = ringSize, style = stroke,
                )
                drawArc(
                    color = ringColor,
                    startAngle = if (reduced) 270f else spin,
                    sweepAngle = 90f, useCenter = false,
                    topLeft = offset, size = ringSize, style = stroke,
                )
            }
            ControlTone.ACTIVE -> drawArc(
                color = ringColor.copy(alpha = if (reduced) 0.6f else breathe),
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = offset, size = ringSize, style = stroke,
            )
            else -> drawArc(
                color = ringColor,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = offset, size = ringSize, style = stroke,
            )
        }
    }
}

private val CONTROL_SIZE = 236.dp
private val DISC_SIZE = 196.dp
