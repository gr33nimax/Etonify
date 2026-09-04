package io.hydrabox.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * How much of the plan is gone.
 *
 * It is a meter rather than two numbers in a row because the question is "how much is left",
 * and a proportion answers that before any digit is read. The digits stay, in tabular figures,
 * for the person who wants them: a bar alone cannot say whether 20% is 2 GB or 200.
 */
@Composable
fun QuotaMeter(
    fraction: Float?,
    label: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // Colour is a second signal, never the only one: the label already says the figures, and
    // the caption says what is left.
    val fill = when {
        fraction == null -> scheme.primary
        fraction >= 0.97f -> scheme.error
        fraction >= 0.85f -> scheme.tertiary
        else -> scheme.primary
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiTokens.spacing / 2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                style = UiTokens.figures(MaterialTheme.typography.bodyMedium),
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            caption?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
        // A plan without a cap has no proportion to draw: an empty track would read as "none
        // left" when what it means is "there is no limit". Then the figure stands alone.
        if (fraction != null) {
            Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(color = scheme.surfaceContainerHighest, cornerRadius = radius)
                val width = size.width * fraction.coerceIn(0f, 1f)
                if (width > 0f) {
                    drawRoundRect(color = fill, size = Size(maxOf(width, size.height), size.height), cornerRadius = radius)
                }
            }
        }
    }
}

/**
 * The last minute of throughput, down and up.
 *
 * Two numbers that change every second say almost nothing: the useful question is whether the
 * tunnel is carrying traffic steadily or in gasps, and that is a shape, not a figure. The
 * samples are drawn as they are, without animation — the data already moves.
 */
@Composable
fun Sparkline(
    down: List<Long>,
    up: List<Long>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier.fillMaxWidth().height(44.dp)) {
        val peak = maxOf(down.maxOrNull() ?: 0L, up.maxOrNull() ?: 0L, 1L).toFloat()
        val baseline = size.height - 1f
        drawLine(
            color = scheme.outlineVariant,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1f,
        )
        listOf(down to scheme.primary, up to scheme.tertiary).forEach { (series, colour) ->
            if (series.size < 2) return@forEach
            val step = size.width / (series.size - 1).toFloat()
            val path = Path()
            series.forEachIndexed { index, value ->
                val x = step * index
                val y = baseline - (value.toFloat() / peak) * (size.height - 4f)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, colour, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
