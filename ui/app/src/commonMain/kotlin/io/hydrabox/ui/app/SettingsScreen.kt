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
import io.hydrabox.core.projection.AppsMode
import io.hydrabox.core.projection.DnsMode
import io.hydrabox.core.projection.NotificationDetail
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.core.projection.TlsFragmentation
import io.hydrabox.core.projection.TunnelStack
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.ChoiceDialog
import io.hydrabox.ui.design.ConfirmDialog
import io.hydrabox.ui.design.HydraRow
import io.hydrabox.ui.design.InputDialog
import io.hydrabox.ui.design.LoadingRows
import io.hydrabox.ui.design.OptionRow
import io.hydrabox.ui.design.SectionGroup
import io.hydrabox.ui.design.ToggleRow
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import org.jetbrains.compose.resources.stringResource

/** Which question is open. One at a time, and none of them is a screen of its own. */
private enum class SettingsAsk { NOTIFICATION, DNS_ANSWERS, DNS_BOOTSTRAP, DNS_PROXY, DNS_DIRECT, MTU, FRAGMENTATION, STACK, PROXY_PORT }

/**
 * Everything a person can change, grouped by what it is for rather than by how technical it
 * looks. Two rules hold the screen together: a row exists only if it reaches storage and the
 * generated configuration, and a row explains a consequence only when the title does not
 * already say it — the reconnect notice is shown by the app itself, so no row repeats it.
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
    var ask by remember { mutableStateOf<SettingsAsk?>(null) }
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        SectionGroup(stringResource(Res.string.settings_connection)) {
            OptionRow(
            title = stringResource(Res.string.settings_mode_vpn),
            supporting = stringResource(Res.string.settings_mode_vpn_hint),
            selected = settings?.proxyOnly != true,
            onClick = { actions.onSetProxyOnly(false) },
        )
            OptionRow(
            title = stringResource(Res.string.settings_mode_proxy),
            supporting = stringResource(Res.string.settings_mode_proxy_hint),
            selected = settings?.proxyOnly == true,
            onClick = { actions.onSetProxyOnly(true) },
        )
            if (settings?.proxyOnly == true) {
                ValueRow(
                title = stringResource(Res.string.settings_proxy_port),
                value = settings.proxyPort.toString(),
                onClick = { ask = SettingsAsk.PROXY_PORT },
            )
                ToggleRow(
                title = stringResource(Res.string.settings_proxy_lan),
                supporting = null,
                checked = settings.proxyAllowLan,
                onCheckedChange = actions.onSetProxyAllowLan,
            )
            }
            ValueRow(
            title = stringResource(Res.string.settings_notification),
            value = notificationLabel(settings?.notificationDetail ?: NotificationDetail.SPEED),
            onClick = { ask = SettingsAsk.NOTIFICATION },
        )
            ToggleRow(
            title = stringResource(Res.string.settings_interrupt),
            supporting = null,
            checked = settings?.interruptConnections == true,
            onCheckedChange = actions.onSetInterruptConnections,
            )
        }
        SectionGroup(stringResource(Res.string.settings_dns)) {
            ValueRow(
            title = stringResource(Res.string.settings_dns_answers),
            value = dnsModeLabel(settings?.dnsMode ?: DnsMode.IPV4),
            onClick = { ask = SettingsAsk.DNS_ANSWERS },
        )
            ValueRow(
            title = stringResource(Res.string.settings_dns_proxy),
            value = settings?.proxyDnsResolver,
            onClick = { ask = SettingsAsk.DNS_PROXY },
        )
            ValueRow(
            title = stringResource(Res.string.settings_dns_bootstrap),
            value = settings?.bootstrapDnsResolver,
            onClick = { ask = SettingsAsk.DNS_BOOTSTRAP },
        )
            ValueRow(
            title = stringResource(Res.string.settings_dns_direct),
            value = settings?.directDnsResolver,
            onClick = { ask = SettingsAsk.DNS_DIRECT },
        )
            ToggleRow(
            title = stringResource(Res.string.settings_fakeip),
            supporting = stringResource(Res.string.settings_fakeip_hint),
            checked = settings?.fakeIp == true,
            onCheckedChange = actions.onSetFakeIp,
            )
        }
        SectionGroup(stringResource(Res.string.settings_routing)) {
            ToggleRow(
            title = stringResource(Res.string.rules_bypass_local),
            supporting = stringResource(Res.string.rules_bypass_local_hint),
            checked = settings?.bypassLocalNetwork != false,
            onCheckedChange = actions.onSetBypassLocalNetwork,
        )
            ToggleRow(
            title = stringResource(Res.string.rules_block_leaks),
            supporting = stringResource(Res.string.rules_block_leaks_hint),
            checked = settings?.blockLeaks != false,
            onCheckedChange = actions.onSetBlockLeaks,
        )
            ToggleRow(
            title = stringResource(Res.string.settings_strict_route),
            supporting = stringResource(Res.string.settings_strict_route_hint),
            checked = settings?.strictRoute == true,
            onCheckedChange = actions.onSetStrictRoute,
            )
        }
        SectionGroup(stringResource(Res.string.settings_apps)) {
            ValueRow(
            title = stringResource(Res.string.settings_apps_manage),
            value = appsSummary(
                mode = settings?.appsMode ?: AppsMode.BYPASS_SELECTED,
                selectedCount = settings?.appsOutsideTunnel ?: 0,
            ),
            onClick = onOpenApps,
            )
        }
        SectionGroup(stringResource(Res.string.settings_ad_block)) { AdBlockRow(state, actions) }
        SectionGroup(stringResource(Res.string.settings_performance)) {
            ToggleRow(
            title = stringResource(Res.string.settings_economy),
            supporting = stringResource(Res.string.settings_economy_hint),
            checked = settings?.economyMode == true,
            onCheckedChange = actions.onSetEconomy,
        )
            ToggleRow(
            title = stringResource(Res.string.settings_tcp_fast_open),
            supporting = stringResource(Res.string.settings_tcp_fast_open_hint),
            checked = settings?.tcpFastOpen == true,
            onCheckedChange = actions.onSetTcpFastOpen,
        )
            ToggleRow(
            title = stringResource(Res.string.settings_tcp_multipath),
            supporting = stringResource(Res.string.settings_tcp_multipath_hint),
            checked = settings?.tcpMultiPath == true,
            onCheckedChange = actions.onSetTcpMultiPath,
        )
            ValueRow(
            title = stringResource(Res.string.settings_mtu),
            value = (settings?.vpnMtu ?: 9000).toString(),
            onClick = { ask = SettingsAsk.MTU },
            )
        }
        SectionGroup(stringResource(Res.string.settings_compatibility)) {
            ValueRow(
            title = stringResource(Res.string.settings_fragmentation),
            value = fragmentationLabel(settings?.fragmentation ?: TlsFragmentation.OFF),
            onClick = { ask = SettingsAsk.FRAGMENTATION },
        )
            ValueRow(
            title = stringResource(Res.string.settings_stack),
            value = stackLabel(settings?.stack ?: TunnelStack.MIXED),
            onClick = { ask = SettingsAsk.STACK },
            )
        }
        SectionGroup(stringResource(Res.string.settings_interface)) {
            ValueRow(stringResource(Res.string.settings_appearance), null, onClick = onOpenAppearance)
        }
        SectionGroup(stringResource(Res.string.settings_support)) {
            ValueRow(stringResource(Res.string.settings_about), null, onClick = onOpenAbout)
            ValueRow(stringResource(Res.string.settings_diagnostics_open), null, onClick = onOpenDiagnostics)
        }
        SectionGroup(stringResource(Res.string.backup_title)) { BackupSettings(actions) }
    }
    Asks(ask, state, actions) { ask = null }
}

/**
 * The questions behind the rows. A value that is chosen once a year is asked for in a dialog,
 * so the everyday screen stays a list of answers rather than a list of radio buttons.
 */
