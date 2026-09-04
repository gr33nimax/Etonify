package io.hydrabox.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrabox.core.projection.JournalEntry
import io.hydrabox.core.projection.JournalLevel
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.ActionRow
import io.hydrabox.ui.design.UiTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The journal, as a journal.
 *
 * One line per event in the order they happened, oldest first, opened at the newest — which is
 * how 1.x's log page read, and the reason it was usable during a failure. What replaced it here
 * was a list of settings-style rows inside a scrolling column: three hundred of them in one
 * non-scrollable stack, each rounded, each padded, none of them readable as a sequence.
 *
 * A line is monospaced because the interesting part is a tag, an address or a code, and columns
 * that do not line up cannot be scanned. The level is a colour and a word, never only a colour.
 * The message is one line until it is tapped, so a stack trace does not push out the ten lines
 * around it that give it context.
 */
@Composable
fun JournalScreen(entries: List<JournalEntry>) {
    var filter by remember { mutableStateOf<JournalLevel?>(null) }
    val visible = remember(entries, filter) {
        entries.filter { entry -> filter == null || entry.level == filter }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        LevelFilter(filter) { filter = it }
        if (visible.isEmpty()) {
            Text(
                stringResource(Res.string.journal_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(UiTokens.spacing * 2),
            )
            return@Column
        }
        val listState = rememberLazyListState()
        // The newest line is the one being waited for, so the list opens at the end and stays
        // there as lines arrive.
        LaunchedEffect(visible.size) { listState.scrollToItem(visible.lastIndex) }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            items(visible, key = { it.id }) { entry ->
                JournalRow(entry)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
        }
    }
}

/** Which levels are worth looking at right now. "All" is a level too, and it is the default. */
@Composable
private fun LevelFilter(selected: JournalLevel?, onSelect: (JournalLevel?) -> Unit) {
    val options = listOf(
        null to Res.string.journal_all,
        JournalLevel.ERROR to Res.string.journal_errors,
        JournalLevel.WARN to Res.string.journal_warnings,
        JournalLevel.INFO to Res.string.journal_info,
        JournalLevel.DEBUG to Res.string.journal_debug,
    )
    ActionRow(modifier = Modifier.padding(horizontal = UiTokens.spacing * 2)) {
        options.forEach { (level, label) ->
            FilterChip(
                selected = selected == level,
                onClick = { onSelect(level) },
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

@Composable
private fun JournalRow(entry: JournalEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val tone = entry.level.tone()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = UiTokens.spacing * 2, vertical = UiTokens.spacing * 1.25f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        ) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(tone))
            Text(
                text = header(entry),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun header(entry: JournalEntry): String {
    val repeats = if (entry.repeats > 1) " x${entry.repeats}" else ""
    return "${entry.time}  ${entry.level.name.lowercase()}  ${entry.source}$repeats"
}

/**
 * The level as a colour from the scheme rather than a fixed hex: 1.x could hardcode four
 * colours because its log page was always dark, and this one follows the app's theme.
 */
@Composable
private fun JournalLevel.tone(): Color = when (this) {
    JournalLevel.ERROR -> MaterialTheme.colorScheme.error
    JournalLevel.WARN -> MaterialTheme.colorScheme.tertiary
    JournalLevel.INFO -> MaterialTheme.colorScheme.primary
    JournalLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
}
