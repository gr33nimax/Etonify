package io.hydrabox.core.runtime

import io.hydrabox.core.contract.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditLifecycleTest {
    @Test fun `new session clears previous observations and counters`() {
        val previous = RuntimeModel(
            latencies = listOf(OutboundLatency("old", 12, "ok")),
            observedOutbounds = listOf(OutboundSelection("auto", "old")),
            traffic = TrafficCounters(available = true, downlink = 42),
        )
        val started = reduce(previous, RuntimeInput.Start(RuntimeMode.VPN)).state
        assertTrue(started.latencies.isEmpty())
        assertTrue(started.observedOutbounds.isEmpty())
        assertEquals(TrafficCounters(), started.traffic)
    }

    @Test fun `offline probe cannot overwrite running session results`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 3)
        assertEquals(running, reduce(running, RuntimeInput.Latencies(listOf(OutboundLatency("old", 1, "ok")))).state)
    }

    @Test fun `late health cannot mutate a released session`() {
        val stopped = RuntimeModel(commandGeneration = 3)
        assertEquals(stopped, reduce(stopped, RuntimeInput.Health(3, 0, TransportHealth(activeLanes = 1))).state)
    }

    @Test fun `launch failure retains original reason immediately`() {
        val starting = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        val failure = RuntimeFailure(FailureDomain.INTERNAL, HydraCoreErrorCode.CONFIG_INVALID_PLAN, false)
        val failed = reduce(starting, RuntimeInput.Released(1, false, failure)).state
        assertEquals(RuntimeState.FAILED, failed.state)
        assertEquals(failure, failed.failure)
    }

    @Test fun `close timeout names unconfirmed shutdown`() {
        val stopping = reduce(RuntimeModel(state = RuntimeState.RUNNING), RuntimeInput.Stop).state
        assertEquals(HydraCoreErrorCode.RUNTIME_STOP_UNCONFIRMED,
            reduce(stopping, RuntimeInput.Deadline(stopping.commandGeneration)).state.failure?.code)
    }
}
