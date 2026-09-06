package io.hydrabox.core.projection

import io.hydrabox.core.contract.CommandGeneration
import io.hydrabox.core.contract.EventSequence
import io.hydrabox.core.contract.FailureDomain
import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.OutboundLatency
import io.hydrabox.core.contract.OutboundSelection
import io.hydrabox.core.contract.ProcessEpoch
import io.hydrabox.core.contract.RuntimeFailure
import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeSnapshot
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenProjectionTest {
    private fun snapshot(
        state: RuntimeState,
        failure: RuntimeFailure? = null,
        health: TransportHealth = TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1),
        selections: List<OutboundSelection> = emptyList(),
        latencies: List<OutboundLatency> = emptyList(),
    ) = RuntimeSnapshot(
        processEpoch = ProcessEpoch("p"),
        commandGeneration = CommandGeneration(1),
        runtimeGeneration = RuntimeGeneration(1),
        networkGeneration = NetworkGeneration(1),
        lastEventSequence = EventSequence(1),
        state = state,
        mode = RuntimeMode.VPN,
        selectedOutbounds = selections,
        transportHealth = health,
        lastFailure = failure,
        latencies = latencies,
    )

    private val auto = ServerRef(id = "auto", displayName = "Auto", auto = true)
    private val group = ServerGroup("s1", "Source", listOf(ServerRef("tokyo", "Tokyo", sourceId = "s1")))

    private fun model(
        state: RuntimeState,
        failure: RuntimeFailure? = null,
        health: TransportHealth = TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1),
        sources: List<SubscriptionSummary> = listOf(SubscriptionSummary("s1", "Source", 1, 0)),
        servers: List<ServerGroup> = listOf(group),
        selections: List<OutboundSelection> = emptyList(),
        latencies: List<OutboundLatency> = emptyList(),
        selected: String? = null,
        permissionMissing: Boolean = false,
    ) = AppReadModel(
        runtime = snapshot(state, failure, health, selections, latencies),
        sources = sources,
        servers = servers,
        autoServer = auto,
        selectedServerId = selected,
        vpnPermissionMissing = permissionMissing,
    )

    @Test
    fun `an empty install asks for a subscription and nothing else`() {
        val state = ScreenProjection.project(
            model(RuntimeState.STOPPED, sources = emptyList(), servers = emptyList()).copy(autoServer = null),
        )
        assertEquals(Connection.NeedsSubscription, state.connection)
        assertEquals(PrimaryAction.ADD_SUBSCRIPTION, state.connection.primaryAction)
    }

    @Test
    fun `a source without servers is its own state, not an empty list`() {
        val state = ScreenProjection.project(
            model(RuntimeState.STOPPED, servers = emptyList()).copy(autoServer = null),
        )
        assertEquals(Connection.NeedsServers, state.connection)
        assertEquals(PrimaryAction.REFRESH_SOURCE, state.connection.primaryAction)
    }

    @Test
    fun `running without a transport lane is still connecting`() {
        val state = ScreenProjection.project(
            model(RuntimeState.RUNNING, health = TransportHealth(TransportHealthState.STARTING, activeLanes = 0)),
        )
        assertTrue(state.connection is Connection.Connecting)
        assertEquals(PrimaryAction.CANCEL, state.connection.primaryAction)
    }

    @Test
    fun `a lane loss under a running tunnel reads as reconnecting, not as an error`() {
        val state = ScreenProjection.project(
            model(RuntimeState.RUNNING, health = TransportHealth(TransportHealthState.RECOVERING, activeLanes = 0)),
        )
        assertTrue(state.connection is Connection.Reconnecting)
    }

    @Test
    fun `recovery after a network change is not the state the person asked for`() {
        assertTrue(ScreenProjection.project(model(RuntimeState.RECOVERING)).connection is Connection.Reconnecting)
        assertTrue(ScreenProjection.project(model(RuntimeState.STARTING)).connection is Connection.Connecting)
    }

    @Test
    fun `every runtime failure becomes one of six situations with one action`() {
        val cases = mapOf(
            HydraCoreErrorCode.NETWORK_LOST to Trouble.NO_INTERNET,
            HydraCoreErrorCode.QUIC_NO_PATHS to Trouble.SERVER_UNREACHABLE,
            HydraCoreErrorCode.VK_CREDENTIALS_REJECTED to Trouble.SUBSCRIPTION_UNAVAILABLE,
            HydraCoreErrorCode.CONFIG_QUARANTINED to Trouble.CONFIG_REJECTED,
            HydraCoreErrorCode.RUNTIME_SUPERSEDED to Trouble.UNKNOWN,
        )
        cases.forEach { (code, expected) ->
            val state = ScreenProjection.project(
                model(RuntimeState.FAILED, RuntimeFailure(FailureDomain.NETWORK, code, retryable = true)),
            )
            val stopped = state.connection as Connection.Stopped
            assertEquals(expected, stopped.cause, "code $code")
        }
        val unreachable = ScreenProjection.project(
            model(
                RuntimeState.FAILED,
                RuntimeFailure(FailureDomain.QUIC, HydraCoreErrorCode.QUIC_DIAL_FAILED, retryable = true),
            ),
        )
        assertEquals(PrimaryAction.CHOOSE_SERVER, unreachable.connection.primaryAction)
    }

    @Test
    fun `a refused VPN consent is a product state, not a banner`() {
        val state = ScreenProjection.project(model(RuntimeState.STOPPED, permissionMissing = true))
        assertEquals(Trouble.PERMISSION_REQUIRED, (state.connection as Connection.Stopped).cause)
    }

    @Test
    fun `automatic selection names the server it landed on`() {
        val state = ScreenProjection.project(
            model(
                RuntimeState.RUNNING,
                selections = listOf(OutboundSelection("auto", "tokyo")),
                latencies = listOf(OutboundLatency("tokyo", 42, "ok")),
                selected = "auto",
            ),
        )
        val connected = state.connection as Connection.Connected
        assertEquals("tokyo", connected.server?.resolvedName)
        assertEquals(42, connected.server?.latencyMillis)
        assertEquals(ProbeState.ANSWERING, connected.server?.probe)
    }

    @Test
    fun `a server that was asked and stayed silent is not a server without a figure`() {
        val state = ScreenProjection.project(
            model(
                RuntimeState.RUNNING,
                selections = listOf(OutboundSelection("auto", "tokyo")),
                latencies = listOf(OutboundLatency("tokyo", 0, "unavailable")),
                selected = "auto",
            ),
        )
        val connected = state.connection as Connection.Connected
        assertNull(connected.server?.latencyMillis)
        assertEquals(ProbeState.SILENT, connected.server?.probe)
    }

    @Test
    fun `an old probe retains its age and stale verdict`() {
        val state = ScreenProjection.project(
            model(
                RuntimeState.RUNNING,
                latencies = listOf(
                    OutboundLatency(
                        tag = "tokyo",
                        delayMillis = 42,
                        status = "ok",
                        observedAtMillis = 1_700_000_000_000,
                        ageSeconds = 1_801,
                        stale = true,
                    ),
                ),
                selected = "tokyo",
            ),
        )
        val connected = state.connection as Connection.Connected
        assertEquals(1_801, connected.server?.latencyAgeSeconds)
        assertTrue(connected.server?.latencyStale == true)
    }

    @Test
    fun `probe age and stale state advance when projected`() {
        val state = ScreenProjection.project(
            model(
                RuntimeState.RUNNING,
                latencies = listOf(
                    OutboundLatency(
                        tag = "tokyo",
                        delayMillis = 42,
                        status = "ok",
                        observedAtMillis = 1_000,
                        staleAfterMillis = 5_000,
                    ),
                ),
                selected = "tokyo",
            ),
            nowMillis = 7_500,
        )
        val connected = state.connection as Connection.Connected
        assertEquals(6, connected.server?.latencyAgeSeconds)
        assertTrue(connected.server?.latencyStale == true)
    }

    @Test
    fun `the stale boundary is the first instant the projection would call a probe old`() {
        val tokyo = OutboundLatency("tokyo", 42, "ok", observedAtMillis = 1_000, staleAfterMillis = 5_000)
        val oslo = OutboundLatency("oslo", 42, "ok", observedAtMillis = 2_000, staleAfterMillis = 8_000)

        // The nearest boundary belongs to tokyo, and it is one millisecond past its own age
        // limit — not past the limit counted from zero.
        val boundary = nextLatencyStaleAtMillis(listOf(tokyo, oslo), nowMillis = 4_000)
        assertEquals(6_001, boundary)

        // The number is only worth anything if it is the instant the projection changes its
        // mind, so ask the projection itself on both sides of it.
        fun staleAt(nowMillis: Long) = ScreenProjection
            .project(model(RuntimeState.RUNNING, latencies = listOf(tokyo)), nowMillis = nowMillis)
            .servers.first().servers.first().latencyStale
        assertEquals(false, staleAt(boundary!! - 1))
        assertEquals(true, staleAt(boundary))

        // Once every probe is already old there is nothing left to redraw for.
        assertNull(nextLatencyStaleAtMillis(listOf(tokyo), nowMillis = boundary))
    }

    @Test
    fun `stale boundaries arrive one after another until none are left`() {
        val tokyo = OutboundLatency("tokyo", 42, "ok", observedAtMillis = 1_000, staleAfterMillis = 5_000)
        val oslo = OutboundLatency("oslo", 42, "ok", observedAtMillis = 2_000, staleAfterMillis = 8_000)

        // The wake-up loop: after each boundary redraws the screen, the next call must name
        // the next boundary, not the one that has already passed.
        var now = 4_000L
        assertEquals(6_001, nextLatencyStaleAtMillis(listOf(tokyo, oslo), now))
        now = 6_001
        assertEquals(10_001, nextLatencyStaleAtMillis(listOf(tokyo, oslo), now))
        // With nothing fresh left, the loop stops instead of waking every second forever.
        assertNull(nextLatencyStaleAtMillis(listOf(tokyo, oslo), 10_001))
    }

    @Test
    fun `an edge figure is an answer, and says what it measures`() {
        val edge = OutboundLatency("tokyo", 120, "edge", observedAtMillis = 1_000, staleAfterMillis = 5_000)
        val state = ScreenProjection.project(model(RuntimeState.RUNNING, latencies = listOf(edge)))

        // It answers — a figure, not silence — and it is labelled as the edge round trip,
        // never drawn like a measurement of the tunnel itself.
        val server = state.servers.first().servers.first()
        assertEquals(120, server.latencyMillis)
        assertTrue(server.latencyIsEdgeRtt, "the edge figure must be distinguishable from a tunnel probe")

        // An ordinary probe never claims the edge label.
        val ordinary = ScreenProjection.project(
            model(RuntimeState.RUNNING, latencies = listOf(OutboundLatency("tokyo", 80, "ok", 1_000, 5_000))),
        )
        assertEquals(false, ordinary.servers.first().servers.first().latencyIsEdgeRtt)
    }

    @Test
    fun `the running outbound is what the core observed, not what the app asked for`() {
        fun snapshot(selections: List<OutboundSelection> = emptyList(), observed: List<OutboundSelection> = emptyList()) =
            RuntimeSnapshot(
                processEpoch = ProcessEpoch("p"),
                commandGeneration = CommandGeneration(1),
                runtimeGeneration = RuntimeGeneration(1),
                networkGeneration = NetworkGeneration(1),
                lastEventSequence = EventSequence(1),
                state = RuntimeState.RUNNING,
                mode = RuntimeMode.VPN,
                selectedOutbounds = selections,
                observedOutbounds = observed,
            )

        // After a plain start nothing was asked for; the core's own answer is all there is.
        assertNull(runningOutboundTag(snapshot()))
        assertEquals(
            "tokyo",
            runningOutboundTag(snapshot(observed = listOf(OutboundSelection("select", "tokyo")))),
        )

        // The automatic choice is the group, not the route: its leaf is what carries traffic.
        assertEquals(
            "oslo",
            runningOutboundTag(
                snapshot(observed = listOf(OutboundSelection("select", "auto"), OutboundSelection("auto", "oslo"))),
            ),
        )
        assertNull(
            runningOutboundTag(snapshot(observed = listOf(OutboundSelection("select", "auto")))),
            "an automatic choice with no leaf announced is not a route to ask about",
        )

        // A switch is recorded the moment it is asked for; the core may still be routing
        // through the old outbound, so the request must not win over the observation.
        assertEquals(
            "tokyo",
            runningOutboundTag(
                snapshot(
                    selections = listOf(OutboundSelection("select", "oslo")),
                    observed = listOf(OutboundSelection("select", "tokyo")),
                ),
            ),
            "the requested switch answered instead of the core's actual route",
        )
    }

    @Test
    fun `active QUIC health replaces the URL test figure with actual RTT`() {
        val state = ScreenProjection.project(
            model(
                RuntimeState.RUNNING,
                health = TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1, quicRttMillis = 47),
                latencies = listOf(OutboundLatency("tokyo", 42, "ok")),
                selected = "tokyo",
            ),
        )
        assertEquals(47, (state.connection as Connection.Connected).server?.quicRttMillis)
    }

    @Test
    fun `an unmeasured server carries no verdict at all`() {
        val state = ScreenProjection.project(
            model(
                RuntimeState.RUNNING,
                selections = listOf(OutboundSelection("auto", "tokyo")),
                selected = "auto",
            ),
        )
        val connected = state.connection as Connection.Connected
        assertNull(connected.server?.latencyMillis)
        assertEquals(ProbeState.UNKNOWN, connected.server?.probe)
    }

    @Test
    fun `the last failure reaches diagnostics and the runtime phase reaches nothing`() {
        val state = ScreenProjection.project(
            model(RuntimeState.FAILED, RuntimeFailure(FailureDomain.DNS, HydraCoreErrorCode.DNS_NO_ANSWER, true)),
        )
        assertNull(state.diagnostics)
        val withDiagnostics = ScreenProjection.project(
            model(RuntimeState.FAILED, RuntimeFailure(FailureDomain.DNS, HydraCoreErrorCode.DNS_NO_ANSWER, true))
                .copy(diagnostics = DiagnosticsSummary(level = "warn")),
        )
        assertEquals("dns / dns.no_answer", withDiagnostics.diagnostics?.lastError)
    }
}