@Composable
private fun Asks(ask: SettingsAsk?, state: ScreenState, actions: AppActions, onClose: () -> Unit) {
    val settings = state.settings
    val cancel = stringResource(Res.string.action_cancel)
    when (ask) {
        null -> Unit
        SettingsAsk.NOTIFICATION -> ChoiceDialog(stringResource(Res.string.settings_notification), cancel, onClose) {
            listOf(
                NotificationDetail.OFF to Res.string.notification_off,
                NotificationDetail.SPEED to Res.string.notification_speed,
                NotificationDetail.TOTAL to Res.string.notification_total,
                NotificationDetail.BOTH to Res.string.notification_both,
            ).forEach { (value, label) ->
                OptionRow(
                    title = stringResource(label),
                    supporting = null,
                    selected = (settings?.notificationDetail ?: NotificationDetail.SPEED) == value,
                    onClick = { actions.onSetNotificationDetail(value); onClose() },
                )
            }
        }
        SettingsAsk.DNS_ANSWERS -> ChoiceDialog(stringResource(Res.string.settings_dns_answers), cancel, onClose) {
            listOf(
                DnsMode.AUTO to Res.string.dns_auto,
                DnsMode.IPV4 to Res.string.dns_ipv4,
                DnsMode.IPV6 to Res.string.dns_ipv6,
            ).forEach { (value, label) ->
                OptionRow(
                    title = stringResource(label),
                    supporting = null,
                    selected = (settings?.dnsMode ?: DnsMode.IPV4) == value,
                    onClick = { actions.onSetDnsMode(value); onClose() },
                )
            }
        }
        SettingsAsk.DNS_PROXY -> ResolverAsk(
            title = stringResource(Res.string.settings_dns_proxy),
            current = settings?.proxyDnsResolver.orEmpty(),
            // Reached through the proxy, so a hostname is fine here: the bootstrap resolver
            // finds it before the tunnel carries anything.
            groups = listOf(
                "DoH" to listOf(
                    "Cloudflare" to "https://dns.cloudflare.com/dns-query",
                    "Google" to "https://dns.google/dns-query",
                    "Quad9" to "https://dns.quad9.net/dns-query",
                    "AdGuard" to "https://dns.adguard-dns.com/dns-query",
                    "Mullvad" to "https://dns.mullvad.net/dns-query",
                ),
                "DoT" to listOf(
                    "Cloudflare" to "tls://one.one.one.one",
                    "Google" to "tls://dns.google",
                    "Quad9" to "tls://dns.quad9.net",
                    "AdGuard" to "tls://dns.adguard-dns.com",
                ),
                "UDP" to listOf(
                    "Cloudflare" to "udp://1.1.1.1",
                    "Google" to "udp://8.8.8.8",
                    "Quad9" to "udp://9.9.9.9",
                ),
            ),
            onSelect = actions.onSetProxyDns,
            onDismiss = onClose,
        )
        SettingsAsk.DNS_DIRECT -> ResolverAsk(
            title = stringResource(Res.string.settings_dns_direct),
            current = settings?.directDnsResolver.orEmpty(),
            // By address, and encrypted first: a plain port 53 query outside the tunnel is
            // answered by whatever the network redirects it to — a router with its own DNS
            // replies in place of the address that was asked.
            groups = listOf(
                "DoH" to listOf(
                    "Cloudflare" to "https://1.1.1.1/dns-query",
                    "Google" to "https://8.8.8.8/dns-query",
                    "Quad9" to "https://9.9.9.9/dns-query",
                ),
                "DoT" to listOf(
                    "Cloudflare" to "tls://1.1.1.1",
                    "Google" to "tls://8.8.8.8",
                    "Quad9" to "tls://9.9.9.9",
                ),
                "UDP" to listOf(
                    "Cloudflare" to "udp://1.1.1.1",
                    "Google" to "udp://8.8.8.8",
                    "Quad9" to "udp://9.9.9.9",
                    "Yandex" to "udp://77.88.8.8",
                    "AdGuard" to "udp://94.140.14.14",
                ),
            ),
            onSelect = actions.onSetDirectDns,
            onDismiss = onClose,
        )
        SettingsAsk.DNS_BOOTSTRAP -> ResolverAsk(
            title = stringResource(Res.string.settings_dns_bootstrap),
            current = settings?.bootstrapDnsResolver.orEmpty(),
            // Whatever is chosen here has to answer on a network that allows almost nothing,
            // which is why Yandex's is first: behind an operator white list it is reachable
            // when 1.1.1.1 is not, and nothing at all works until this one answers.
            groups = listOf(
                "UDP" to listOf(
                    "Yandex" to "udp://77.88.8.8",
                    "Cloudflare" to "udp://1.1.1.1",
                    "Google" to "udp://8.8.8.8",
                    "Quad9" to "udp://9.9.9.9",
                ),
                "DoH" to listOf(
                    "Cloudflare" to "https://1.1.1.1/dns-query",
                    "Google" to "https://8.8.8.8/dns-query",
                ),
            ),
            platform = true,
            onSelect = actions.onSetBootstrapDns,
            onDismiss = onClose,
        )
        SettingsAsk.MTU -> ChoiceDialog(stringResource(Res.string.settings_mtu), cancel, onClose) {
            listOf(1280, 1500, 9000).forEach { value ->
                OptionRow(
                    title = value.toString(),
                    supporting = null,
                    selected = (settings?.vpnMtu ?: 9000) == value,
                    onClick = { actions.onSetMtu(value); onClose() },
                )
            }
        }
        SettingsAsk.FRAGMENTATION -> ChoiceDialog(stringResource(Res.string.settings_fragmentation), cancel, onClose) {
            listOf(
                TlsFragmentation.OFF to Res.string.fragmentation_off,
                TlsFragmentation.RECORD to Res.string.fragmentation_record,
                TlsFragmentation.FRAGMENT to Res.string.fragmentation_fragment,
            ).forEach { (value, label) ->
                OptionRow(
                    title = stringResource(label),
                    supporting = null,
                    selected = (settings?.fragmentation ?: TlsFragmentation.OFF) == value,
                    onClick = { actions.onSetFragmentation(value); onClose() },
                )
            }
        }
        SettingsAsk.STACK -> ChoiceDialog(stringResource(Res.string.settings_stack), cancel, onClose) {
            listOf(
                TunnelStack.MIXED to Res.string.stack_mixed,
                TunnelStack.SYSTEM to Res.string.stack_system,
                TunnelStack.GVISOR to Res.string.stack_gvisor,
            ).forEach { (value, label) ->
                OptionRow(
                    title = stringResource(label),
                    supporting = null,
                    selected = (settings?.stack ?: TunnelStack.MIXED) == value,
                    onClick = { actions.onSetStack(value); onClose() },
                )
            }
        }
        SettingsAsk.PROXY_PORT -> PortAsk(settings?.proxyPort ?: 2080, actions.onSetProxyPort, onClose)
    }
}

