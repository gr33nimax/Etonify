package io.hydrabox.ui.app

import io.hydrabox.core.projection.Appearance
import io.hydrabox.core.projection.AppsMode
import io.hydrabox.core.projection.Language
import io.hydrabox.core.projection.LogDetail
import io.hydrabox.core.projection.TlsFragmentation
import io.hydrabox.core.projection.TunnelStack

/**
 * Everything the screens can ask the platform to do. No screen performs an action itself,
 * and no screen decides what the runtime does with it — pressing "connect" while a tunnel
 * is up is the platform's problem, not the button's.
 */
data class AppActions(
    val onConnect: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onGrantPermission: () -> Unit = {},
    val onAddSource: (String, String) -> Unit = { _, _ -> },
    val onAddSourceFromFile: () -> Unit = {},
    val onRefreshSource: (String) -> Unit = {},
    val onRenameSource: (String, String) -> Unit = { _, _ -> },
    val onRemoveSource: (String) -> Unit = {},
    val onSelectServer: (String) -> Unit = {},
    val onMeasure: () -> Unit = {},
    val onAcceptLegal: () -> Unit = {},
    val onSetEconomy: (Boolean) -> Unit = {},
    val onToggleNotification: (Boolean) -> Unit = {},
    val onSetProxyDns: (String) -> Unit = {},
    val onSetDirectDns: (String) -> Unit = {},
    val onSetMtu: (Int) -> Unit = {},
    val onSetBlockLeaks: (Boolean) -> Unit = {},
    val onSetBypassLocalNetwork: (Boolean) -> Unit = {},
    val onSetAppsMode: (AppsMode) -> Unit = {},
    val onSetStrictRoute: (Boolean) -> Unit = {},
    val onSetStack: (TunnelStack) -> Unit = {},
    val onSetFragmentation: (TlsFragmentation) -> Unit = {},
    val onSetLogDetail: (LogDetail) -> Unit = {},
    val onSetAppearance: (Appearance) -> Unit = {},
    val onSetLanguage: (Language) -> Unit = {},
    val onToggleApp: (String) -> Unit = {},
    val onLoadApps: () -> Unit = {},
    val onExportDiagnostics: () -> Unit = {},
    val onExportBackup: (String) -> Unit = {},
    val onImportBackup: (String) -> Unit = {},
    val onResetSettings: () -> Unit = {},
    val onNoticeShown: () -> Unit = {},
)
