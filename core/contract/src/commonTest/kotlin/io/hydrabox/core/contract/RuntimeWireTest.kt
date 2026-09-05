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
            latencies = listOf(OutboundLatency("proxy", 42, "ok", 1_700_000_000_000, 90, stale = true)),
            connectedAtElapsedRealtimeMillis = 123_456,
        )
        assertEquals(snapshot, RuntimeWire.decodeSnapshot(RuntimeWire.encode(snapshot)))
    }
}
