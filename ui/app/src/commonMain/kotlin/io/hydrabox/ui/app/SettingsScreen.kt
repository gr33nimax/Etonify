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
import io.hydrabox.core.projection.TlsFragmentation
import io.hydrabox.core.projection.TunnelStack
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import io.hydrabox.ui.design.AdvancedSection
import io.hydrabox.ui.design.ConfirmDialog
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraRow
import io.hydrabox.ui.design.InputDialog
import io.hydrabox.ui.design.LoadingRows
import io.hydrabox.ui.design.OptionRow
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
        AdBlockRow(state, actions)
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
        SectionHeader(stringResource(Res.string.proxy_section))
        ToggleRow(
            title = stringResource(Res.string.proxy_only),
            supporting = stringResource(Res.string.proxy_only_hint),
            checked = settings?.proxyOnly == true,
            onCheckedChange = actions.onSetProxyOnly,
        )
        if (settings?.proxyOnly == true) {
            var port by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
            HydraField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = stringResource(Res.string.proxy_port),
                supporting = stringResource(Res.string.settings_needs_reconnect),
            )
            PrimaryAction(
                label = stringResource(Res.string.action_save),
                enabled = port.toIntOrNull()?.let { it in PROXY_PORT_RANGE } == true,
                onClick = { port.toIntOrNull()?.let(actions.onSetProxyPort) },
            )
            ToggleRow(
                title = stringResource(Res.string.proxy_allow_lan),
                supporting = stringResource(Res.string.proxy_allow_lan_hint),
                checked = settings.proxyAllowLan,
                onCheckedChange = actions.onSetProxyAllowLan,
            )
        }
        SectionHeader(stringResource(Res.string.advanced_stack))
        ToggleRow(
            title = stringResource(Res.string.advanced_strict_route),
            supporting = stringResource(Res.string.advanced_strict_route_hint),
            checked = settings?.strictRoute == true,
            onCheckedChange = actions.onSetStrictRoute,
        )
        listOf(
            TunnelStack.MIXED to Res.string.stack_mixed,
            TunnelStack.SYSTEM to Res.string.stack_system,
            TunnelStack.GVISOR to Res.string.stack_gvisor,
        ).forEach { (value, label) ->
            OptionRow(
                title = stringResource(label),
                supporting = null,
                selected = (settings?.stack ?: TunnelStack.MIXED) == value,
                onClick = { actions.onSetStack(value) },
            )
        }
        SectionHeader(stringResource(Res.string.advanced_fragmentation))
        Text(
            stringResource(Res.string.advanced_fragmentation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = UiTokens.spacing),
        )
        listOf(
            TlsFragmentation.OFF to Res.string.fragmentation_off,
            TlsFragmentation.RECORD to Res.string.fragmentation_record,
            TlsFragmentation.FRAGMENT to Res.string.fragmentation_fragment,
        ).forEach { (value, label) ->
            OptionRow(
                title = stringResource(label),
                supporting = null,
                selected = (settings?.fragmentation ?: TlsFragmentation.OFF) == value,
                onClick = { actions.onSetFragmentation(value) },
            )
        }
        SectionHeader(stringResource(Res.string.backup_title))
        BackupSettings(actions)
    }
}

/**
 * The blocking switch, and what it is allowed to promise. Until the compiled list is on the
 * device there is nothing to switch on, so the row offers the download instead of a toggle
 * that would do nothing.
 */
@Composable
private fun AdBlockRow(state: ScreenState, actions: AppActions) {
    val rules = state.ruleSets
    if (!rules.available) {
        HydraRow(
            title = stringResource(Res.string.rules_ad_block_download),
            supporting = stringResource(Res.string.rules_ad_block_download_hint),
            leading = HydraIcons.Download,
            onClick = actions.onUpdateRuleSets,
        )
        if (rules.downloading) LoadingRows(1)
        return
    }
    ToggleRow(
        title = stringResource(Res.string.rules_ad_block),
        supporting = stringResource(
            Res.string.rules_ad_block_hint,
            rules.blockedDomains,
            rules.updatedAt.orEmpty(),
        ),
        checked = state.settings?.adBlock == true,
        onCheckedChange = actions.onSetAdBlock,
    )
    HydraRow(
        title = stringResource(Res.string.rules_ad_block_update),
        leading = HydraIcons.Refresh,
        onClick = actions.onUpdateRuleSets,
    )
    if (rules.downloading) LoadingRows(1)
}

/** Which passphrase question is open. Nothing about a backup happens without one. */
private enum class BackupIntent { EXPORT, IMPORT }

/**
 * Saving and restoring: two actions, one warning, and a passphrase. The document carries
 * access keys, so the file is encrypted with something only the person knows.
 */
@Composable
private fun BackupSettings(actions: AppActions) {
    var intent by remember { mutableStateOf<BackupIntent?>(null) }
    var confirmImport by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var resetting by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.spacing)) {
        Text(
            stringResource(Res.string.backup_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = UiTokens.spacing),
        )
        HydraRow(
            title = stringResource(Res.string.backup_export),
            leading = HydraIcons.Upload,
            onClick = { passphrase = ""; intent = BackupIntent.EXPORT },
        )
        HydraRow(
            title = stringResource(Res.string.backup_import),
            leading = HydraIcons.Download,
            onClick = { passphrase = ""; confirmImport = true },
        )
        HydraRow(
            title = stringResource(Res.string.settings_reset),
            supporting = stringResource(Res.string.settings_reset_body),
            leading = HydraIcons.Refresh,
            onClick = { resetting = true },
        )
    }
    if (confirmImport) {
        ConfirmDialog(
            title = stringResource(Res.string.backup_import_title),
            body = stringResource(Res.string.backup_import_body),
            confirmLabel = stringResource(Res.string.action_continue),
            dismissLabel = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = { confirmImport = false; intent = BackupIntent.IMPORT },
            onDismiss = { confirmImport = false },
        )
    }
    intent?.let { open ->
        InputDialog(
            title = stringResource(Res.string.backup_title),
            value = passphrase,
            onValueChange = { passphrase = it },
            label = stringResource(Res.string.backup_passphrase),
            confirmLabel = stringResource(
                if (open == BackupIntent.EXPORT) Res.string.action_export else Res.string.action_import,
            ),
            dismissLabel = stringResource(Res.string.action_cancel),
            onConfirm = {
                if (open == BackupIntent.EXPORT) actions.onExportBackup(passphrase) else actions.onImportBackup(passphrase)
                intent = null
            },
            onDismiss = { intent = null },
        )
    }
    if (resetting) {
        ConfirmDialog(
            title = stringResource(Res.string.settings_reset),
            body = stringResource(Res.string.settings_reset_body),
            confirmLabel = stringResource(Res.string.action_continue),
            dismissLabel = stringResource(Res.string.action_cancel),
            destructive = true,
            onConfirm = { actions.onResetSettings(); resetting = false },
            onDismiss = { resetting = false },
        )
    }
}

private val MTU_RANGE = 1280..9000
private val PROXY_PORT_RANGE = 1024..65535
