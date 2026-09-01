package io.hydrabox.ui.app

import androidx.compose.runtime.Composable
import io.hydrabox.core.projection.Connection
import io.hydrabox.core.projection.Notice
import io.hydrabox.core.projection.ServerRef
import io.hydrabox.core.projection.SourceProblem
import io.hydrabox.core.projection.Trouble
import io.hydrabox.ui.app.resources.Res
import io.hydrabox.ui.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The only place where a product state becomes a sentence.
 *
 * Screens never write text of their own, and the projection never writes text at all — it
 * hands over a typed state and this file translates it. That is what keeps the vocabulary
 * of the app single: one concept, one word, in every language.
 */
@Composable
fun connectionTitle(connection: Connection): String = stringResource(
    when (connection) {
        Connection.NeedsSubscription -> Res.string.state_needs_subscription
        Connection.NeedsServers -> Res.string.state_needs_servers
        is Connection.Idle -> Res.string.state_idle
        is Connection.Connecting -> Res.string.state_connecting
        is Connection.Connected -> Res.string.state_connected
        is Connection.Reconnecting -> Res.string.state_reconnecting
        Connection.Disconnecting -> Res.string.state_disconnecting
        is Connection.Stopped -> return troubleTitle(connection.cause, connection.server)
    },
)

@Composable
private fun troubleTitle(cause: Trouble, server: ServerRef?): String = when (cause) {
    Trouble.NO_INTERNET -> stringResource(Res.string.trouble_no_internet)
    Trouble.SERVER_UNREACHABLE -> stringResource(
        Res.string.trouble_server_unreachable,
        server?.let { serverName(it) } ?: stringResource(Res.string.home_server_none),
    )
    Trouble.SUBSCRIPTION_UNAVAILABLE -> stringResource(Res.string.trouble_subscription)
    Trouble.CONFIG_REJECTED -> stringResource(Res.string.trouble_config)
    Trouble.PERMISSION_REQUIRED -> stringResource(Res.string.trouble_permission)
    Trouble.UNKNOWN -> stringResource(Res.string.trouble_unknown)
}

/** What to do about it, in one sentence, for the states that need one. */
@Composable
fun connectionHint(connection: Connection): String? = when (connection) {
    is Connection.Stopped -> stringResource(
        when (connection.cause) {
            Trouble.NO_INTERNET -> Res.string.trouble_no_internet_hint
            Trouble.SERVER_UNREACHABLE -> Res.string.trouble_server_unreachable_hint
            Trouble.SUBSCRIPTION_UNAVAILABLE -> Res.string.trouble_subscription_hint
            Trouble.CONFIG_REJECTED -> Res.string.trouble_config_hint
            Trouble.PERMISSION_REQUIRED -> Res.string.trouble_permission_hint
            Trouble.UNKNOWN -> Res.string.trouble_unknown_hint
        },
    )
    else -> null
}

/** A server as a person calls it: its name, or "fastest" for the automatic choice. */
@Composable
fun serverName(server: ServerRef): String =
    if (server.auto) stringResource(Res.string.server_auto) else server.displayName

@Composable
fun serverDetail(server: ServerRef): String? {
    val resolved = server.resolvedName
    return when {
        server.auto && resolved != null -> stringResource(Res.string.server_auto_now, resolved)
        server.auto -> stringResource(Res.string.server_auto_detail)
        else -> null
    }
}

@Composable
fun latencyLabel(millis: Int?): String? = millis?.let { stringResource(Res.string.latency_ms, it) }

@Composable
fun noticeText(notice: Notice): String = stringResource(
    when (notice) {
        Notice.VPN_PERMISSION_DENIED -> Res.string.notice_permission_denied
        Notice.SOURCE_ADDED -> Res.string.notice_source_added
        Notice.SOURCE_UPDATED -> Res.string.notice_source_updated
        Notice.SOURCE_REMOVED -> Res.string.notice_source_removed
        Notice.SOURCE_FAILED -> Res.string.notice_source_failed
        Notice.SOURCE_EMPTY -> Res.string.notice_source_empty
        Notice.SERVER_SWITCHED -> Res.string.notice_server_switched
        Notice.SETTINGS_NEED_RECONNECT -> Res.string.notice_settings_need_reconnect
        Notice.BACKUP_EXPORTED -> Res.string.notice_backup_exported
        Notice.BACKUP_IMPORTED -> Res.string.notice_backup_imported
        Notice.OPERATION_FAILED -> Res.string.notice_operation_failed
    },
)

@Composable
fun sourceProblemText(problem: SourceProblem): String = stringResource(
    when (problem) {
        SourceProblem.EXPIRED -> Res.string.source_problem_expired
        SourceProblem.UNREACHABLE -> Res.string.source_problem_unreachable
        SourceProblem.EMPTY -> Res.string.source_problem_empty
        SourceProblem.REJECTED -> Res.string.source_problem_rejected
    },
)

/** Time as a clock, not as words: it has to survive both languages unchanged. */
fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    fun pad(value: Int) = if (value < 10) "0$value" else value.toString()
    return if (hours > 0) "$hours:${pad(minutes)}:${pad(secs)}" else "${pad(minutes)}:${pad(secs)}"
}
