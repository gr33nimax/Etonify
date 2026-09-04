package io.hydrabox.core.runtime

import io.hydrabox.core.contract.RuntimeGeneration
import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import io.hydrabox.core.contract.TransportHealth
import io.hydrabox.core.contract.TransportHealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A dial refused at zero active lanes fails immediately. The core reports it within a couple of
 * seconds; the runtime used to keep the start alive for the remaining forty-three, which reads to
 * the person as a dead network rather than as a refusal worth retrying.
 */
class StartFailureTest {
    private fun starting(): RuntimeModel {
        val decision = reduce(RuntimeModel(), RuntimeInput.Start(RuntimeMode.VPN))
        assertEquals(RuntimeState.STARTING, decision.state.state)
        return reduce(decision.state, RuntimeInput.Launched(1, 1)).state
    }

    private fun health(state: TransportHealthState, lanes: Int) = TransportHealth(
        state = state,
        activeLanes = lanes,
        totalLanes = 8,
        applicable = true,
        runtimeGeneration = RuntimeGeneration(1),
    )

    @Test fun `a failed transport ends the start instead of waiting for the deadline`() {
        val decision = reduce(
            starting(),
            RuntimeInput.Health(1, 1, health(TransportHealthState.FAILED, 0), shouldRecover = true),
        )
        assertEquals(RuntimeState.STOPPING, decision.state.state)
        assertEquals(listOf(Effect.StopCore(2)), decision.effects)
        assertTrue(decision.state.failAfterRelease)
        assertFalse(decision.state.wantRunning)
        // And the release turns it into a failure the person can retry, not a silent stop.
        assertEquals(RuntimeState.FAILED, reduce(decision.state, RuntimeInput.Released(2, true)).state.state)
    }

    @Test fun `lanes still coming up are not a failure`() {
        val decision = reduce(
            starting(),
            RuntimeInput.Health(1, 1, health(TransportHealthState.STARTING, 0)),
        )
        assertEquals(RuntimeState.STARTING, decision.state.state)
        assertEquals(0, decision.state.health.activeLanes)
        assertTrue(decision.effects.isEmpty())
    }

    @Test fun `one live lane out of eight is already a working tunnel`() {
        val decision = reduce(
            starting(),
            RuntimeInput.Health(1, 1, health(TransportHealthState.DEGRADED, 1)),
        )
        assertEquals(RuntimeState.RUNNING, decision.state.state)
        assertEquals(listOf(TimerOp.Cancel(1)), decision.timers)
    }

    @Test fun `a captcha is still given its own deadline rather than failed`() {
        val decision = reduce(
            starting(),
            RuntimeInput.Health(1, 1, health(TransportHealthState.WAITING_USER, 0), challenge = true),
        )
        assertEquals(RuntimeState.STARTING, decision.state.state)
        assertEquals(listOf(TimerOp.Arm(1, RuntimeDeadline.CHALLENGE)), decision.timers)
    }

    @Test fun `health of a superseded command cannot fail the current start`() {
        val decision = reduce(
            starting(),
            RuntimeInput.Health(0, 0, health(TransportHealthState.FAILED, 0), shouldRecover = true),
        )
        assertEquals(RuntimeState.STARTING, decision.state.state)
    }
}
