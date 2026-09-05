package io.hydrabox.core.projection

import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.TransportHealthState
import io.hydrabox.core.model.OperationState

/**
 * Bytes as a person reads them, for anything outside a screen: a quota in a source row, a
 * size in the journal. One implementation, because the platform grew a second one and it
 * printed `${'$'}{value} B` at people.
 */
fun readableBytes(value: Long): String = formatBytes(value, "")

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
            // The failure code is the one runtime fact a support conversation needs; the
            // phase, the transport and the lane count are not shown anywhere any more.
            diagnostics = model.diagnostics?.copy(
                lastError = snapshot.lastFailure?.let { "${it.domain.name.lowercase()} / ${it.code.code}" },
            ),
            ruleSets = model.ruleSets,
            exit = model.exit,
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
    val displayedServer = server?.let {
        health.quicRttMillis.takeIf { value -> health.applicable && value > 0 }?.let { value ->
            it.copy(quicRttMillis = value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        } ?: it
    }
    if (model.vpnPermissionMissing && snapshot.state == RuntimeState.STOPPED) {
        return Connection.Stopped(Trouble.PERMISSION_REQUIRED, displayedServer, retryable = true)
    }
    return when (snapshot.state) {
        RuntimeState.STOPPED -> when {
            model.sources.isEmpty() -> Connection.NeedsSubscription
            model.servers.none { it.servers.isNotEmpty() } && model.autoServer == null -> Connection.NeedsServers
            else -> Connection.Idle(displayedServer)
        }
        RuntimeState.STARTING -> Connection.Connecting(displayedServer)
        RuntimeState.RECOVERING -> Connection.Reconnecting(displayedServer)
        RuntimeState.STOPPING -> Connection.Disconnecting
        RuntimeState.FAILED -> Connection.Stopped(
            cause = Trouble.of(snapshot.lastFailure),
            server = displayedServer,
            retryable = snapshot.lastFailure?.retryable == true,
        )
        RuntimeState.RUNNING -> when {
            !health.isReady && health.state == TransportHealthState.RECOVERING -> Connection.Reconnecting(displayedServer)
            !health.isReady -> Connection.Connecting(displayedServer)
            else -> Connection.Connected(displayedServer, traffic(snapshot), model.connectedForSeconds)
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
        uplinkRate = counters.uplink,
        downlinkRate = counters.downlink,
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

/**
 * The delay the core measured, and whether it got an answer at all.
 *
 * The core reports both a figure and a verdict; only the figure was read. A probe that timed
 * out arrives as `unavailable` with a delay of zero, which looked exactly like a server nobody
 * had measured yet — so a dead server and a fresh one were drawn the same way.
 */
private fun ServerRef.withLatency(snapshot: RuntimeSnapshot): ServerRef {
    val tag = resolvedName ?: id
    val measured = snapshot.latencies.firstOrNull { it.tag == tag } ?: return this
    // A positive delay is the evidence that a probe came back; the verdict is only needed for
    // the other case, and it is trusted when it says the server did not answer.
    val answered = measured.delayMillis > 0 && measured.status != PROBE_UNAVAILABLE
    return if (answered) {
        copy(
            latencyMillis = measured.delayMillis,
            probe = ProbeState.ANSWERING,
            latencyAgeSeconds = measured.ageSeconds.takeIf { measured.observedAtMillis > 0 },
            latencyStale = measured.stale,
        )
    } else {
        copy(latencyMillis = null, probe = ProbeState.SILENT, latencyAgeSeconds = null, latencyStale = measured.stale)
    }
}

/** The core's own word for a probe that did not come back. */
private const val PROBE_UNAVAILABLE = "unavailable"

private fun operationNotice(model: AppReadModel): Notice? = when {
    model.sourceOperation is OperationState.Failed -> Notice.SOURCE_FAILED
    model.backupOperation is OperationState.Failed -> Notice.OPERATION_FAILED
    else -> null
}
