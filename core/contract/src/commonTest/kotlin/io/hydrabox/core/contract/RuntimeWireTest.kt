package io.hydrabox.core.contract

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeWireTest {
    @Test
    fun commandsRoundTripThroughTheSharedWireSchema() {
        val commands = listOf(
            RuntimeCommand.Start(RuntimeMode.VPN), RuntimeCommand.Stop, RuntimeCommand.Reload,
            RuntimeCommand.SelectOutbound("main", "direct"), RuntimeCommand.NetworkChanged(NetworkGeneration(7)),
        )
        commands.forEach { assertEquals(it, RuntimeWire.decodeCommand(RuntimeWire.encode(it))) }
    }

    @Test
    fun typedSnapshotRoundTripsThroughTheSharedWireSchema() {
        val failure = RuntimeFailure(FailureDomain.NETWORK, HydraCoreErrorCode.NETWORK_LOST, retryable = true)
        val snapshot = RuntimeSnapshot(
            ProcessEpoch("epoch-1"), CommandGeneration(2), RuntimeGeneration(3), NetworkGeneration(4), EventSequence(5),
            RuntimeState.RUNNING, RuntimeMode.VPN, listOf(OutboundSelection("main", "proxy")),
            // What was asked for and what the core answered are separate fields, because they
            // disagree: an automatic group's actual server is only ever in the second one.
            listOf(OutboundSelection("select", "auto"), OutboundSelection("auto", "tokyo")),
            TransportHealth(
                transportTag = "call-vk-out",
                state = TransportHealthState.HEALTHY,
                activeLanes = 12,
                totalLanes = 16,
                applicable = true,
                runtimeGeneration = RuntimeGeneration(3),
                networkGeneration = NetworkGeneration(4),
                failure = failure,
                retryAfterMillis = 120_000,
                quicRttMillis = 47,
            ),
            failure,
            latencies = listOf(
                OutboundLatency(
                    tag = "proxy",
                    delayMillis = 42,
                    status = "ok",
                    observedAtMillis = 1_700_000_000_000,
                    staleAfterMillis = 120_000,
                    ageSeconds = 90,
                    stale = true,
                ),
            ),
            edgeLatencies = listOf(
                OutboundLatency(
                    tag = "vk-call",
                    delayMillis = 0,
                    status = "edge",
                    observedAtMillis = 1_700_000_000_500,
                    staleAfterMillis = 120_000,
                ),
            ),
            connectedAtElapsedRealtimeMillis = 123_456,
        )
        assertEquals(snapshot, RuntimeWire.decodeSnapshot(RuntimeWire.encode(snapshot)))
    }
}