/**
 * A resolver, by protocol. The groups are the point: DoH and DoT are a session with the
 * resolver itself and nothing between can answer for them, while a UDP resolver is answered by
 * whoever wants to — which is how a router with its own DNS ends up in a leak test. The typed
 * value is validated by the settings store, where the prefixes are understood.
 */
@Composable
private fun ResolverAsk(
    title: String,
    current: String,
    groups: List<Pair<String, List<Pair<String, String>>>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    platform: Boolean = false,
) {
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(current) }
    if (typing) {
        InputDialog(
            title = title,
            value = draft,
            onValueChange = { draft = it },
            label = stringResource(Res.string.dns_custom),
            confirmLabel = stringResource(Res.string.action_save),
            dismissLabel = stringResource(Res.string.action_cancel),
            onConfirm = { onSelect(draft.trim()); onDismiss() },
            onDismiss = onDismiss,
        )
        return
    }
    val known = groups.flatMap { it.second }.map { it.second } + listOf(PLATFORM_RESOLVER)
    ChoiceDialog(title, stringResource(Res.string.action_cancel), onDismiss) {
        if (platform) {
            ResolverCategory(stringResource(Res.string.dns_category_system))
            OptionRow(
                title = stringResource(Res.string.dns_platform),
                supporting = null,
                selected = current == PLATFORM_RESOLVER,
                onClick = { onSelect(PLATFORM_RESOLVER); onDismiss() },
            )
        }
        groups.forEach { (category, options) ->
            ResolverCategory(category)
            options.forEach { (label, value) ->
                OptionRow(
                    title = label,
                    supporting = value.substringAfter("://"),
                    selected = current == value,
                    onClick = { onSelect(value); onDismiss() },
                )
            }
        }
        ResolverCategory(stringResource(Res.string.dns_category_custom))
        OptionRow(
            title = stringResource(Res.string.dns_custom),
            supporting = current.takeUnless { it in known },
            selected = current !in known,
            onClick = { typing = true },
        )
    }
}

