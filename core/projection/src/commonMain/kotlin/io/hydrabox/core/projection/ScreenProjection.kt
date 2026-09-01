package io.hydrabox.core.projection

import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.TransportHealthState
import io.hydrabox.core.model.OperationState

/** Bytes as a person reads them. Formatting belongs here, not in a composable. */
internal fun formatBytes(value: Long, suffix: String): String {
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) {
        amount /= 1024
        unit += 1
    }
    val rendered = if (unit == 0) amount.toLong().toString() else {
        val scaled = (amount * 10).toLong()
        "${scaled / 10}.${scaled % 10}"
    }
    return "$rendered ${units[unit]}$suffix"
}

/**
 * Turns the runtime snapshot and the stored model into product states.
 *
 * This is the only place allowed to know that a runtime phase exists. Everything above it
 * sees [Connection], which answers the three questions the home screen owes the person:
 * am I protected, through what, and what can I press.
 */
object ScreenProjection {
    fun project(snapshot: RuntimeSnapshot): ScreenState = project(AppReadModel(runtime = snapshot))

    fun project(model: AppReadModel): ScreenState {
        val snapshot = model.runtime
        val server = selectedServer(model)
        return ScreenState(
            connection = connection(model, server),
            legalAccepted = model.legalAccepted,
            servers = model.servers.map { group ->
                group.copy(servers = group.servers.map { it.withLatency(snapshot) })
            },
            autoServer = model.autoServer?.withLatency(snapshot)?.copy(resolvedName = resolvedAuto(model)),
            selectedServerId = model.selectedServerId,
            sources = model.sources,
            settings = model.settings,
            diagnostics = model.diagnostics?.copy(
                runtimeState = snapshot.state.name.lowercase(),
                transport = snapshot.transportHealth.let { health ->
                    if (!health.applicable) "not applicable"
                    else "${health.state.name.lowercase()}, ${health.activeLanes} lanes"
                },
                lastErrorCode = snapshot.lastFailure?.code?.code,
            ),
            apps = model.apps.sortedWith(
                compareByDescending<InstalledApp> { it.excluded }.thenBy { it.label.lowercase() },
            ),
            busy = Busy(
                source = model.sourceOperation == OperationState.Running,
                servers = snapshot.state == RuntimeState.RUNNING && snapshot.latencies.isEmpty(),
                backup = model.backupOperation == OperationState.Running,
            ),
            notice = model.notice ?: operationNotice(model),
        )
    }
}

/**
 * The runtime says `RUNNING` as soon as the core accepted the configuration, but traffic
 * only flows once the transport has a lane. Reporting "Connected" before that would lie
 * to the person, so readiness — not the phase name — decides.
 */
private fun connection(model: AppReadModel, server: ServerRef?): Connection {
    val snapshot = model.runtime
    val health = snapshot.transportHealth
    if (model.vpnPermissionMissing && snapshot.state == RuntimeState.STOPPED) {
        return Connection.Stopped(Trouble.PERMISSION_REQUIRED, server, retryable = true)
    }
    return when (snapshot.state) {
        RuntimeState.STOPPED -> when {
            model.sources.isEmpty() -> Connection.NeedsSubscription
            model.servers.none { it.servers.isNotEmpty() } && model.autoServer == null -> Connection.NeedsServers
            else -> Connection.Idle(server)
        }
        RuntimeState.STARTING -> Connection.Connecting(server)
        RuntimeState.RECOVERING -> Connection.Reconnecting(server)
        RuntimeState.STOPPING -> Connection.Disconnecting
        RuntimeState.FAILED -> Connection.Stopped(
            cause = Trouble.of(snapshot.lastFailure),
            server = server,
            retryable = snapshot.lastFailure?.retryable == true,
        )
        RuntimeState.RUNNING -> when {
            !health.isReady && health.state == TransportHealthState.RECOVERING -> Connection.Reconnecting(server)
            !health.isReady -> Connection.Connecting(server)
            else -> Connection.Connected(server, traffic(snapshot), model.connectedForSeconds)
        }
    }
}

private fun traffic(snapshot: RuntimeSnapshot) = snapshot.traffic.let { counters ->
    TrafficSummary(
        available = counters.available,
        uplink = formatBytes(counters.uplink, "/s"),
        downlink = formatBytes(counters.downlink, "/s"),
        uplinkTotal = formatBytes(counters.uplinkTotal, ""),
        downlinkTotal = formatBytes(counters.downlinkTotal, ""),
        connections = counters.connectionsOut,
    )
}

/** What the home screen names as the destination: the picked server, or automatic. */
private fun selectedServer(model: AppReadModel): ServerRef? {
    val auto = model.autoServer?.copy(resolvedName = resolvedAuto(model))
    val chosen = model.selectedServerId ?: return auto?.withLatency(model.runtime)
    if (auto != null && chosen == auto.id) return auto.withLatency(model.runtime)
    return model.servers.asSequence()
        .flatMap { it.servers.asSequence() }
        .firstOrNull { it.id == chosen }
        ?.withLatency(model.runtime)
        ?: auto?.withLatency(model.runtime)
}

/** Which server the automatic choice landed on, straight from the snapshot. */
private fun resolvedAuto(model: AppReadModel): String? {
    val autoId = model.autoServer?.id ?: return null
    return model.runtime.selectedOutbounds.firstOrNull { it.groupId == autoId }?.outboundId
        ?.takeIf { it != autoId }
}

private fun ServerRef.withLatency(snapshot: RuntimeSnapshot): ServerRef {
    val tag = resolvedName ?: id
    val measured = snapshot.latencies.firstOrNull { it.tag == tag }?.delayMillis?.takeIf { it > 0 }
    return if (measured == null) this else copy(latencyMillis = measured)
}

private fun operationNotice(model: AppReadModel): Notice? = when {
    model.sourceOperation is OperationState.Failed -> Notice.SOURCE_FAILED
    model.backupOperation is OperationState.Failed -> Notice.OPERATION_FAILED
    else -> null
}
