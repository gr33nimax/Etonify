package io.hydrabox.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import io.hydrabox.core.projection.Appearance
import io.hydrabox.core.projection.AppsMode
import io.hydrabox.core.projection.Connection
import io.hydrabox.core.projection.Language
import io.hydrabox.core.projection.LogDetail
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.EmptyState
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.HydraRow
import io.hydrabox.ui.design.MetricTile
import io.hydrabox.ui.design.OptionRow
import io.hydrabox.ui.design.SectionHeader
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.ToggleRow
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import org.jetbrains.compose.resources.stringResource

/** Which apps skip the tunnel. A list of apps with switches, not a comma-separated field. */
@Composable
fun AppsScreen(state: ScreenState, actions: AppActions) {
    var filter by remember { mutableStateOf("") }
    val mode = state.settings?.appsMode ?: AppsMode.BYPASS_SELECTED
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        Text(
            stringResource(Res.string.apps_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(UiTokens.spacing),
        )
        SectionHeader(stringResource(Res.string.apps_mode_title))
        OptionRow(
            title = stringResource(Res.string.apps_mode_off),
            supporting = stringResource(Res.string.apps_mode_off_hint),
            selected = mode == AppsMode.OFF,
            onClick = { actions.onSetAppsMode(AppsMode.OFF) },
        )
        OptionRow(
            title = stringResource(Res.string.apps_mode_bypass),
            supporting = stringResource(Res.string.apps_mode_bypass_hint),
            selected = mode == AppsMode.BYPASS_SELECTED,
            onClick = { actions.onSetAppsMode(AppsMode.BYPASS_SELECTED) },
        )
        OptionRow(
            title = stringResource(Res.string.apps_mode_only),
            supporting = stringResource(Res.string.apps_mode_only_hint),
            selected = mode == AppsMode.ONLY_SELECTED,
            onClick = { actions.onSetAppsMode(AppsMode.ONLY_SELECTED) },
        )
        if (state.apps.isEmpty()) {
            EmptyState(
                icon = HydraIcons.Apps,
                title = stringResource(Res.string.apps_title),
                body = stringResource(Res.string.apps_body),
                primaryLabel = stringResource(Res.string.apps_load),
                onPrimary = actions.onLoadApps,
            )
            return@Column
        }
        HydraField(
            value = filter,
            onValueChange = { filter = it },
            label = stringResource(Res.string.apps_search),
        )
        state.apps
            .filter { filter.isBlank() || it.label.contains(filter, ignoreCase = true) }
            .take(APP_LIMIT)
            .forEach { app ->
                ToggleRow(
                    title = app.label,
                    supporting = null,
                    checked = app.excluded,
                    onCheckedChange = { actions.onToggleApp(app.packageName) },
                )
            }
    }
}

/** Live traffic, opened from the home screen rather than owning a tab. */
@Composable
fun TrafficScreen(state: ScreenState) {
    val traffic = (state.connection as? Connection.Connected)?.traffic
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        if (traffic == null || !traffic.available) {
            EmptyState(
                icon = HydraIcons.Download,
                title = stringResource(Res.string.traffic_title),
                body = stringResource(Res.string.traffic_unavailable),
            )
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing), modifier = Modifier.fillMaxWidth()) {
            MetricTile(stringResource(Res.string.traffic_down), traffic.downlink, HydraIcons.Download, Modifier.weight(1f))
            MetricTile(stringResource(Res.string.traffic_up), traffic.uplink, HydraIcons.Upload, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing), modifier = Modifier.fillMaxWidth()) {
            MetricTile(stringResource(Res.string.traffic_down_total), traffic.downlinkTotal, HydraIcons.Download, Modifier.weight(1f))
            MetricTile(stringResource(Res.string.traffic_up_total), traffic.uplinkTotal, HydraIcons.Upload, Modifier.weight(1f))
        }
        HydraRow(
            title = stringResource(Res.string.traffic_connections),
            supporting = traffic.connections.toString(),
            leading = HydraIcons.Globe,
        )
    }
}

