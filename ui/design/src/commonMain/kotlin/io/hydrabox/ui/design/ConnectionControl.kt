package io.hydrabox.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

enum class ControlTone { IDLE, BUSY, ACTIVE, TROUBLE }

/** Keyframes and runtime transitions. Reconnection retains the open composition. */
enum class ConnectionVisualState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING }

/**
 * What the instrument says besides the state.
 *
 * The two rates drive the speed of the running ring and the quota drives the arc on the
 * outer track, so the movement a person sees is measured rather than decorative: a tunnel
 * that carries nothing has a ring that stands still.
 */
data class ControlSignal(
    val downRate: Long = 0,
    val upRate: Long = 0,
    /** How much of the plan is spent, when the provider declared a cap at all. */
    val quota: Float? = null,
)

/**
 * The aperture.
 *
 * Six blades, open when nothing is protected and turned shut when the tunnel carries
 * traffic. It is a mechanism rather than a picture on purpose: a shutter says open and
 * closed without a word, at every size, and it cannot be mistaken for a logo. Around it sit
 * the two rings that carry the numbers — the plan on the outside, the traffic inside — and
 * the words for the state live in the middle, where the light is.
 */
@Composable
fun ConnectionControl(
    tone: ControlTone,
    visualState: ConnectionVisualState,
    enabled: Boolean,
    contentDescription: String,
    stateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_CONTROL_SIZE,
    signal: ControlSignal = ControlSignal(),
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val reduced = LocalUiCapabilities.current.reducedMotion
    val bladeTarget = when (tone) {
        ControlTone.ACTIVE -> scheme.primary
        ControlTone.BUSY -> scheme.secondaryContainer
        ControlTone.TROUBLE -> scheme.errorContainer
        ControlTone.IDLE -> scheme.onSurfaceVariant.copy(alpha = .22f)
    }
    val blade = if (reduced) bladeTarget else animateColorAsState(
        targetValue = bladeTarget, animationSpec = HydraMotion.settle(), label = "iris-blade",
    ).value
    // Ink is decided by what is behind the words: a shut aperture is a filled disc, every
    // other state leaves the middle open onto the background.
    val inkTarget = if (tone == ControlTone.ACTIVE) scheme.onPrimary else scheme.onSurface
    val ink = if (reduced) inkTarget else animateColorAsState(
        targetValue = inkTarget, animationSpec = HydraMotion.settle(), label = "iris-ink",
    ).value
    // How far the blades have travelled inward. Shut is the only state that reaches 1: a
    // connection being made or restored must not look like one that is carrying traffic.
    val closureTarget = when (visualState) {
        ConnectionVisualState.DISCONNECTED -> .16f
        ConnectionVisualState.CONNECTING -> .46f
        ConnectionVisualState.CONNECTED -> 1f
        ConnectionVisualState.RECONNECTING -> .66f
        ConnectionVisualState.DISCONNECTING -> .30f
    }
    val closure = if (reduced) closureTarget else animateFloatAsState(
        targetValue = closureTarget, animationSpec = HydraMotion.stateChange(), label = "iris-closure",
    ).value

    val phases = remember { IrisPhases() }
    val live = rememberUpdatedState(signal)
    val working = visualState == ConnectionVisualState.CONNECTING ||
        visualState == ConnectionVisualState.DISCONNECTING
    val holding = visualState == ConnectionVisualState.RECONNECTING
    val flowing = visualState == ConnectionVisualState.CONNECTED || holding
    // Driven by the frame clock rather than by an infinite transition, because the speed is
    // a measurement: a repeating animation would have to be restarted at every new rate.
    LaunchedEffect(reduced, working, holding, flowing) {
        if (reduced || !(working || flowing)) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val delta = if (last == 0L) 0f else ((now - last) / 1_000_000_000.0).toFloat().coerceAtMost(.05f)
                last = now
                if (working) phases.spin = (phases.spin + BLADE_SPIN * delta) % 360f
                phases.flow = (phases.flow + flowSpeed(working, holding, live.value) * delta) % 360f
            }
        }
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = ink,
            modifier = Modifier.size(size).semantics {
                this.stateDescription = stateDescription
                this.contentDescription = contentDescription
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    // Three radii, proportional so the instrument keeps its proportions from a
                    // 168 dp phone to a 268 dp tablet, with strokes in dp so they stay crisp.
                    val outer = this.size.minDimension / 2f
                    val trackWidth = 3.dp.toPx()
                    val flowWidth = 3.dp.toPx()
                    val trackRadius = outer - 2.dp.toPx()
                    val flowRadius = outer * .90f
                    val irisRadius = outer * .795f
                    if (tone == ControlTone.ACTIVE) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(blade.copy(alpha = .16f), Color.Transparent),
                                center = center,
                                radius = outer,
                            ),
                            radius = outer,
                        )
                    }
                    drawTrack(trackRadius, trackWidth, scheme, live.value.quota)
                    when {
                        flowing -> drawFlow(flowRadius, flowWidth, phases.flow, live.value, holding, blade, scheme)
                        working -> drawSweep(flowRadius, flowWidth, if (reduced) 200f else phases.flow, scheme)
                    }
                    drawIris(irisRadius, closure, phases.spin, blade, ink)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.padding(size * .17f),
                    content = content,
                )
            }
        }
    }
}

