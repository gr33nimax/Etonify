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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
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
import io.hydrabox.ui.design.ChoiceDialog
import io.hydrabox.ui.design.EmptyState
import io.hydrabox.ui.design.HydraField
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.HydraRow
import io.hydrabox.ui.design.MetricTile
import io.hydrabox.ui.design.OptionRow
import io.hydrabox.ui.design.SectionGroup
import io.hydrabox.ui.design.Sparkline
import io.hydrabox.ui.design.SecondaryAction
import io.hydrabox.ui.design.ToggleRow
import io.hydrabox.ui.design.UiTokens
import io.hydrabox.ui.design.ValueRow
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Which apps use the tunnel. A list of apps with switches, not a comma-separated field. */
@Composable
fun AppsScreen(state: ScreenState, actions: AppActions) {
    var filter by remember { mutableStateOf("") }
    val mode = state.settings?.appsMode ?: AppsMode.BYPASS_SELECTED
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        SectionGroup(stringResource(Res.string.apps_mode_title)) {
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
        }
        if (state.apps.isEmpty()) {
            EmptyState(
                icon = HydraIcons.Apps,
                title = stringResource(Res.string.apps_title),
                body = stringResource(Res.string.apps_select_first),
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
        val matching = state.apps.filter { filter.isBlank() || it.label.contains(filter, ignoreCase = true) }
        // The chosen set is bounded by what the tunnel can carry in its package list, and a
        // switch that silently refuses to stick is worse than one that is visibly unavailable.
        val chosen = state.apps.count { it.excluded }
        val full = chosen >= APP_SELECTION_LIMIT
        if (chosen > 0) {
            Text(
                stringResource(Res.string.apps_selected, chosen, APP_SELECTION_LIMIT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = UiTokens.spacing * 2),
            )
        }
        SectionGroup {
            matching.take(APP_LIMIT).forEach { app ->
                ToggleRow(
                    title = app.label,
                    supporting = null,
                    checked = app.excluded,
                    enabled = app.excluded || !full,
                    onCheckedChange = { actions.onToggleApp(app.packageName) },
                )
            }
        }
        if (matching.size > APP_LIMIT) {
            Text(
                stringResource(Res.string.apps_truncated, APP_LIMIT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = UiTokens.spacing * 2),
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
                icon = HydraIcons.Traffic,
                title = stringResource(Res.string.traffic_title),
                body = stringResource(Res.string.traffic_unavailable),
            )
            return@Column
        }
        // The graph belongs here rather than on the home screen: 1.x kept its charts on a
        // traffic page for the same reason — a shape over time is something you come to look
        // at, not something you glance at while deciding whether to press connect.
        val (down, up) = rememberThroughput(traffic.downlinkRate, traffic.uplinkRate)
        Sparkline(down = down, up = up, modifier = Modifier.padding(top = UiTokens.spacing))
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing), modifier = Modifier.fillMaxWidth()) {
            MetricTile(stringResource(Res.string.traffic_down), traffic.downlink, HydraIcons.Download, Modifier.weight(1f))
            MetricTile(stringResource(Res.string.traffic_up), traffic.uplink, HydraIcons.Upload, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.spacing), modifier = Modifier.fillMaxWidth()) {
            MetricTile(stringResource(Res.string.traffic_down_total), traffic.downlinkTotal, HydraIcons.Download, Modifier.weight(1f))
            MetricTile(stringResource(Res.string.traffic_up_total), traffic.uplinkTotal, HydraIcons.Upload, Modifier.weight(1f))
        }
        SectionGroup {
            HydraRow(
                title = stringResource(Res.string.traffic_connections),
                supporting = traffic.connections.toString(),
                leading = HydraIcons.Connections,
            )
        }
    }
}

/**
 * What a support conversation needs, and nothing that only the runtime understands.
 *
 * The phase, the transport, the lane count and the generations used to be the first four rows
 * here. They are gone: no one outside this codebase could act on them, and the journal already
 * says the same things in sentences. What is left is which build is running, where it is
 * connected, which resolver it asks, the last failure, and the way into the journal.
 */
