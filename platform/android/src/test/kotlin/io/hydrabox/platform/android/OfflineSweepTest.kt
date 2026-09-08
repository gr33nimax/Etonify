package io.hydrabox.platform.android

import io.hydrabox.core.contract.EdgeLatencyStatus
import io.hydrabox.core.contract.OutboundLatency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline pass's rules, without a core, the platform or a device: what it measures in
 * what order, when a handover or a stop ends it, and what may still be published after.
 * A handover used to end only the edge questions — the HTTP sessions kept building core
 * instances on the old network and their results were published as though one network had
 * measured them all.
 */
class OfflineSweepTest {
    /** A pass wired to record everything it did, so a test can read the run back. */
    private fun pass(
        moved: () -> Boolean,
        measured: MutableList<String>,
        published: MutableList<String>,
        onEdge: (String) -> OutboundLatency? = { null },
        onHttp: (String) -> OutboundLatency = { OutboundLatency(it, 40, "available") },
    ) = OfflineSweep(
        isCancelled = moved,
        networkStillCurrent = { !moved() },
        measureEdge = { tag -> measured += "edge:$tag"; onEdge(tag) },
        measureHttp = { tag -> measured += "http:$tag"; onHttp(tag) },
        publishEdge = { answers -> published += "edge:" + answers.joinToString(",") { it.tag } },
        publishHttp = { answers -> published += "http:" + answers.joinToString(",") { it.tag } },
    )

    @Test fun `a handover between two servers ends the pass and publishes nothing`() {
        val measured = mutableListOf<String>()
        val published = mutableListOf<String>()
        var moved = false
        pass(
            moved = { moved },
            measured = measured,
            published = published,
            onHttp = { tag ->
                if (tag == "amsterdam") moved = true
                OutboundLatency(tag, 40, "available")
            },
        ).run(
            listOf(
                OfflineSweep.Target("amsterdam", "vless"),
                OfflineSweep.Target("tokyo", "vless"),
            ),
        )
        assertEquals(listOf("http:amsterdam"), measured, "the server after the handover was measured anyway")
        assertTrue(published.isEmpty(), "answers measured across a handover were published")
    }

    @Test fun `a handover during the edge pass ends both passes`() {
        val measured = mutableListOf<String>()
        val published = mutableListOf<String>()
        var moved = false
        pass(
            moved = { moved },
            measured = measured,
            published = published,
            onEdge = { tag ->
                moved = true
                OutboundLatency(tag, 0, EdgeLatencyStatus.NO_EDGE)
            },
        ).run(
            listOf(
                OfflineSweep.Target("vk", "call"),
                OfflineSweep.Target("vk-two", "call"),
                OfflineSweep.Target("amsterdam", "vless"),
            ),
        )
        assertEquals(listOf("edge:vk"), measured, "a question after the handover was asked anyway")
        assertTrue(published.isEmpty(), "an answer from before the handover was published as current")
    }

    @Test fun `an undisturbed pass asks its cheap questions first and publishes each kind separately`() {
        val measured = mutableListOf<String>()
        val published = mutableListOf<String>()
        pass(
            moved = { false },
            measured = measured,
            published = published,
            onEdge = { tag -> OutboundLatency(tag, 0, EdgeLatencyStatus.ANSWERED) },
        ).run(
            listOf(
                OfflineSweep.Target("vk", "call"),
                OfflineSweep.Target("vk-two", "call"),
                OfflineSweep.Target("amsterdam", "vless"),
                OfflineSweep.Target("tokyo", "vless"),
            ),
        )
        assertEquals(listOf("edge:vk", "edge:vk-two", "http:amsterdam", "http:tokyo"), measured)
        assertEquals(listOf("edge:vk,vk-two", "http:amsterdam,tokyo"), published)
    }

    @Test fun `edge answers published before a later handover in the http pass survive it`() {
        val measured = mutableListOf<String>()
        val published = mutableListOf<String>()
        var moved = false
        pass(
            moved = { moved },
            measured = measured,
            published = published,
            onEdge = { tag -> OutboundLatency(tag, 0, EdgeLatencyStatus.ANSWERED) },
            onHttp = { tag ->
                moved = true
                OutboundLatency(tag, 40, "available")
            },
        ).run(
            listOf(
                OfflineSweep.Target("vk", "call"),
                OfflineSweep.Target("amsterdam", "vless"),
                OfflineSweep.Target("tokyo", "vless"),
            ),
        )
        assertEquals(listOf("edge:vk", "http:amsterdam"), measured)
        // The edge answers were current when they were published, before the move; only
        // the http answers, measured across it, are dropped.
        assertEquals(listOf("edge:vk"), published)
    }
}