@Composable
private fun ResolverCategory(text: String) = Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = UiTokens.spacing / 2),
)

/** 1.x's marker for "whatever the network's own resolver is". */
private const val PLATFORM_RESOLVER = "device://network"

/** A port is a number in a range, and a number outside it is not saved. */
@Composable
private fun PortAsk(current: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(current.toString()) }
    val port = draft.trim().toIntOrNull()
    InputDialog(
        title = stringResource(Res.string.settings_proxy_port),
        value = draft,
        onValueChange = { draft = it.filter(Char::isDigit).take(5) },
        label = stringResource(Res.string.settings_proxy_port),
        confirmLabel = stringResource(Res.string.action_save),
        dismissLabel = stringResource(Res.string.action_cancel),
        onConfirm = { if (port != null && port in 1024..65535) onSelect(port); onDismiss() },
        onDismiss = onDismiss,
    )
}

@Composable
private fun notificationLabel(detail: NotificationDetail) = stringResource(
    when (detail) {
        NotificationDetail.OFF -> Res.string.notification_off
        NotificationDetail.SPEED -> Res.string.notification_speed
        NotificationDetail.TOTAL -> Res.string.notification_total
        NotificationDetail.BOTH -> Res.string.notification_both
    },
)

@Composable
private fun dnsModeLabel(mode: DnsMode) = stringResource(
    when (mode) {
        DnsMode.AUTO -> Res.string.dns_auto
        DnsMode.IPV4 -> Res.string.dns_ipv4
        DnsMode.IPV6 -> Res.string.dns_ipv6
    },
)