/**
 * The one screen allowed to name runtime internals. Everything the audit banned from the
 * main interface — the phase, the transport, the error code — is here, and only here.
 */
@Composable
fun DiagnosticsScreen(state: ScreenState, actions: AppActions) {
    val diagnostics = state.diagnostics
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        Text(
            stringResource(Res.string.diagnostics_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(UiTokens.spacing),
        )
        HydraRow(stringResource(Res.string.diagnostics_runtime), diagnostics?.runtimeState)
        HydraRow(stringResource(Res.string.diagnostics_transport), diagnostics?.transport)
        diagnostics?.lastErrorCode?.let { HydraRow(stringResource(Res.string.diagnostics_error), it) }
        SectionHeader(stringResource(Res.string.diagnostics_level))
        listOf(
            LogDetail.ERROR to Res.string.diagnostics_level_error,
            LogDetail.WARN to Res.string.diagnostics_level_warn,
            LogDetail.INFO to Res.string.diagnostics_level_info,
            LogDetail.DEBUG to Res.string.diagnostics_level_debug,
            LogDetail.TRACE to Res.string.diagnostics_level_trace,
        ).forEach { (value, label) ->
            OptionRow(
                title = stringResource(label),
                supporting = null,
                selected = (state.settings?.logDetail ?: LogDetail.WARN) == value,
                onClick = { actions.onSetLogDetail(value) },
            )
        }
        SecondaryAction(stringResource(Res.string.diagnostics_export), onClick = actions.onExportDiagnostics)
        SectionHeader(stringResource(Res.string.diagnostics_title))
        if (diagnostics == null || diagnostics.recentEvents.isEmpty()) {
            Text(
                stringResource(Res.string.diagnostics_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(UiTokens.spacing),
            )
        } else {
            diagnostics.recentEvents.forEach { event -> HydraRow(event) }
        }
    }
}

/** Version, the legal documents, and nothing that pretends to be a feature. */
@Composable
fun AboutScreen(version: String, coreVersion: String, onOpenTerms: () -> Unit, onOpenPrivacy: () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        HydraRow(stringResource(Res.string.app_name), stringResource(Res.string.about_version, version))
        HydraRow(stringResource(Res.string.about_core, coreVersion))
        ValueRow(stringResource(Res.string.about_terms), null, HydraIcons.Info, onOpenTerms)
        ValueRow(stringResource(Res.string.about_privacy), null, HydraIcons.Info, onOpenPrivacy)
    }
}

/** Theme and language: two choices, each with a visible effect and nothing to explain. */
@Composable
fun AppearanceScreen(state: ScreenState, actions: AppActions) {
    val settings = state.settings
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        SectionHeader(stringResource(Res.string.appearance_theme))
        listOf(
            Appearance.SYSTEM to Res.string.theme_system,
            Appearance.LIGHT to Res.string.theme_light,
            Appearance.DARK to Res.string.theme_dark,
        ).forEach { (value, label) ->
            OptionRow(
                title = stringResource(label),
                supporting = null,
                selected = (settings?.appearance ?: Appearance.SYSTEM) == value,
                onClick = { actions.onSetAppearance(value) },
            )
        }
        if (settings?.languageChoice != true) return@Column
        SectionHeader(stringResource(Res.string.appearance_language))
        listOf(
            Language.SYSTEM to Res.string.language_system,
            Language.RUSSIAN to Res.string.language_ru,
            Language.ENGLISH to Res.string.language_en,
        ).forEach { (value, label) ->
            OptionRow(
                title = stringResource(label),
                supporting = null,
                selected = (settings?.language ?: Language.SYSTEM) == value,
                onClick = { actions.onSetLanguage(value) },
            )
        }
    }
}

private const val APP_LIMIT = 200
