package io.hydrabox.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A group of rows under a name. The name is a label, not a headline. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .semantics { heading() }
            .padding(start = UiTokens.spacing * 1.5f, top = UiTokens.spacing * 2, bottom = UiTokens.spacing / 2),
    )
}

/** One visual block for related rows; the rows inside carry interaction, not extra cards. */
@Composable
fun SectionGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        title?.let { SectionHeader(it) }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

/**
 * The one row every list is built from. A row is a tonal container, not a card with a
 * shadow: elevation in this app is carried by colour, per the token rules.
 */
@Composable
fun HydraRow(
    title: String,
    supporting: String? = null,
    leading: ImageVector? = null,
    tone: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 2,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        shape = MaterialTheme.shapes.small,
        color = tone,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing * 1.5f),
            modifier = Modifier.heightIn(min = 48.dp)
                .padding(horizontal = UiTokens.spacing * 2, vertical = UiTokens.spacing * 1.25f),
        ) {
            leading?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** A setting that is on or off, with the consequence spelled out under its name. */
@Composable
fun ToggleRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    HydraRow(
        title = title,
        supporting = supporting,
        leading = leading,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A row that opens something else, showing the value it currently holds. */
@Composable
fun ValueRow(
    title: String,
    value: String?,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) = HydraRow(
    title = title,
    supporting = value,
    leading = leading,
    onClick = onClick,
    trailing = {
        Icon(
            HydraIcons.Chevron,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
)

/** One number with its name. Used for traffic, never for runtime internals. */
@Composable
fun MetricTile(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(UiTokens.spacing * 2),
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing * 0.5f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/**
 * One fact and its value on a single line, with the screen it belongs to behind the chevron.
 *
 * A [ValueRow] stacks its value under its name, which is right for a setting whose name is
 * the subject. An instrument is the other way round: the name is a label a person reads
 * once and the value is what they came for, so the two share a line and the value takes the
 * right edge, where the eye can run down a column of them.
 */
@Composable
fun FactRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    detail: String? = null,
    accent: Color? = null,
    trailingIcon: ImageVector = HydraIcons.Chevron,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing * 1.5f),
            modifier = Modifier.heightIn(min = 48.dp)
                .padding(
                    start = UiTokens.spacing * 2,
                    end = UiTokens.spacing * 1.5f,
                    top = UiTokens.spacing * 1.25f,
                    bottom = UiTokens.spacing * 1.25f,
                ),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    value,
                    style = UiTokens.figures(MaterialTheme.typography.bodyMedium),
                    color = accent ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
                detail?.let {
                    Text(
                        it,
                        style = UiTokens.figures(MaterialTheme.typography.labelMedium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                    )
                }
            }
            Icon(
                trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
