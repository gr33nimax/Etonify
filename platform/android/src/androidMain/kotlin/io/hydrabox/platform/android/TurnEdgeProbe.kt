package io.hydrabox.platform.android

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * A workerless reachability probe for the VK transport's TURN edge.
 *
 * Measuring whether the VK route is reachable used to mean joining a call and allocating:
 * four workers, TURN credentials, QUIC sessions — a transport's worth of cost for one
 * number. The core now keeps the TURN edge a transport really allocated through
 * ([Libbox.hydraCoreTurnEdgeEndpoint]), and this asks that one edge a single STUN Binding
 * question instead. The answer is the round trip to the edge and nothing more: it does not
 * prove credentials, the join, the relay path, or the server behind it — which is why the
 * screen labels it "RTT до TURN edge" and never as a ping of the tunnel.
 *
 * The socket is opened by the caller, because only the service can protect it from its own
 * tunnel and bind it to the network underneath; everything here is plain UDP so the
 * exchange itself can run in a test against a socket on localhost.
 */
object TurnEdgeProbe {
    /** One recorded edge, in the form the core writes it: `network://host:port`. */
    data class Endpoint(val network: String, val host: String, val port: Int) {
        /** Only a UDP edge answers a datagram; TCP and TLS ones are not probed. */
        val probeable: Boolean get() = network == "udp"
    }

    /**
     * Parses the core's record. An unknown shape is no endpoint rather than a guess: the
     * caller shows "not measured", because obtaining an address is never worth a VK
     * authorisation.
     */
    fun parseEndpoint(value: String): Endpoint? {
        val trimmed = value.trim()
        val schemeSeparator = trimmed.indexOf("://")
        if (schemeSeparator <= 0) return null
        val network = trimmed.substring(0, schemeSeparator).lowercase()
        if (network !in setOf("udp", "tcp")) return null
        val authority = trimmed.substring(schemeSeparator + 3)
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close <= 1 || close == authority.length - 1 || authority[close + 1] != ':') return null
            host = authority.substring(1, close)
            portText = authority.substring(close + 2)
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon <= 0) return null
            host = authority.substring(0, colon)
            portText = authority.substring(colon + 1)
        }
        if (host.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65_535) return null
        return Endpoint(network, host, port)
    }

    /**
     * Results by edge and network generation, for the short window an answer is worth.
     *
     * Two profiles of one provider often name the same edge, a sweep asks in one burst, and
     * a network handover invalidates every answer at once — so the key carries both, and an
     * expired entry is a miss again rather than a number the network can no longer produce.
     */
    class Cache(private val ttlMillis: Long = 45_000) {
        private val entries = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

        fun get(key: String, nowMillis: Long): Long? {
            val entry = entries[key] ?: return null
            if (nowMillis - entry.second >= ttlMillis) {
                entries.remove(key, entry)
                return null
            }
            return entry.first
        }

        fun put(key: String, rttMillis: Long, nowMillis: Long) {
            entries[key] = rttMillis to nowMillis
        }
    }

    fun cacheKey(endpoint: Endpoint, networkGeneration: Long): String =
        "${endpoint.network}://${endpoint.host}:${endpoint.port}|$networkGeneration"

    /**
     * One Binding question to the edge, with a single repeat inside the budget: about a
     * second and a half overall, which measures "no answer in the budget" rather than the
     * STUN retransmission procedure in full. The name is resolved outside the timed window,
     * so the answer is the round trip to the edge and not the resolver's.
     *
     * Returns the round trip in milliseconds, or null when the edge did not answer in
     * budget, answered from the wrong address, or replied with something that is not the
     * answer to this question.
     */
    fun probe(endpoint: Endpoint, openSocket: () -> DatagramSocket, timeoutMillis: Int = 700): Long? {
        val address = runCatching { InetAddress.getByName(endpoint.host) }.getOrNull() ?: return null
        val target = InetSocketAddress(address, endpoint.port)
        return openSocket().use { socket ->
            socket.soTimeout = timeoutMillis.coerceAtLeast(50)
            var request = bindingRequest()
            // One attempt and one repeat, exactly the budget a reachability question on
            // demand deserves; anything still unanswered is out of budget, not retried.
            repeat(2) { attempt ->
                if (attempt == 1) request = bindingRequest()
                val started = System.nanoTime()
                socket.send(DatagramPacket(request, request.size, target))
                val answer = ByteArray(RESPONSE_BYTES)
                val packet = DatagramPacket(answer, answer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.io.IOException) {
                    return@repeat
                }
                if (packet.address == address && packet.port == endpoint.port &&
                    isBindingAnswer(answer, packet.length, request)
                ) {
                    return (System.nanoTime() - started) / 1_000_000
                }
            }
            null
        }
    }

    /**
     * A STUN Binding request with no attributes: type, zero length, the magic cookie and a
     * fresh transaction id. RFC 8489 §5 — the response proves itself by echoing the last
     * sixteen bytes back unchanged.
     */
    internal fun bindingRequest(): ByteArray {
        val request = ByteArray(HEADER_BYTES)
        request[0] = 0x00
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x00
        request[4] = (MAGIC_COOKIE ushr 24).toByte()
        request[5] = (MAGIC_COOKIE ushr 16).toByte()
        request[6] = (MAGIC_COOKIE ushr 8).toByte()
        request[7] = MAGIC_COOKIE.toByte()
        val transactionId = ByteArray(TRANSACTION_ID_BYTES)
        transactionIds.nextBytes(transactionId)
        System.arraycopy(transactionId, 0, request, 8, TRANSACTION_ID_BYTES)
        return request
    }

    /** A Binding success or error response for this transaction, from a full-sized header. */
    internal fun isBindingAnswer(answer: ByteArray, length: Int, request: ByteArray): Boolean {
        if (length < HEADER_BYTES || answer.size < HEADER_BYTES) return false
        val type = ((answer[0].toInt() and 0xff) shl 8) or (answer[1].toInt() and 0xff)
        if (type != BINDING_SUCCESS && type != BINDING_ERROR) return false
        val messageLength = ((answer[2].toInt() and 0xff) shl 8) or (answer[3].toInt() and 0xff)
        if (messageLength > length - HEADER_BYTES) return false
        // The cookie and the transaction id come back unchanged; a different id is another
        // client's answer to another question.
        for (index in 4 until HEADER_BYTES) {
            if (answer[index] != request[index]) return false
        }
        return true
    }

    private val transactionIds = SecureRandom()

    private const val HEADER_BYTES = 20
    private const val TRANSACTION_ID_BYTES = 12
    private const val RESPONSE_BYTES = 128
    private const val MAGIC_COOKIE = 0x2112A442
    private const val BINDING_SUCCESS = 0x0101
    private const val BINDING_ERROR = 0x0111
}
