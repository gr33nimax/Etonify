package io.hydrabox.core.runtime

import io.hydrabox.core.contract.NetworkGeneration
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a network change is allowed to do, per state. The table is HydraBox 1.x's
 * (`CoreRuntimeService.reduce` for `NetworkChanged`, plus `applyNetworkChangeAction`), and it
 * is a table rather than a single rule for a reason: a running tunnel has lanes to move, a
 * starting one only needs to know where to dial, and a stopping one has nothing to move at all.
 *
 * The order this produces on the platform side matters more than the states. A lane replaced
 * before the core has been told the new generation cannot be superseded in sequence, so the
 * transport tears every lane down and rebuilds it — which, for a VK parasite session, means
 * rejoining the conversation and eventually being refused.
 */
class NetworkChangeTest {
    private fun running(): RuntimeModel {
        val started = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        val launched = reduce(started, RuntimeInput.Launched(started.commandGeneration, started.commandGeneration)).state
        return reduce(
            launched,
            RuntimeInput.Health(
                commandGeneration = launched.commandGeneration,
                runtimeGeneration = launched.runtimeGeneration,
                health = TransportHealth(TransportHealthState.HEALTHY, activeLanes = 1),
            ),
        ).state.also { assertEquals(RuntimeState.RUNNING, it.state) }
    }

    @Test fun `a running tunnel is rebound, and the generation travels with it`() {
        val decision = reduce(running(), RuntimeInput.NetworkChanged(NetworkGeneration(4)))
        val effect = assertIs<Effect.RebindNetwork>(decision.effects.single())
        assertEquals(4, effect.generation.value)
        assertEquals(4, decision.state.networkGeneration.value)
    }

    @Test fun `a starting tunnel is only told where to dial`() {
        val starting = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN)).state
        assertEquals(RuntimeState.STARTING, starting.state)
        val decision = reduce(starting, RuntimeInput.NetworkChanged(NetworkGeneration(1)))
        assertIs<Effect.PublishNetwork>(decision.effects.single())
    }

    @Test fun `a stopping or stopped tunnel refuses the change entirely`() {
        val stopping = reduce(running(), RuntimeInput.Stop).state
        assertEquals(RuntimeState.STOPPING, stopping.state)
        assertTrue(reduce(stopping, RuntimeInput.NetworkChanged(NetworkGeneration(9))).effects.isEmpty())
        assertTrue(reduce(RuntimeModel(), RuntimeInput.NetworkChanged(NetworkGeneration(9))).effects.isEmpty())
        // A refused change is not remembered either, so a later one still counts as newer.
        assertEquals(0, reduce(stopping, RuntimeInput.NetworkChanged(NetworkGeneration(9))).state.networkGeneration.value)
    }

    @Test fun `a generation that is not newer is dropped rather than replayed`() {
        val moved = reduce(running(), RuntimeInput.NetworkChanged(NetworkGeneration(3))).state
        // Android reports the same handover several times — capabilities and link properties
        // both fire. Each replay reaching the core is one more teardown of a healthy lane.
        assertTrue(reduce(moved, RuntimeInput.NetworkChanged(NetworkGeneration(3))).effects.isEmpty())
        assertTrue(reduce(moved, RuntimeInput.NetworkChanged(NetworkGeneration(2))).effects.isEmpty())
        assertIs<Effect.RebindNetwork>(
            reduce(moved, RuntimeInput.NetworkChanged(NetworkGeneration(4))).effects.single(),
        )
    }
}