/** The plan: a full track for what it allows, an arc for what has gone. */
private fun DrawScope.drawTrack(radius: Float, width: Float, scheme: ColorScheme, quota: Float?) {
    drawCircle(scheme.outlineVariant.copy(alpha = .55f), radius, style = Stroke(width))
    // No arc at all when the provider declared no cap: an empty ring would read as nothing
    // left and a full one as all of it gone, and neither is what no limit means.
    val spent = quota?.coerceIn(0f, 1f) ?: return
    drawArc(
        color = when {
            spent >= .97f -> scheme.error
            spent >= .85f -> scheme.tertiary
            else -> scheme.primary
        },
        startAngle = -90f,
        sweepAngle = 360f * spent,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

/**
 * The traffic, as sixteen marks that run at the speed of the measured rate. Still and dim
 * when the tunnel is up and quiet, which is a fact about the connection and not a fault.
 */
private fun DrawScope.drawFlow(
    radius: Float,
    width: Float,
    phase: Float,
    signal: ControlSignal,
    holding: Boolean,
    blade: Color,
    scheme: ColorScheme,
) {
    val moving = signal.downRate + signal.upRate > 0L
    val colour = when {
        holding -> scheme.tertiary
        moving -> blade
        else -> blade.copy(alpha = .40f)
    }
    val step = 360f / FLOW_MARKS
    repeat(FLOW_MARKS) { index ->
        drawArc(
            color = colour,
            startAngle = -90f + step * index + phase,
            sweepAngle = step * .46f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width, cap = StrokeCap.Round),
        )
    }
}

/** Work in progress: one arc going round, on the ring the traffic will use once it is up. */
private fun DrawScope.drawSweep(radius: Float, width: Float, phase: Float, scheme: ColorScheme) {
    drawArc(
        color = scheme.primary,
        startAngle = -90f + phase,
        sweepAngle = 104f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round),
    )
}

/**
 * Six blades as annular sectors: a stroke as wide as the blade has travelled, drawn at the
 * radius it has travelled to. At full closure the six sectors meet in the middle and the
 * aperture is a disc with six hairlines across it; at rest they are a thin broken ring at
 * the rim. The whole set turns as it closes, which is what reads as a mechanism.
 */
private fun DrawScope.drawIris(radius: Float, closure: Float, spin: Float, blade: Color, ink: Color) {
    val travelled = radius * closure.coerceIn(0f, 1f)
    if (travelled <= .5f) return
    val mid = radius - travelled / 2f
    // The angular gap closes with the blades. Kept open, it would draw six wedges meeting in
    // the middle — a pie chart, which is what a shut aperture must not look like.
    val gap = 8f * (1f - closure)
    val turn = spin + closure * 26f
    // Adjacent sectors overlap by a degree. Butted exactly against each other they leave an
    // antialiasing seam per edge, and six of those meeting in the middle is the pie chart
    // again — this time drawn by the rasteriser rather than by the geometry.
    repeat(BLADES) { index ->
        drawArc(
            color = blade,
            startAngle = -90f + index * (360f / BLADES) + gap / 2f - .75f + turn,
            sweepAngle = 360f / BLADES - gap + 1.5f,
            useCenter = false,
            topLeft = Offset(center.x - mid, center.y - mid),
            size = Size(mid * 2, mid * 2),
            style = Stroke(travelled, cap = StrokeCap.Butt),
        )
    }
    // Once the wedges are gone the disc would be a plain circle, so the seams are drawn back
    // in — but only as short marks at its rim. Carried to the middle they read as a pie chart,
    // which is a different instrument saying a different thing.
    val seam = ((closure - .55f) / .45f).coerceIn(0f, 1f)
    if (seam <= 0f) return
    repeat(BLADES) { index ->
        val radians = ((-90f + index * (360f / BLADES) + turn) * PI / 180f).toFloat()
        val unit = Offset(cos(radians), sin(radians))
        drawLine(
            color = ink.copy(alpha = .14f * seam),
            start = center + unit * (radius * .76f),
            end = center + unit * (radius * .99f),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Degrees a second. Connecting turns at a fixed pace because there is nothing to measure
 * yet; a live tunnel turns at the log of its own throughput, so a slow link still shows
 * movement and a fast one does not blur into a solid ring.
 */
private fun flowSpeed(working: Boolean, holding: Boolean, signal: ControlSignal): Float {
    if (working) return 210f
    if (holding) return 24f
    val rate = (signal.downRate + signal.upRate).coerceAtLeast(0L)
    if (rate == 0L) return 0f
    val norm = (ln(1f + rate / 24_000f) / ln(1f + 8_000_000f / 24_000f)).coerceIn(0f, 1f)
    return 26f + 200f * norm
}

private class IrisPhases {
    var spin by mutableFloatStateOf(0f)
    var flow by mutableFloatStateOf(0f)
}

private const val BLADES = 6
private const val FLOW_MARKS = 16
private const val BLADE_SPIN = 74f
val DEFAULT_CONTROL_SIZE = 208.dp
