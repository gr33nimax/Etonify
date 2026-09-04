package io.hydrabox.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The action a screen wants pressed. One per screen region, never two side by side. */
@Composable
fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
}

@Composable
fun TonalAction(label: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
}

/**
 * A row of actions that runs out of width instead of clipping.
 *
 * Three buttons with Russian labels do not fit one line on a phone, and a plain `Row`
 * answers that by squeezing the last one off the screen: the reported "broken actions" in
 * the import row were buttons the person could see the left edge of and not press. Wrapping
 * is the only behaviour that keeps every action reachable at every width and font scale.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing),
    verticalArrangement = Arrangement.spacedBy(UiTokens.spacing / 2),
    modifier = modifier.fillMaxWidth(),
) { content() }

/** A single-line or multi-line input. Labels say what goes in, not which format parses. */
@Composable
fun HydraField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
) = OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    supportingText = supporting?.let { { Text(it) } },
    singleLine = singleLine,
    minLines = minLines,
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.fillMaxWidth(),
)

/**
 * Anything irreversible asks first, and the question names the consequence rather than
 * repeating the button.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = {
        TextButton(
            onClick = onConfirm,
            colors = if (destructive) {
                ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.textButtonColors()
            },
        ) { Text(confirmLabel) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    shape = MaterialTheme.shapes.extraLarge,
)

/** One choice out of a few, where a switch would not say what the alternatives are. */
@Composable
fun OptionRow(title: String, supporting: String?, selected: Boolean, onClick: () -> Unit) = HydraRow(
    title = title,
    supporting = supporting,
    tone = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    onClick = onClick,
    trailing = {
        RadioButton(selected = selected, onClick = null)
    },
)

/**
 * One choice out of a few, asked in a dialog instead of as a permanent block of rows.
 *
 * A five-way choice that is changed once a year does not deserve five rows on a screen a person
 * scrolls every day; the row shows the current value and this asks for the new one.
 */
@Composable
fun ChoiceDialog(
    title: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
        // Six options and a large font scale do not fit a dialog on a short screen, and an
        // AlertDialog does not scroll its own content.
        Column(
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
            modifier = Modifier.verticalScroll(rememberScrollState()),
            content = content,
        )
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    shape = MaterialTheme.shapes.extraLarge,
)

/** One value to type, one question, one confirmation. Used for renaming, not for forms. */
@Composable
fun InputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { HydraField(value = value, onValueChange = onValueChange, label = label) },
    confirmButton = {
        TextButton(onClick = onConfirm, enabled = value.isNotBlank()) { Text(confirmLabel) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    shape = MaterialTheme.shapes.extraLarge,
)

/**
 * One server in a list: what it is called, how fast it answered, whether it is the one.
 *
 * The name stays on one line. Provider names are long, share a suffix and do not break at
 * anything: two lines of `amneziawg-desktop-gr33nima` + `x` reads worse than one line that
 * ends in an ellipsis, and the part that tells two servers apart is at the front.
 */
@Composable
fun ServerRow(
    name: String,
    detail: String?,
    latency: String?,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) = HydraRow(
    title = name,
    supporting = detail,
    leading = icon,
    tone = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
    onClick = onClick,
    titleMaxLines = 1,
    trailing = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
            latency?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(
                    HydraIcons.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    },
)

/**
 * Ask again, and show that the asking is under way.
 *
 * A refresh that only greys its own icon out looks like a button that stopped working. The
 * icon turns for as long as the request is in flight, which is the same fact the row would
 * otherwise need a second line of text to state, and it stops turning when the answer is in.
 */
@Composable
fun RefreshButton(
    busy: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalUiCapabilities.current.reducedMotion
    val angle = if (busy && !reduced) {
        rememberInfiniteTransition(label = "refresh").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
            label = "refresh-angle",
        ).value
    } else {
        0f
    }
    IconButton(
        onClick = onClick,
        enabled = !busy,
        // A turning icon that is also greyed out reads as broken rather than busy: the button
        // refuses a second tap, and the colour stays the one that says it is working.
        colors = IconButtonDefaults.iconButtonColors(
            disabledContentColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier,
    ) {
        Icon(
            HydraIcons.Refresh,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp).rotate(angle),
        )
    }
}
