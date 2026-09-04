package io.hydrabox.core.runtime

import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.contract.RuntimeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLOSE deadline used to be armed and unanswered: `stop` arms it, and the `Deadline`
 * branch only looked at STARTING and RECOVERING. A core that never confirmed its release left
 * the runtime in STOPPING with no way out, and the only visible trace was a warning nobody
 * acted on.
 */
class CloseDeadlineTest {
    private fun stopping(): RuntimeModel {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 4, mode = RuntimeMode.VPN, wantRunning = true)
        val decision = reduce(running, RuntimeInput.Stop)
        assertEquals(RuntimeState.STOPPING, decision.state.state)
        assertTrue(TimerOp.Arm(5, RuntimeDeadline.CLOSE) in decision.timers)
        return decision.state
    }

    @Test fun `close deadline releases a stop that never reported back`() {
        val decision = reduce(stopping(), RuntimeInput.Deadline(5))
        assertEquals(RuntimeState.FAILED, decision.state.state)
        assertEquals(listOf(TimerOp.Cancel(5)), decision.timers)
        assertNull(decision.state.mode)
    }

    @Test fun `a release that does arrive still wins and reaches stopped`() {
        val decision = reduce(stopping(), RuntimeInput.Released(5, success = true))
        assertEquals(RuntimeState.STOPPED, decision.state.state)
    }

    @Test fun `close deadline of a superseded command is ignored`() {
        assertEquals(RuntimeState.STOPPING, reduce(stopping(), RuntimeInput.Deadline(4)).state.state)
    }

    @Test fun `a deferred start is dropped when the close had to be forced`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 1, mode = RuntimeMode.VPN)
        val stopping = reduce(running, RuntimeInput.Start(RuntimeMode.PROXY)).state
        assertEquals(RuntimeMode.PROXY, stopping.deferredStart)
        val forced = reduce(stopping, RuntimeInput.Deadline(2))
        assertEquals(RuntimeState.FAILED, forced.state.state)
        assertNull(forced.state.deferredStart)
        assertTrue(forced.effects.isEmpty())
    }
}
