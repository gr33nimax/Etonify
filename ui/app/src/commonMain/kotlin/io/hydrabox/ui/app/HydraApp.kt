package io.hydrabox.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.hydrabox.core.projection.Appearance
import io.hydrabox.core.projection.ScreenState
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import io.hydrabox.ui.design.DetailScreen
import io.hydrabox.ui.design.AppShell
import io.hydrabox.ui.design.HydraIcons
import io.hydrabox.ui.design.HydraTheme
import io.hydrabox.ui.design.ShellDestination
import io.hydrabox.ui.design.UiTokens
import org.jetbrains.compose.resources.stringResource

/** Where the app is. Three tasks in the navigation; everything else is opened from them. */
enum class Tab { HOME, SERVERS, SETTINGS }

sealed interface Route {
    data object Main : Route
    data object Sources : Route
    data object Traffic : Route
    data object Apps : Route
    data object Diagnostics : Route
    data object About : Route
    data object Appearance : Route
    data class Document(val privacy: Boolean) : Route
}

/**
 * Navigation state the platform can also reach, so the system back gesture pops a detail
 * screen instead of leaving the app. It is deliberately not a library: three tabs and a
 * stack of detail screens do not need one.
 */
class AppNavigation {
    var tab by mutableStateOf(Tab.HOME)
    var route by mutableStateOf<Route>(Route.Main)
        private set
    private val stack = mutableListOf<Route>()

    fun open(next: Route) {
        stack += route
        route = next
    }

    /** True when the gesture was consumed here. */
    fun back(): Boolean {
        if (route == Route.Main) return false
        route = stack.removeLastOrNull() ?: Route.Main
        return true
    }
}

@Composable
fun HydraApp(
    state: ScreenState,
    actions: AppActions = AppActions(),
    navigation: AppNavigation = remember { AppNavigation() },
    versionName: String = "",
    coreVersion: String = "",
) {
    val dark = when (state.settings?.appearance ?: Appearance.SYSTEM) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    HydraTheme(dark = dark) {
        val snackbar = remember { SnackbarHostState() }
        // "I will do this later" has to lead somewhere: the flow steps aside for this run,
        // and the home screen then asks for a subscription in its own words.
        var postponed by remember { mutableStateOf(false) }
        if (!state.onboardingComplete && !postponed && navigation.route !is Route.Document) {
            OnboardingFlow(
                state = state,
                actions = actions,
                onOpenTerms = { navigation.open(Route.Document(privacy = false)) },
                onOpenPrivacy = { navigation.open(Route.Document(privacy = true)) },
                onFinish = { postponed = true; navigation.tab = Tab.HOME },
            )
            return@HydraTheme
        }
        when (val route = navigation.route) {
            Route.Main -> {
                // The snackbar lives with its host: showing one where no host is composed —
                // during the first run, for instance — would swallow the message entirely.
                NoticeHost(state, actions, snackbar)
                MainShell(state, actions, navigation, snackbar)
            }
            is Route.Document -> Detail(
                title = stringResource(if (route.privacy) Res.string.about_privacy else Res.string.about_terms),
                navigation = navigation,
            ) {
                DocumentText(
                    stringResource(if (route.privacy) Res.string.legal_privacy_body else Res.string.legal_terms_body),
                )
            }
            Route.Sources -> Detail(stringResource(Res.string.sources_title), navigation) {
                SourcesScreen(state, actions)
            }
            Route.Traffic -> Detail(stringResource(Res.string.traffic_title), navigation) {
                TrafficScreen(state)
            }
            Route.Apps -> Detail(stringResource(Res.string.apps_title), navigation) {
                AppsScreen(state, actions)
            }
            Route.Diagnostics -> Detail(stringResource(Res.string.diagnostics_title), navigation) {
                DiagnosticsScreen(state, actions)
            }
            Route.Appearance -> Detail(stringResource(Res.string.settings_appearance), navigation) {
                AppearanceScreen(state, actions)
            }
            Route.About -> Detail(stringResource(Res.string.settings_about), navigation) {
                AboutScreen(
                    version = versionName,
                    coreVersion = coreVersion,
                    onOpenTerms = { navigation.open(Route.Document(privacy = false)) },
                    onOpenPrivacy = { navigation.open(Route.Document(privacy = true)) },
                )
            }
        }
    }
}

@Composable
private fun MainShell(
    state: ScreenState,
    actions: AppActions,
    navigation: AppNavigation,
    snackbar: SnackbarHostState,
) {
    val destinations = listOf(
        ShellDestination(stringResource(Res.string.nav_home), HydraIcons.Shield),
        ShellDestination(
            stringResource(Res.string.nav_servers),
            HydraIcons.Globe,
            attention = state.sources.any { it.problem != null },
        ),
        ShellDestination(stringResource(Res.string.nav_settings), HydraIcons.Sliders),
    )
    AppShell(
        title = stringResource(
            when (navigation.tab) {
                Tab.HOME -> Res.string.app_name
                Tab.SERVERS -> Res.string.nav_servers
                Tab.SETTINGS -> Res.string.nav_settings
            },
        ),
        destinations = destinations,
        selected = navigation.tab.ordinal,
        onSelect = { navigation.tab = Tab.entries[it] },
        snackbarHostState = snackbar,
    ) { _, padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(bottom = UiTokens.spacing * 3),
        ) {
            when (navigation.tab) {
                Tab.HOME -> HomeScreen(
                    state = state,
                    actions = actions,
                    onOpenServers = { navigation.tab = Tab.SERVERS },
                    onOpenTraffic = { navigation.open(Route.Traffic) },
                    onAddSource = { navigation.open(Route.Sources) },
                )
                Tab.SERVERS -> ServersScreen(
                    state = state,
                    actions = actions,
                    onOpenSources = { navigation.open(Route.Sources) },
                )
                Tab.SETTINGS -> SettingsScreen(
                    state = state,
                    actions = actions,
                    onOpenApps = { navigation.open(Route.Apps) },
                    onOpenDiagnostics = { navigation.open(Route.Diagnostics) },
                    onOpenAbout = { navigation.open(Route.About) },
                    onOpenAppearance = { navigation.open(Route.Appearance) },
                )
            }
        }
    }
}

@Composable
private fun Detail(title: String, navigation: AppNavigation, content: @Composable () -> Unit) = DetailScreen(
    title = title,
    onBack = { navigation.back() },
    backLabel = stringResource(Res.string.action_back),
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
}

/** One place for transient messages, and it is never a screen element. */
@Composable
private fun NoticeHost(state: ScreenState, actions: AppActions, snackbar: SnackbarHostState) {
    val notice = state.notice
    val text = notice?.let { noticeText(it) }
    LaunchedEffect(notice) {
        if (text != null) {
            snackbar.showSnackbar(text)
            actions.onNoticeShown()
        }
    }
}
