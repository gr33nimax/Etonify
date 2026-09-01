package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.core.projection.SubscriptionSummary
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.ConfirmDialog
import io.hydrabox.ui.design.EmptyState
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.HydraRow
import io.hydrabox.ui.design.InputDialog
import io.hydrabox.ui.design.LoadingRows
import io.hydrabox.ui.design.PrimaryAction
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.SectionHeader
import io.hydrabox.ui.design.UiTokens
import org.jetbrains.compose.resources.stringResource

/**
 * Managing the sources of servers. The add form asks for one thing — the link — and says
 * where that link comes from; it does not list the formats the parser accepts.
 */
@Composable
fun SourcesScreen(state: ScreenState, actions: AppActions) {
    var link by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pendingRemoval by remember { mutableStateOf<SubscriptionSummary?>(null) }
    var renaming by remember { mutableStateOf<SubscriptionSummary?>(null) }
    val clipboard = LocalClipboardManager.current
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        SectionHeader(stringResource(Res.string.sources_add_title))
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
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
            PrimaryAction(
                label = stringResource(Res.string.action_add),
                enabled = link.isNotBlank() && !state.busy.source,
                onClick = {
                    actions.onAddSource(name.trim(), link.trim())
                    link = ""
                    name = ""
                },
            )
            SecondaryAction(
                label = stringResource(Res.string.action_paste),
                onClick = { clipboard.getText()?.text?.let { link = it.trim() } },
            )
        }
        if (state.busy.source) LoadingRows(1)
        if (state.sources.isEmpty()) {
            EmptyState(
                icon = HydraIcons.Link,
                title = stringResource(Res.string.sources_empty_title),
                body = stringResource(Res.string.sources_empty_body),
            )
        } else {
            SectionHeader(stringResource(Res.string.sources_title))
            state.sources.forEach { source ->
                SourceRow(
                    source = source,
                    onRefresh = { actions.onRefreshSource(source.id) },
                    onRename = { renaming = source },
                    onRemove = { pendingRemoval = source },
                )
            }
        }
    }
    pendingRemoval?.let { source ->
        ConfirmDialog(
            title = stringResource(Res.string.sources_remove_title),
            body = stringResource(Res.string.sources_remove_body),
            confirmLabel = stringResource(Res.string.action_remove),
            dismissLabel = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = {
                actions.onRemoveSource(source.id)
                pendingRemoval = null
            },
            onDismiss = { pendingRemoval = null },
        )
    }
    renaming?.let { source -> RenameDialog(source, actions) { renaming = null } }
}

@Composable
private fun SourceRow(
    source: SubscriptionSummary,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    var open by remember(source.id) { mutableStateOf(false) }
    val detail = listOfNotNull(
        stringResource(Res.string.sources_servers_count, source.serverCount),
        source.expiresAt?.let { stringResource(Res.string.sources_expires, it) },
        stringResource(Res.string.sources_encrypted).takeIf { source.encrypted },
        source.problem?.let { sourceProblemText(it) },
    ).joinToString(" · ")
    HydraRow(
        title = source.name,
        supporting = detail,
        leading = HydraIcons.Link,
        onClick = { open = !open },
    )
    if (open) {
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
            SecondaryAction(stringResource(Res.string.action_refresh), onClick = onRefresh)
            SecondaryAction(stringResource(Res.string.action_rename), onClick = onRename)
            SecondaryAction(stringResource(Res.string.action_remove), onClick = onRemove)
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
        onConfirm = {
            actions.onRenameSource(source.id, draft.trim())
            onClose()
        },
        onDismiss = onClose,
    )
}
