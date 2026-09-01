package io.hydrabox.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Settings a person does not need in order to use a VPN live behind this. Collapsed by
 * default, and it says why it is collapsed rather than just hiding things.
 */
@Composable
fun AdvancedSection(
    title: String,
    caution: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
        HydraRow(
            title = title,
            supporting = caution,
            leading = HydraIcons.Sliders,
            tone = MaterialTheme.colorScheme.surfaceContainerHigh,
            onClick = onToggle,
            trailing = {
                Icon(
                    if (expanded) HydraIcons.Close else HydraIcons.Chevron,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(UiTokens.spacing)) { content() }
        }
    }
}

/** One server in a list: what it is called, how fast it answered, whether it is the one. */
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