@Composable
fun DiagnosticsScreen(state: ScreenState, actions: AppActions, onOpenJournal: () -> Unit) {
    val diagnostics = state.diagnostics
    var pickingLevel by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
        modifier = Modifier.fillMaxWidth().padding(horizontal = UiTokens.spacing * 2),
    ) {
        SectionGroup {
            HydraRow(
            title = stringResource(Res.string.diagnostics_version),
            supporting = listOfNotNull(
                diagnostics?.appVersion?.takeIf(String::isNotBlank),
                diagnostics?.coreVersion?.takeIf(String::isNotBlank),
            ).joinToString(" · "),
        )
            HydraRow(
            title = stringResource(Res.string.diagnostics_server),
            supporting = diagnostics?.activeServer ?: stringResource(Res.string.server_auto),
        )
            HydraRow(
            title = stringResource(Res.string.diagnostics_dns),
            supporting = diagnostics?.dnsResolver?.takeIf(String::isNotBlank),
        )
            diagnostics?.lastError?.let {
                HydraRow(title = stringResource(Res.string.diagnostics_error), supporting = it)
            }
            ValueRow(
            title = stringResource(Res.string.journal_title),
            value = stringResource(Res.string.journal_count, diagnostics?.journal?.size ?: 0),
            onClick = onOpenJournal,
        )
            ValueRow(
            title = stringResource(Res.string.diagnostics_level),
            value = diagnostics?.level?.uppercase(),
            onClick = { pickingLevel = true },
            )
        }
        SecondaryAction(stringResource(Res.string.diagnostics_export), onClick = actions.onExportDiagnostics)
    }
    if (pickingLevel) {
        ChoiceDialog(
            title = stringResource(Res.string.diagnostics_level),
            dismissLabel = stringResource(Res.string.action_cancel),
            onDismiss = { pickingLevel = false },
        ) {
            listOf(
                LogDetail.OFF to Res.string.diagnostics_level_off,
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
                    onClick = { actions.onSetLogDetail(value); pickingLevel = false },
                )
            }
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
        SectionGroup {
            HydraRow(stringResource(Res.string.app_name), stringResource(Res.string.about_version, version))
            HydraRow(stringResource(Res.string.about_core, coreVersion))
            ValueRow(stringResource(Res.string.about_terms), null, HydraIcons.Document, onOpenTerms)
            ValueRow(stringResource(Res.string.about_privacy), null, HydraIcons.Lock, onOpenPrivacy)
        }
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
        SectionGroup(stringResource(Res.string.appearance_theme)) {
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
        }
        SectionGroup(stringResource(Res.string.appearance_colour)) {
            OptionRow(
            title = stringResource(Res.string.colour_brand),
            supporting = stringResource(Res.string.colour_brand_hint),
            selected = settings?.dynamicColour != true,
            onClick = { actions.onSetDynamicColour(false) },
        )
            OptionRow(
            title = stringResource(Res.string.colour_system),
            supporting = stringResource(Res.string.colour_system_hint),
            selected = settings?.dynamicColour == true,
            onClick = { actions.onSetDynamicColour(true) },
            )
        }
        if (settings?.languageChoice != true) return@Column
        SectionGroup(stringResource(Res.string.appearance_language)) {
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
}

private const val APP_LIMIT = 200

/** As many packages as the tunnel's include/exclude list holds; the store enforces the same. */
private const val APP_SELECTION_LIMIT = 128

/**
 * A minute of samples, kept in the screen rather than in the runtime: the snapshot already
 * arrives once a second, and a chart of the last minute is a screen's own memory.
 */
@Composable
private fun rememberThroughput(down: Long, up: Long): Pair<List<Long>, List<Long>> {
    val downs = remember { mutableStateListOf<Long>() }
    val ups = remember { mutableStateListOf<Long>() }
    val latest = rememberUpdatedState(down to up)
    LaunchedEffect(Unit) {
        while (true) {
            downs += latest.value.first
            ups += latest.value.second
            while (downs.size > TRAFFIC_SAMPLES) downs.removeAt(0)
            while (ups.size > TRAFFIC_SAMPLES) ups.removeAt(0)
            delay(1000)
        }
    }
    return downs to ups
}

private const val TRAFFIC_SAMPLES = 60
