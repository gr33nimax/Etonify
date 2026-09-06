package io.hydrabox.platform.android

import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workerless edge probe, without a core: the wire format, the record the core writes,
 * and one real exchange against a socket on localhost.
 */
class TurnEdgeProbeTest {
    @Test fun `the core's record parses into an edge, and only a usable shape`() {
        assertEquals(
            TurnEdgeProbe.Endpoint("udp", "relay.example.invalid", 3478),
            TurnEdgeProbe.parseEndpoint("udp://relay.example.invalid:3478"),
        )
        assertEquals(
            TurnEdgeProbe.Endpoint("udp", "2001:db8::1", 3478),
            TurnEdgeProbe.parseEndpoint("udp://[2001:db8::1]:3478"),
            "a bracketed IPv6 edge is an address, not a broken authority",
        )

        // Only a UDP edge answers a datagram; TCP and TLS are recorded but not probed.
        assertFalse(TurnEdgeProbe.parseEndpoint("tcp://relay.example.invalid:3478")!!.probeable)
        assertTrue(TurnEdgeProbe.parseEndpoint("udp://relay.example.invalid:3478")!!.probeable)

        // Anything else is no edge at all — never a guess at what was meant.
        assertNull(TurnEdgeProbe.parseEndpoint("relay.example.invalid:3478"))
        assertNull(TurnEdgeProbe.parseEndpoint("udp://relay.example.invalid"))
        assertNull(TurnEdgeProbe.parseEndpoint("udp://relay.example.invalid:not-a-port"))
        assertNull(TurnEdgeProbe.parseEndpoint("udp://relay.example.invalid:0"))
        assertNull(TurnEdgeProbe.parseEndpoint("quic://relay.example.invalid:3478"))
        assertNull(TurnEdgeProbe.parseEndpoint(""))
    }

    @Test fun `a binding request is a full header with a fresh transaction`() {
        val first = TurnEdgeProbe.bindingRequest()
        assertEquals(20, first.size)
        // Type 0x0001 (Binding), length 0, magic cookie 0x2112A442.
        assertEquals(0x00, first[0].toInt() and 0xff)
        assertEquals(0x01, first[1].toInt() and 0xff)
        assertEquals(0x00, first[2].toInt() and 0xff)
        assertEquals(0x00, first[3].toInt() and 0xff)
        assertEquals(0x21, first[4].toInt() and 0xff)
        assertEquals(0x12, first[5].toInt() and 0xff)
        assertEquals(0xA4, first[6].toInt() and 0xff)
        assertEquals(0x42, first[7].toInt() and 0xff)

        val second = TurnEdgeProbe.bindingRequest()
        assertNotEquals(first.toList().subList(8, 20), second.toList().subList(8, 20), "the transaction id must be fresh")
    }

    @Test fun `an answer proves itself, and an imposter does not`() {
        val request = TurnEdgeProbe.bindingRequest()

        fun answer(type: Int, mutate: (ByteArray) -> Unit = {}): ByteArray {
            val answer = request.copyOf()
            answer[0] = (type ushr 8).toByte()
            answer[1] = type.toByte()
            mutate(answer)
            return answer
        }

        assertTrue(TurnEdgeProbe.isBindingAnswer(answer(0x0101), 20, request), "a Binding success for this transaction is an answer")
        assertTrue(TurnEdgeProbe.isBindingAnswer(answer(0x0111), 20, request), "a Binding error is still this edge answering")

        assertFalse(TurnEdgeProbe.isBindingAnswer(answer(0x0101) { it[8] = (it[8] + 1).toByte() }, 20, request), "another transaction's answer is not ours")
        assertFalse(TurnEdgeProbe.isBindingAnswer(answer(0x0300), 20, request), "an unrelated message type is not an answer")
        assertFalse(TurnEdgeProbe.isBindingAnswer(answer(0x0101) { it[4] = 0x00.toByte() }, 20, request), "a different cookie is not STUN")
        assertFalse(TurnEdgeProbe.isBindingAnswer(answer(0x0101), 12, request), "a header shorter than twenty bytes is not an answer")

        // A length field claiming more than the datagram carried is not an answer either.
        val oversized = answer(0x0101)
        oversized[2] = 0x7F
        oversized[3] = 0xFF.toByte()
        assertFalse(TurnEdgeProbe.isBindingAnswer(oversized, 20, request))
    }

    @Test fun `one real exchange on localhost answers with the round trip`() {
        val responder = DatagramSocket()
        val answered = Thread {
            val packet = DatagramPacket(ByteArray(64), 64)
            try {
                responder.receive(packet)
                // Echo the header back as a Binding success: same cookie, same transaction.
                val reply = packet.data.copyOf(20)
                reply[0] = 0x01
                reply[1] = 0x01
                responder.send(DatagramPacket(reply, reply.size, packet.socketAddress))
            } catch (_: java.io.IOException) {
            }
        }
        answered.start()
        try {
            val edge = TurnEdgeProbe.Endpoint("udp", "127.0.0.1", responder.localPort)
            val rtt = TurnEdgeProbe.probe(edge, { DatagramSocket() }, timeoutMillis = 1_000)
            assertNotNull(rtt, "the edge answered and the exchange should have measured it")
            assertTrue(rtt >= 0)
        } finally {
            responder.close()
            answered.join(1_000)
        }
    }

    @Test fun `a silent edge is out of budget, not an error`() {
        // Nothing is listening: both attempts time out and the probe answers null.
        val silent = DatagramSocket()
        val port = silent.localPort
        silent.close()
        assertNull(TurnEdgeProbe.probe(TurnEdgeProbe.Endpoint("udp", "127.0.0.1", port), { DatagramSocket() }, timeoutMillis = 100))
    }

    @Test fun `a result is cached per edge and network, and only for its window`() {
        val cache = TurnEdgeProbe.Cache(ttlMillis = 1_000)
        val edge = TurnEdgeProbe.Endpoint("udp", "relay.example.invalid", 3478)
        val key = TurnEdgeProbe.cacheKey(edge, networkGeneration = 3)

        assertNull(cache.get(key, nowMillis = 0), "an empty cache has no answer")
        cache.put(key, rttMillis = 120, nowMillis = 0)
        assertEquals(120, cache.get(key, nowMillis = 999))

        // The network generation is part of the key: a handover invalidates every answer.
        assertNull(cache.get(TurnEdgeProbe.cacheKey(edge, networkGeneration = 4), nowMillis = 999))
        // So is the edge itself: two profiles of one provider share it, two edges do not.
        assertNull(cache.get(TurnEdgeProbe.cacheKey(edge.copy(port = 3479), 3), nowMillis = 999))

        // Outside the window the number is worth nothing and a fresh question is asked.
        assertNull(cache.get(key, nowMillis = 1_000), "an expired answer must not be served")
        cache.put(key, rttMillis = 130, nowMillis = 2_000)
        assertEquals(130, cache.get(key, nowMillis = 2_500))
    }
}