@Composable
private fun fragmentationLabel(mode: TlsFragmentation) = stringResource(
    when (mode) {
        TlsFragmentation.OFF -> Res.string.fragmentation_off
        TlsFragmentation.RECORD -> Res.string.fragmentation_record
        TlsFragmentation.FRAGMENT -> Res.string.fragmentation_fragment
    },
)

@Composable
private fun stackLabel(stack: TunnelStack) = stringResource(
    when (stack) {
        TunnelStack.SYSTEM -> Res.string.stack_system
        TunnelStack.GVISOR -> Res.string.stack_gvisor
        TunnelStack.MIXED -> Res.string.stack_mixed
    },
)

@Composable
private fun appsSummary(mode: AppsMode, selectedCount: Int): String = when (mode) {
    AppsMode.OFF -> stringResource(Res.string.settings_apps_all_vpn)
    AppsMode.BYPASS_SELECTED -> selectedCount.takeIf { it > 0 }
        ?.let { stringResource(Res.string.settings_apps_bypass_count, it) }
        ?: stringResource(Res.string.settings_apps_all_vpn)
    AppsMode.ONLY_SELECTED -> selectedCount.takeIf { it > 0 }
        ?.let { stringResource(Res.string.settings_apps_vpn_count, it) }
        ?: stringResource(Res.string.settings_apps_select)
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
    Column {
        Text(
            stringResource(Res.string.backup_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = UiTokens.spacing * 2, vertical = UiTokens.spacing * 1.5f),
        )
        HydraRow(
            title = stringResource(Res.string.backup_export),
            onClick = { passphrase = ""; intent = BackupIntent.EXPORT },
        )
        HydraRow(
            title = stringResource(Res.string.backup_import),
            onClick = { passphrase = ""; confirmImport = true },
        )
        HydraRow(
            title = stringResource(Res.string.settings_reset),
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
