package io.hydrabox.ui.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.core.projection.SubscriptionSummary
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.ActionRow
import io.hydrabox.ui.design.ConfirmDialog
import io.hydrabox.ui.design.EmptyState
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.InputDialog
import io.hydrabox.ui.design.LoadingRows
import io.hydrabox.ui.design.PrimaryAction
import io.hydrabox.ui.design.QrDialog
import io.hydrabox.ui.design.QuotaMeter
import io.hydrabox.ui.design.RefreshButton
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.WarningStrip
import org.jetbrains.compose.resources.stringResource

/**
 * The sources of servers, as a list of what a person has — not as a form with a list stapled
 * under it.
 *
 * Adding a subscription happens once and then almost never; looking at what a subscription is
 * doing happens every time this screen opens. So the form moved into a sheet, and the space it
 * held went to the two figures a provider actually sends: how much of the plan is gone and how
 * long it is still valid.
 */
@Composable
fun SourcesScreen(state: ScreenState, actions: AppActions, onOpenServers: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<SubscriptionSummary?>(null) }
    var renaming by remember { mutableStateOf<SubscriptionSummary?>(null) }
    var sharing by remember { mutableStateOf<SubscriptionSummary?>(null) }
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        state.notice?.takeIf { it.failure }?.let { notice ->
            WarningStrip(text = noticeText(notice), actionLabel = null, onAction = null)
        }
        ActionRow {
            PrimaryAction(
                label = stringResource(Res.string.action_add_subscription),
                enabled = !state.busy.source,
                onClick = { adding = true },
            )
            if (state.sources.size > 1) {
                SecondaryAction(
                    label = stringResource(Res.string.sources_refresh_all),
                    enabled = !state.busy.source,
                    onClick = { state.sources.forEach { actions.onRefreshSource(it.id) } },
                )
            }
        }
        if (state.busy.source) LoadingRows(1)
        if (state.sources.isEmpty()) {
            EmptyState(
                icon = HydraIcons.Subscription,
                title = stringResource(Res.string.sources_empty_title),
                body = stringResource(Res.string.sources_empty_body),
                primaryLabel = stringResource(Res.string.action_add_subscription),
                onPrimary = { adding = true },
            )
        } else {
            state.sources.forEach { source ->
                SourceCard(
                    source = source,
                    busy = state.busy.source,
                    onOpenServers = onOpenServers,
                    onRefresh = { actions.onRefreshSource(source.id) },
                    onRename = { renaming = source },
                    onRemove = { pendingRemoval = source },
                    onToggle = { enabled -> actions.onSetSourceEnabled(source.id, enabled) },
                    onRefreshUsage = { actions.onRefreshUsage(source.id) },
                    onShare = { sharing = source },
                )
            }
        }
    }
    if (adding) AddSourceSheet(state, actions) { adding = false }
    pendingRemoval?.let { source ->
        ConfirmDialog(
            title = stringResource(Res.string.sources_remove_title),
            body = stringResource(Res.string.sources_remove_body),
            confirmLabel = stringResource(Res.string.action_remove),
            dismissLabel = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = { actions.onRemoveSource(source.id); pendingRemoval = null },
            onDismiss = { pendingRemoval = null },
        )
    }
    renaming?.let { source -> RenameDialog(source, actions) { renaming = null } }
    sharing?.let { source ->
        source.link?.let { link ->
            QrDialog(
                title = source.name,
                text = link,
                dismissLabel = stringResource(Res.string.action_close),
                onDismiss = { sharing = null },
            )
        }
    }
}

/**
 * One subscription, with the four things it can tell about itself: what it is called, what it
 * gave, how much of it is left, and when it was last read. A problem, when there is one, sits
 * inside the card next to the action that fixes it rather than as a banner over the screen.
 */
