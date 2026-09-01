package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.hydrabox.core.projection.Connection
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.core.projection.ServerRef
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.EmptyState
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.LoadingRows
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.SectionHeader
import io.hydrabox.ui.design.ServerRow
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import io.hydrabox.ui.design.WarningStrip
import org.jetbrains.compose.resources.stringResource

/**
 * Choosing where to connect, and nothing else. Subscriptions are reached from here rather
 * than from the main navigation, because a person picks a server often and manages a
 * subscription almost never.
 */
@Composable
fun ServersScreen(
    state: ScreenState,
    actions: AppActions,
    onOpenSources: () -> Unit,
) {
    if (state.sources.isEmpty() && state.autoServer == null) {
        EmptyState(
            icon = HydraIcons.Globe,
            title = stringResource(Res.string.servers_empty_title),
            body = stringResource(Res.string.servers_empty_body),
            primaryLabel = stringResource(Res.string.action_add_subscription),
            onPrimary = onOpenSources,
        )
        return
    }
    var filter by remember { mutableStateOf("") }
    val connected = state.connection is Connection.Connected
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        ValueRow(
            title = stringResource(Res.string.servers_sources),
            value = stringResource(Res.string.sources_servers_count, state.serverCount),
            leading = HydraIcons.Link,
            onClick = onOpenSources,
        )
        state.sources.firstOrNull { it.problem != null }?.let { source ->
            WarningStrip(
                text = "${source.name} · ${sourceProblemText(source.problem!!)}",
                actionLabel = stringResource(Res.string.action_refresh),
                onAction = { actions.onRefreshSource(source.id) },
            )
        }
        state.autoServer?.let { auto ->
            ServerRow(
                name = serverName(auto),
                detail = serverDetail(auto),
                latency = latencyLabel(auto.latencyMillis),
                selected = state.selectedServerId == auto.id || state.selectedServerId == null,
                icon = HydraIcons.Bolt,
                onClick = { actions.onSelectServer(auto.id) },
            )
        }
        HydraField(
            value = filter,
            onValueChange = { filter = it },
            label = stringResource(Res.string.servers_search),
        )
        if (state.busy.servers && state.serverCount == 0) LoadingRows(4)
        state.servers.forEach { group ->
            val visible = group.servers.filter {
                filter.isBlank() || it.displayName.contains(filter, ignoreCase = true)
            }
            if (visible.isEmpty()) return@forEach
            SectionHeader(group.sourceName)
            visible.forEach { server -> ServerEntry(server, state.selectedServerId, actions) }
        }
        if (connected) {
            SecondaryAction(stringResource(Res.string.servers_measure), onClick = actions.onMeasure)
        }
    }
}

@Composable
private fun ServerEntry(server: ServerRef, selectedId: String?, actions: AppActions) = ServerRow(
    name = server.displayName,
    detail = null,
    latency = latencyLabel(server.latencyMillis),
    selected = server.id == selectedId,
    icon = HydraIcons.Globe,
    onClick = { actions.onSelectServer(server.id) },
)
