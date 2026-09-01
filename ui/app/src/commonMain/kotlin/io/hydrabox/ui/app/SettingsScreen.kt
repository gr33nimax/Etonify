package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.AdvancedSection
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.PrimaryAction
import io.hydrabox.ui.design.SectionHeader
import io.hydrabox.ui.design.ToggleRow
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import org.jetbrains.compose.resources.stringResource

/**
 * Settings split by whether a person needs the setting to use a VPN at all. Everything on
 * the first level has a visible consequence; everything a person does not have to
 * understand is behind [AdvancedSection], and the resolvers and the packet size are there.
 */
@Composable
fun SettingsScreen(
    state: ScreenState,
    actions: AppActions,
    onOpenApps: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    val settings = state.settings
    var advanced by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        SectionHeader(stringResource(Res.string.settings_connection))
        ToggleRow(
            title = stringResource(Res.string.settings_notification),
            supporting = stringResource(Res.string.settings_notification_hint),
            checked = settings?.statusNotificationEnabled == true,
            onCheckedChange = actions.onToggleNotification,
        )
        ToggleRow(
            title = stringResource(Res.string.settings_economy),
            supporting = stringResource(Res.string.settings_economy_hint),
            checked = settings?.economyMode == true,
            onCheckedChange = actions.onSetEconomy,
        )
        ValueRow(
            title = stringResource(Res.string.settings_apps),
            value = settings?.appsOutsideTunnel?.takeIf { it > 0 }
                ?.let { stringResource(Res.string.settings_apps_count, it) }
                ?: stringResource(Res.string.settings_apps_none),
            leading = HydraIcons.Apps,
            onClick = onOpenApps,
        )
        SectionHeader(stringResource(Res.string.settings_rules))
        ToggleRow(
            title = stringResource(Res.string.rules_block_leaks),
            supporting = stringResource(Res.string.rules_block_leaks_hint),
            checked = settings?.blockLeaks != false,
            onCheckedChange = actions.onSetBlockLeaks,
        )
        ToggleRow(
            title = stringResource(Res.string.rules_bypass_local),
            supporting = stringResource(Res.string.rules_bypass_local_hint),
            checked = settings?.bypassLocalNetwork != false,
            onCheckedChange = actions.onSetBypassLocalNetwork,
        )
        SectionHeader(stringResource(Res.string.settings_about))
        ValueRow(
            title = stringResource(Res.string.settings_appearance),
            value = null,
            leading = HydraIcons.Palette,
            onClick = onOpenAppearance,
        )
        ValueRow(
            title = stringResource(Res.string.settings_about),
            value = null,
            leading = HydraIcons.Info,
            onClick = onOpenAbout,
        )
        ValueRow(
            title = stringResource(Res.string.settings_diagnostics),
            value = null,
            leading = HydraIcons.Logs,
            onClick = onOpenDiagnostics,
        )
        AdvancedSection(
            title = stringResource(Res.string.settings_advanced),
            caution = stringResource(Res.string.settings_advanced_caution),
            expanded = advanced,
            onToggle = { advanced = !advanced },
        ) { AdvancedSettings(state, actions) }
    }
}

@Composable
private fun AdvancedSettings(state: ScreenState, actions: AppActions) {
    val settings = state.settings
    var proxyDns by remember(settings?.proxyDnsResolver) { mutableStateOf(settings?.proxyDnsResolver.orEmpty()) }
    var directDns by remember(settings?.directDnsResolver) { mutableStateOf(settings?.directDnsResolver.orEmpty()) }
    var mtu by remember(settings?.vpnMtu) { mutableStateOf(settings?.vpnMtu?.toString().orEmpty()) }
    val reconnectHint = stringResource(Res.string.settings_needs_reconnect)
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
        HydraField(
            value = proxyDns,
            onValueChange = { proxyDns = it },
            label = stringResource(Res.string.settings_dns_proxy),
            supporting = reconnectHint,
        )
        HydraField(
            value = directDns,
            onValueChange = { directDns = it },
            label = stringResource(Res.string.settings_dns_direct),
            supporting = reconnectHint,
        )
        HydraField(
            value = mtu,
            onValueChange = { mtu = it.filter(Char::isDigit) },
            label = stringResource(Res.string.settings_mtu),
            supporting = reconnectHint,
        )
        PrimaryAction(
            label = stringResource(Res.string.action_save),
            enabled = proxyDns.isNotBlank() && directDns.isNotBlank() &&
                mtu.toIntOrNull()?.let { it in MTU_RANGE } == true,
            onClick = {
                actions.onSetProxyDns(proxyDns.trim())
                actions.onSetDirectDns(directDns.trim())
                mtu.toIntOrNull()?.let(actions.onSetMtu)
            },
        )
    }
}

private val MTU_RANGE = 1280..9000