@Composable
private fun SourceCard(
    source: SubscriptionSummary,
    busy: Boolean,
    onOpenServers: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefreshUsage: () -> Unit,
    onShare: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Surface(
        onClick = onOpenServers,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(UiTokens.spacing * 2),
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        ) {
            // The name gets the whole width. Three icon buttons beside it left about a third
            // of the line for it, and a provider's name is long enough to need all of it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (source.encrypted) {
                    Icon(
                        HydraIcons.Shield,
                        contentDescription = stringResource(Res.string.sources_encrypted),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                // A subscription kept for later is not a subscription deleted: the switch takes
                // its servers out of the list and out of the configuration, and nothing else.
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            ProtocolBadges(source)
            // Asking only for the figures is a different request from asking for the servers,
            // so it is a different tap: on the meter itself.
            // Both figures are stated even when the provider declares neither: no cap and no
            // end date are answers a person checks for, and a line that simply was not there
            // read as the app having failed to fetch one.
            QuotaMeter(
                fraction = source.quota,
                label = planAllowance(source),
                caption = planValidity(source),
                modifier = Modifier.clickable(onClick = onRefreshUsage),
            )
            source.updateIntervalHours?.let { hours ->
                Text(
                    stringResource(Res.string.home_plan_refresh_every, hours),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The timestamp gets its own line. Beside five icon buttons it had a third of the
            // width left and broke "обновлено 02.09 22:48" across three lines.
            source.updatedAt?.let {
                Text(
                    stringResource(Res.string.sources_updated, it),
                    style = UiTokens.figures(MaterialTheme.typography.labelMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                source.link?.let { link ->
                    IconButton(onClick = { clipboard.setText(AnnotatedString(link)) }) {
                        Icon(HydraIcons.Link, contentDescription = stringResource(Res.string.sources_copy_link))
                    }
                    IconButton(onClick = onShare) {
                        Icon(HydraIcons.Share, contentDescription = stringResource(Res.string.sources_share))
                    }
                }
                RefreshButton(
                    busy = busy,
                    contentDescription = stringResource(Res.string.action_refresh),
                    onClick = onRefresh,
                )
                IconButton(onClick = onRename) {
                    Icon(HydraIcons.Edit, contentDescription = stringResource(Res.string.action_rename))
                }
                IconButton(onClick = onRemove) {
                    Icon(HydraIcons.Delete, contentDescription = stringResource(Res.string.action_remove))
                }
            }
            source.problem?.let { problem ->
                WarningStrip(
                    text = sourceProblemText(problem),
                    actionLabel = stringResource(Res.string.action_refresh),
                    onAction = onRefresh,
                )
            }
        }
    }
}

/**
 * What the source is made of. A count per protocol answers "what can I pick from here", which
 * two truncated server names did not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtocolBadges(source: SubscriptionSummary) {
    if (source.protocols.isEmpty()) {
        Text(
            stringResource(Res.string.sources_servers_count, source.serverCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing / 2),
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing / 2),
    ) {
        source.protocols.entries.sortedByDescending { it.value }.forEach { entry ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    if (entry.value > 1) entry.key + " " + entry.value else entry.key,
                    style = UiTokens.figures(MaterialTheme.typography.labelMedium),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = UiTokens.spacing, vertical = UiTokens.spacing / 2),
                )
            }
        }
    }
}

/**
 * Adding a subscription, in a sheet. It asks for one thing — the link — and offers the two ways
 * a link usually arrives: the clipboard and a file. The field is not cleared until the import
 * has actually succeeded, so a failure does not also lose what was typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddSourceSheet(state: ScreenState, actions: AppActions, onClose: () -> Unit) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 3).padding(bottom = UiTokens.spacing * 4),
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        ) {
            Text(
                stringResource(Res.string.sources_add_title),
                style = MaterialTheme.typography.titleLarge,
            )
            HydraField(
                value = link,
                onValueChange = { link = it },
                label = stringResource(Res.string.sources_add_field),
                supporting = stringResource(Res.string.sources_add_hint),
                singleLine = false,
                minLines = 2,
            )
            HydraField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(Res.string.sources_add_name),
            )
            ActionRow {
                PrimaryAction(
                    label = stringResource(Res.string.action_add),
                    enabled = link.isNotBlank() && !state.busy.source,
                    onClick = { actions.onAddSource(name.trim(), link.trim()); onClose() },
                )
                SecondaryAction(
                    label = stringResource(Res.string.action_paste),
                    onClick = { clipboard.getText()?.text?.let { link = it.trim() } },
                )
                SecondaryAction(
                    label = stringResource(Res.string.sources_add_file),
                    onClick = { actions.onAddSourceFromFile(); onClose() },
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(source: SubscriptionSummary, actions: AppActions, onClose: () -> Unit) {
    var draft by remember(source.id) { mutableStateOf(source.name) }
    InputDialog(
        title = stringResource(Res.string.sources_rename_title),
        value = draft,
        onValueChange = { draft = it },
        label = stringResource(Res.string.sources_add_name),
        confirmLabel = stringResource(Res.string.action_save),
        dismissLabel = stringResource(Res.string.action_cancel),
        onConfirm = { actions.onRenameSource(source.id, draft.trim()); onClose() },
        onDismiss = onClose,
    )
}
