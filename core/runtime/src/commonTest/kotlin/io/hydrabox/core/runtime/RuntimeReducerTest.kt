package io.hydrabox.core.runtime

import io.hydrabox.core.contract.EdgeLatencyStatus
import io.hydrabox.core.contract.HydraCoreErrorCode
import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.OutboundLatency
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeReducerTest {
    @Test fun `R1 reducer is deterministic`() {
        val input = RuntimeInput.Start(RuntimeMode.VPN)
        assertEquals(reduce(RuntimeModel(), input), reduce(RuntimeModel(), input))
    }

    @Test fun `start only declares effect and deadline`() {
        val decision = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN))
        assertEquals(RuntimeState.STARTING, decision.state.state)
        assertEquals(listOf(Effect.StartCore(RuntimeMode.VPN, 1)), decision.effects)
        assertEquals(listOf(TimerOp.Arm(1, RuntimeDeadline.START)), decision.timers)
    }

    @Test fun `ready health transitions to running`() {
        val starting = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        val launched = reduce(starting, RuntimeInput.Launched(1, 2)).state
        val health = TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1, runtimeGeneration = io.hydrabox.core.contract.RuntimeGeneration(2))
        assertEquals(RuntimeState.RUNNING, reduce(launched, RuntimeInput.Health(1, 2, health)).state.state)
    }

    @Test fun `connection time survives interface process recreation`() {
        val starting = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        val launched = reduce(starting, RuntimeInput.Launched(1, 2)).state
        val health = TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1)

        val running = reduce(
            launched,
            RuntimeInput.Health(1, 2, health, observedAtElapsedRealtimeMillis = 123_456),
        ).state

        assertEquals(123_456, running.connectedAtElapsedRealtimeMillis)
        assertEquals(
            123_456,
            reduce(running, RuntimeInput.Traffic(io.hydrabox.core.contract.TrafficCounters(available = true))).state
                .connectedAtElapsedRealtimeMillis,
        )
    }

    @Test fun `network change rebinds exactly once`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, networkGeneration = NetworkGeneration(2))
        val decision = reduce(running, RuntimeInput.NetworkChanged(NetworkGeneration(3)))
        assertEquals(listOf(Effect.RebindNetwork(NetworkGeneration(3))), decision.effects)
    }

    @Test fun `different running mode closes before deferred start`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 2, mode = RuntimeMode.VPN)
        val stopping = reduce(running, RuntimeInput.Start(RuntimeMode.PROXY))
        assertEquals(RuntimeState.STOPPING, stopping.state.state)
        assertEquals(RuntimeMode.PROXY, stopping.state.deferredStart)
        val restarted = reduce(stopping.state, RuntimeInput.Released(3, success = true))
        assertEquals(RuntimeState.STARTING, restarted.state.state)
        assertEquals(listOf(Effect.StartCore(RuntimeMode.PROXY, 4)), restarted.effects)
    }

    @Test fun `deadline transitions through close to failure`() {
        val starting = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        val stopping = reduce(starting, RuntimeInput.Deadline(1))
        assertEquals(RuntimeState.STOPPING, stopping.state.state)
        assertEquals(RuntimeState.FAILED, reduce(stopping.state, RuntimeInput.Released(2, success = true)).state.state)
    }

    @Test fun `stale events do not mutate state`() {
        val state = RuntimeModel(state = RuntimeState.STARTING, commandGeneration = 3, runtimeGeneration = 4)
        assertEquals(state, reduce(state, RuntimeInput.Launched(2, 5)).state)
        assertEquals(state, reduce(state, RuntimeInput.Deadline(2)).state)
    }

    @Test fun `challenge and recovery use declarative timers`() {
        val starting = RuntimeModel(state = RuntimeState.STARTING, commandGeneration = 1, runtimeGeneration = 2)
        val waiting = reduce(starting, RuntimeInput.Health(1, 2, TransportHealth(), challenge = true))
        assertEquals(listOf(TimerOp.Arm(1, RuntimeDeadline.CHALLENGE)), waiting.timers)
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 1, runtimeGeneration = 2)
        assertEquals(listOf(TimerOp.Arm(1, RuntimeDeadline.RECOVERY)), reduce(running, RuntimeInput.Health(1, 2, TransportHealth(), shouldRecover = true)).timers)
    }

    @Test fun `device idle exit has the only bounded automatic restart`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 1, mode = RuntimeMode.VPN, wantRunning = true)
        assertEquals(RuntimeState.RECOVERING, reduce(running, RuntimeInput.DeviceIdleExit).state.state)
        val exhausted = running.copy(recoveryAttempts = 2)
        assertEquals(exhausted, reduce(exhausted, RuntimeInput.DeviceIdleExit).state)
    }

    @Test fun `stop and deadline clear automatic recovery intent`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, wantRunning = true)
        assertEquals(false, reduce(running, RuntimeInput.Stop).state.wantRunning)
        val starting = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        assertEquals(false, reduce(starting, RuntimeInput.Deadline(1)).state.wantRunning)
    }

    @Test fun `all core deadlines retain donor values`() {
        assertEquals(45_000, RuntimeDeadline.START.milliseconds)
        assertEquals(120_000, RuntimeDeadline.CHALLENGE.milliseconds)
        assertEquals(60_000, RuntimeDeadline.RECOVERY.milliseconds)
        assertEquals(5_000, RuntimeDeadline.CLOSE.milliseconds)
    }

    @Test fun `reload is refused by name, with no effect`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 2, mode = RuntimeMode.VPN)
        val decision = reduce(running, RuntimeInput.Reload)
        assertEquals(RuntimeState.RUNNING, decision.state.state)
        assertEquals(emptyList<Effect>(), decision.effects)
        assertEquals(emptyList<TimerOp>(), decision.timers)
        assertEquals(HydraCoreErrorCode.RUNTIME_RELOAD_UNSUPPORTED, decision.state.failure?.code)
    }

    // The offline sweep once sent its edge answers inside the group's list, and they were
    // filed as HTTP figures — an edge round trip lost its label and a sub-millisecond one
    // read as silence. What a value says about itself decides its list now, so a caller
    // mixing the kinds cannot corrupt either.
    @Test fun `edge answers are routed by their own words, not by the list they arrive in`() {
        val state = reduce(
            RuntimeModel(),
            RuntimeInput.Latencies(
                listOf(
                    OutboundLatency("vless", 40, "available"),
                    OutboundLatency("vk", 20, EdgeLatencyStatus.ANSWERED),
                    OutboundLatency("vk-two", 0, EdgeLatencyStatus.NO_EDGE),
                ),
                generation = 0,
            ),
        ).state
        assertEquals(listOf("vless"), state.latencies.map { it.tag })
        assertEquals(setOf("vk", "vk-two"), state.edgeLatencies.map { it.tag }.toSet())
    }
}
