package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import io.hydrabox.core.projection.Connection
import io.hydrabox.core.projection.PrimaryAction
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.core.projection.ServerRef
import io.hydrabox.core.projection.Trouble
import io.hydrabox.core.projection.primaryAction
import io.hydrabox.core.projection.server
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.ConnectionControl
import io.hydrabox.ui.design.ControlTone
import io.hydrabox.ui.design.EmptyState
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.MetricTile
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.TonalAction
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Three questions, in this order: am I protected, through what, and what can I press.
 *
 * Everything that is not one of the three lives somewhere else. There is no terms banner,
 * no subscription card, no transport line and no error code on this screen.
 */
@Composable
fun HomeScreen(
    state: ScreenState,
    actions: AppActions,
    onOpenServers: () -> Unit,
    onOpenTraffic: () -> Unit,
    onAddSource: () -> Unit,
) {
    when (state.connection) {
        Connection.NeedsSubscription -> EmptyState(
            icon = HydraIcons.Link,
            title = stringResource(Res.string.home_empty_title),
            body = stringResource(Res.string.home_empty_body),
            primaryLabel = stringResource(Res.string.action_add_subscription),
            onPrimary = onAddSource,
        )
        Connection.NeedsServers -> EmptyState(
            icon = HydraIcons.Globe,
            title = stringResource(Res.string.home_no_servers_title),
            body = stringResource(Res.string.home_no_servers_body),
            primaryLabel = stringResource(Res.string.action_refresh_subscription),
            onPrimary = { state.sources.firstOrNull()?.let { actions.onRefreshSource(it.id) } },
        )
        else -> LiveHome(state, actions, onOpenServers, onOpenTraffic)
    }
}

@Composable
private fun LiveHome(
    state: ScreenState,
    actions: AppActions,
    onOpenServers: () -> Unit,
    onOpenTraffic: () -> Unit,
) {
    val connection = state.connection
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing * 2),
    ) {
        ConnectionControl(
            tone = connection.tone(),
            icon = connection.icon(),
            enabled = connection.primaryAction != PrimaryAction.NONE,
            contentDescription = stringResource(
                if (connection is Connection.Connected) Res.string.control_disconnect else Res.string.control_connect,
            ),
            stateDescription = connectionTitle(connection),
            onClick = { press(connection, actions, onOpenServers) },
            modifier = Modifier.padding(top = UiTokens.spacing * 2),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing * 0.5f),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(
                connectionTitle(connection),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            connectionHint(connection)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            (connection as? Connection.Connected)?.connectedForSeconds?.let { base ->
                Text(
                    stringResource(Res.string.home_connected_for, formatDuration(ticking(base))),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SecondaryActionRow(connection, actions)
        ServerChip(connection.server, onOpenServers)
        (connection as? Connection.Connected)?.traffic?.takeIf { it.available }?.let { traffic ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MetricTile(
                    label = stringResource(Res.string.traffic_down),
                    value = traffic.downlink,
                    icon = HydraIcons.Download,
                    modifier = Modifier.weight(1f),
                )
                MetricTile(
                    label = stringResource(Res.string.traffic_up),
                    value = traffic.uplink,
                    icon = HydraIcons.Upload,
                    modifier = Modifier.weight(1f),
                )
            }
            SecondaryAction(stringResource(Res.string.home_traffic), onClick = onOpenTraffic)
        }
    }
}

/** The chip is the answer to "through what", and the way to change it. */
@Composable
private fun ServerChip(server: ServerRef?, onOpenServers: () -> Unit) = ValueRow(
    title = server?.let { serverName(it) } ?: stringResource(Res.string.home_server_none),
    value = server?.let { serverDetail(it) ?: latencyLabel(it.latencyMillis) },
    leading = if (server?.auto == true) HydraIcons.Bolt else HydraIcons.Globe,
    onClick = onOpenServers,
)

/** The second action, when a state has one. Never two primary buttons on one screen. */
@Composable
private fun SecondaryActionRow(connection: Connection, actions: AppActions) {
    when {
        connection is Connection.Stopped && connection.cause == Trouble.PERMISSION_REQUIRED ->
            TonalAction(stringResource(Res.string.action_grant_permission), onClick = actions.onGrantPermission)
        connection is Connection.Stopped && connection.primaryAction == PrimaryAction.CHOOSE_SERVER ->
            TonalAction(stringResource(Res.string.action_retry), onClick = actions.onRetry)
        connection is Connection.Connecting || connection is Connection.Reconnecting ->
            SecondaryAction(stringResource(Res.string.action_cancel), onClick = actions.onDisconnect)
        else -> Unit
    }
}

private fun press(connection: Connection, actions: AppActions, onOpenServers: () -> Unit) {
    when (connection.primaryAction) {
        PrimaryAction.CONNECT, PrimaryAction.RETRY -> actions.onConnect()
        PrimaryAction.DISCONNECT, PrimaryAction.CANCEL -> actions.onDisconnect()
        PrimaryAction.CHOOSE_SERVER -> onOpenServers()
        PrimaryAction.REFRESH_SOURCE -> actions.onRetry()
        PrimaryAction.ADD_SUBSCRIPTION, PrimaryAction.NONE -> Unit
    }
}

private fun Connection.tone(): ControlTone = when (this) {
    is Connection.Connected -> ControlTone.ACTIVE
    is Connection.Connecting, is Connection.Reconnecting, Connection.Disconnecting -> ControlTone.BUSY
    is Connection.Stopped -> ControlTone.TROUBLE
    else -> ControlTone.IDLE
}

private fun Connection.icon() = when (this) {
    is Connection.Connected -> HydraIcons.ShieldCheck
    is Connection.Stopped -> HydraIcons.Warning
    else -> HydraIcons.Power
}

/**
 * The elapsed time ticks here rather than in the runtime: the platform reports how long the
 * tunnel has been up at each snapshot, and this only counts the seconds in between so the
 * number does not sit still between snapshots.
 */
@Composable
private fun ticking(baseSeconds: Int): Int {
    var extra by remember(baseSeconds) { mutableIntStateOf(0) }
    LaunchedEffect(baseSeconds) {
        while (true) {
            delay(1000)
            extra += 1
        }
    }
    return baseSeconds + extra
}
