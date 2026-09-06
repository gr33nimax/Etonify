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
    fun project(snapshot: RuntimeSnapshot, nowMillis: Long? = null): ScreenState =
        project(AppReadModel(runtime = snapshot), nowMillis)

    fun project(model: AppReadModel, nowMillis: Long? = null): ScreenState {
        val snapshot = model.runtime
        val latencies = snapshot.latencies.associateBy { it.tag }
        val server = selectedServer(model, latencies, nowMillis)
        return ScreenState(
            connection = connection(model, server),
            legalAccepted = model.legalAccepted,
            servers = model.servers.map { group ->
                group.copy(servers = group.servers.map { it.withLatency(latencies, nowMillis) })
            },
            autoServer = model.autoServer
                ?.copy(resolvedName = resolvedAuto(model))
                ?.withLatency(latencies, nowMillis),
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
 * The first instant at which a currently fresh probe becomes stale.
 *
 * The renderer only needs one redraw at this boundary. Later snapshots replace the probe;
 * without one, the stale result remains stable and needs no periodic clock.
 */
fun nextLatencyStaleAtMillis(
    latencies: List<io.hydrabox.core.contract.OutboundLatency>,
    nowMillis: Long,
): Long? = latencies.asSequence().mapNotNull { latency ->
    if (latency.observedAtMillis <= 0 || latency.staleAfterMillis <= 0) return@mapNotNull null
    val boundary = if (latency.observedAtMillis > Long.MAX_VALUE - latency.staleAfterMillis) {
        Long.MAX_VALUE
    } else {
        latency.observedAtMillis + latency.staleAfterMillis
    }
    val staleAt = if (boundary == Long.MAX_VALUE) boundary else boundary + 1
    staleAt.takeIf { it > nowMillis }
}.minOrNull()

/**
 * The outbound the running tunnel actually routes through, as the core itself reported it.
 *
 * Only the core's own announcements answer this: `selectedOutbounds` is what the app asked
 * for, written the moment a switch is requested, while the core may still be routing
 * through the old outbound — and after a plain start it is empty until the first switch.
 * The observed answers carry the real selection, and the chain is followed from there: the
 * selector's choice, and — when that choice is the automatic group — the leaf the group
 * landed on. It is the one tag an exit lookup must be asked about.
 */
fun runningOutboundTag(snapshot: RuntimeSnapshot): String? {
    val actual = snapshot.observedOutbounds
    val chosen = actual.firstOrNull { it.groupId == "select" }?.outboundId ?: return null
    if (chosen != "auto") return chosen
    return actual.firstOrNull { it.groupId == "auto" }?.outboundId
        ?.takeIf { it.isNotEmpty() }
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
private fun selectedServer(
    model: AppReadModel,
    latencies: Map<String, io.hydrabox.core.contract.OutboundLatency>,
    nowMillis: Long?,
): ServerRef? {
    val auto = model.autoServer?.copy(resolvedName = resolvedAuto(model))
    val chosen = model.selectedServerId ?: return auto?.withLatency(latencies, nowMillis)
    if (auto != null && chosen == auto.id) return auto.withLatency(latencies, nowMillis)
    return model.servers.asSequence()
        .flatMap { it.servers.asSequence() }
        .firstOrNull { it.id == chosen }
        ?.withLatency(latencies, nowMillis)
        ?: auto?.withLatency(latencies, nowMillis)
}

/**
 * Which server the automatic choice landed on.
 *
 * The core's own answer, not the request: the group decides inside itself and the request never
 * names a member of it, so reading the stored selection here meant the automatic choice had no
 * resolved server at all and the home screen simply said "fastest" with nothing under it.
 */
private fun resolvedAuto(model: AppReadModel): String? {
    val autoId = model.autoServer?.id ?: return null
    val snapshot = model.runtime
    return (snapshot.observedOutbounds + snapshot.selectedOutbounds)
        .firstOrNull { it.groupId == autoId }?.outboundId
        ?.takeIf { it != autoId }
}

/**
 * The delay the core measured, and whether it got an answer at all.
 *
 * The core reports both a figure and a verdict; only the figure was read. A probe that timed
 * out arrives as `unavailable` with a delay of zero, which looked exactly like a server nobody
 * had measured yet — so a dead server and a fresh one were drawn the same way.
 */
private fun ServerRef.withLatency(
    latencies: Map<String, io.hydrabox.core.contract.OutboundLatency>,
    nowMillis: Long?,
): ServerRef {
    val tag = resolvedName ?: id
    val measured = latencies[tag] ?: return this
    val ageMillis = nowMillis
        ?.takeIf { measured.observedAtMillis > 0 }
        ?.minus(measured.observedAtMillis)
        ?.coerceAtLeast(0)
    val ageSeconds = ageMillis?.div(1_000) ?: measured.ageSeconds.takeIf { measured.observedAtMillis > 0 }
    val stale = if (ageMillis != null && measured.staleAfterMillis > 0) {
        ageMillis > measured.staleAfterMillis
    } else {
        measured.stale
    }
    // A positive delay is the evidence that a probe came back; the verdict is only needed for
    // the other case, and it is trusted when it says the server did not answer.
    val answered = measured.delayMillis > 0 && measured.status != PROBE_UNAVAILABLE
    return if (answered) {
        copy(
            latencyMillis = measured.delayMillis,
            probe = ProbeState.ANSWERING,
            latencyAgeSeconds = ageSeconds,
            latencyStale = stale,
            latencyIsEdgeRtt = measured.status == PROBE_EDGE,
        )
    } else {
        copy(latencyMillis = null, probe = ProbeState.SILENT, latencyAgeSeconds = null, latencyStale = stale)
    }
}

/** The core's own word for a probe that did not come back. */
private const val PROBE_UNAVAILABLE = "unavailable"

/** The workerless question: one STUN Binding to the transport's TURN edge, nothing behind it. */
private const val PROBE_EDGE = "edge"

private fun operationNotice(model: AppReadModel): Notice? = when {
    model.sourceOperation is OperationState.Failed -> Notice.SOURCE_FAILED
    model.backupOperation is OperationState.Failed -> Notice.OPERATION_FAILED
    else -> null
}
