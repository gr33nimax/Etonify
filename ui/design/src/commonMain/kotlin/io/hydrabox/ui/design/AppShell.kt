package io.hydrabox.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

enum class WindowClass { COMPACT, MEDIUM, EXPANDED }

fun windowClass(width: Int) = when {
    width < 600 -> WindowClass.COMPACT
    width < 840 -> WindowClass.MEDIUM
    else -> WindowClass.EXPANDED
}

/** One destination of the main navigation: a task, with an icon, and a mark when it needs attention. */
data class ShellDestination(val label: String, val icon: ImageVector, val attention: Boolean = false)

/**
 * The frame every screen sits in: a bar that says where you are, navigation that says what
 * else there is, and one place for transient messages.
 *
 * The information architecture does not change with width — only the navigation chrome
 * moves from the bottom to the side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    title: String,
    destinations: List<ShellDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable (WindowClass, PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = windowClass(maxWidth.value.toInt())
        Row(modifier = Modifier.fillMaxSize()) {
            if (width != WindowClass.COMPACT) {
                NavigationRail {
                    destinations.forEachIndexed { index, destination ->
                        NavigationRailItem(
                            selected = selected == index,
                            onClick = { onSelect(index) },
                            icon = { DestinationIcon(destination) },
                            label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                topBar = {
                    TopAppBar(
                        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                        actions = { actions() },
                        scrollBehavior = scrollBehavior,
                    )
                },
                bottomBar = {
                    if (width == WindowClass.COMPACT) {
                        NavigationBar {
                            destinations.forEachIndexed { index, destination ->
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = { onSelect(index) },
                                    icon = { DestinationIcon(destination) },
                                    label = { Text(destination.label) },
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding -> content(width, padding) }
        }
    }
}

@Composable
private fun DestinationIcon(destination: ShellDestination) {
    if (destination.attention) {
        BadgedBox(badge = { Badge() }) {
            Icon(destination.icon, contentDescription = null, modifier = Modifier.size(24.dp))
        }
    } else {
        Icon(destination.icon, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

/**
 * A screen opened from another screen: one title, one way back. Detail screens are how
 * rarely used settings stay out of the main navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    title: String,
    onBack: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(HydraIcons.Back, contentDescription = backLabel)
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.spacing),
            content = content,
        )
    }
}
