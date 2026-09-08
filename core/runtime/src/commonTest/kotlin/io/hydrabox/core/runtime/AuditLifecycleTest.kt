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

    @Test fun `the group's answers and the edge's answers do not erase each other`() {
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 3)
        val tokyo = OutboundLatency("tokyo", 80, "ok")
        val edge = OutboundLatency("vk", 120, "edge")

        // Whichever producer answers second used to wipe the first's figures: an edge round
        // trip appeared and vanished when the group's slower measurement landed, and the
        // regular servers lost their figures to the edge's on a mixed list. The two kinds
        // keep their own lists now — routed by what each answer says about itself — and
        // whichever order they arrive in, neither erases the other.
        val afterEdge = reduce(running, RuntimeInput.Latencies(listOf(edge), 3)).state
        val afterGroup = reduce(afterEdge, RuntimeInput.Latencies(listOf(tokyo), 3)).state
        assertEquals(mapOf("tokyo" to tokyo), afterGroup.latencies.associateBy { it.tag })
        assertEquals(mapOf("vk" to edge), afterGroup.edgeLatencies.associateBy { it.tag })

        val groupFirst = reduce(running, RuntimeInput.Latencies(listOf(tokyo), 3)).state
        val edgeSecond = reduce(groupFirst, RuntimeInput.Latencies(listOf(edge), 3)).state
        assertEquals(mapOf("tokyo" to tokyo), edgeSecond.latencies.associateBy { it.tag })
        assertEquals(mapOf("vk" to edge), edgeSecond.edgeLatencies.associateBy { it.tag })

        // For a server both measured, the later answer is the newer one — and both kinds
        // can be true of one server at once: an HTTP delay and an edge round trip answer
        // different questions about it.
        val refreshed = reduce(afterGroup, RuntimeInput.Latencies(listOf(OutboundLatency("tokyo", 95, "ok")), 3)).state
        assertEquals(95, refreshed.latencies.single { it.tag == "tokyo" }.delayMillis)
        val both = reduce(refreshed, RuntimeInput.Latencies(listOf(OutboundLatency("tokyo", 30, "edge")), 3)).state
        assertEquals(95, both.latencies.single { it.tag == "tokyo" }.delayMillis)
        assertEquals(30, both.edgeLatencies.single { it.tag == "tokyo" }.delayMillis)
    }

    @Test fun `a re-announced selection is not a change`() {
        // The core re-announces every group in every group message. Re-appending a
        // re-announced entry used to flip the list's order twice per message, which read as
        // a route change downstream — re-asking the exit address for every flip and
        // discarding every answer as superseded, until the row sat in "checking" for as
        // long as the messages kept coming.
        val running = RuntimeModel(state = RuntimeState.RUNNING, commandGeneration = 3)
        val announced = reduce(
            running,
            RuntimeInput.SelectionObserved(3, OutboundSelection("select", "tokyo")),
        ).state
        val announcedAgain = reduce(
            announced,
            RuntimeInput.SelectionObserved(3, OutboundSelection("auto", "oslo")),
        ).state
        val originalOrder = announcedAgain.observedOutbounds

        repeat(5) {
            val reannounced = reduce(
                announcedAgain,
                RuntimeInput.SelectionObserved(3, OutboundSelection("select", "tokyo")),
            ).state
            assertEquals(originalOrder, reannounced.observedOutbounds, "a re-announcement reordered the groups")
            val reannouncedAuto = reduce(
                reannounced,
                RuntimeInput.SelectionObserved(3, OutboundSelection("auto", "oslo")),
            ).state
            assertEquals(originalOrder, reannouncedAuto.observedOutbounds, "a re-announcement reordered the groups")
        }

        // A genuinely different leaf still changes the answer, in place.
        val switched = reduce(
            announcedAgain,
            RuntimeInput.SelectionObserved(3, OutboundSelection("select", "oslo")),
        ).state
        assertEquals(
            listOf(OutboundSelection("select", "oslo"), OutboundSelection("auto", "oslo")),
            switched.observedOutbounds,
        )
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
