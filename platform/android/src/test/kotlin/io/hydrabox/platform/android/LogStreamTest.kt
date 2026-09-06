package io.hydrabox.platform.android

import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The log stream's reconnect rules, without a core: what happens when it is lost, when it
 * comes back, when a stream that was already replaced says goodbye late, and when nobody
 * wants it any more. Losing it used to leave a dead client behind that blocked every later
 * enable until the process ended.
 */
class LogStreamTest {
    /** The handler the stream built, captured so the test can speak as the core would. */
    private class Client(val handler: CommandClientHandler) : LogStream.Client {
        val connects = AtomicInteger(0)
        val disconnects = AtomicInteger(0)
        var refuseConnection: Boolean = false

        override fun connect() {
            if (refuseConnection) throw java.io.IOException("socket refused")
            connects.incrementAndGet()
        }

        override fun disconnect() {
            disconnects.incrementAndGet()
        }

        fun lost(reason: String? = "stream closed") = handler.disconnected(reason)
    }

    private val base = object : CommandClientHandler {
        override fun connected() = Unit
        override fun disconnected(message: String?) = Unit
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeStatus(message: StatusMessage?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
    }

    private fun stream(
        clients: MutableList<Client>,
        refuseFirst: Int = 0,
        maxAttempts: Int = 5,
    ) = LogStream(
        base = base,
        newClient = { handler ->
            Client(handler).also { client ->
                client.refuseConnection = clients.size < refuseFirst
                clients += client
            }
        },
        schedule = { _, action -> action() },
        maxAttempts = maxAttempts,
    )

    @Test fun `a lost stream is reconnected, and the loss is visible until it is`() {
        val clients = mutableListOf<Client>()
        val stream = stream(clients)

        assertTrue(stream.setEnabled(true), "enabling must open the stream")
        assertEquals(1, clients.size)
        assertTrue(stream.open)

        // The core drops the connection: the stream must come back on its own, and the
        // replacement must actually be connected.
        clients.first().lost()
        assertEquals(2, clients.size, "a lost stream was not reconnected")
        assertTrue(stream.open)
        assertEquals(1, clients.last().connects.toInt(), "the replacement was never connected")
    }

    @Test fun `a late goodbye from a replaced stream does not take down its replacement`() {
        val clients = mutableListOf<Client>()
        val stream = stream(clients)
        stream.setEnabled(true)

        val first = clients.removeAt(0)
        first.lost()
        assertEquals(1, clients.size, "a lost stream was not reconnected")
        val replacement = clients.last()
        assertEquals(1, replacement.connects.toInt())

        // The first client's goodbye arrives again, late, from a thread that had not
        // noticed the replacement.
        first.lost("late goodbye")

        assertTrue(stream.open, "a late goodbye took down the replacement")
        assertEquals(1, clients.size, "a late goodbye opened yet another stream")
        assertEquals(replacement, clients.last())
    }

    @Test fun `retries stop once nobody wants the stream`() {
        val clients = mutableListOf<Client>()
        val stream = stream(clients, refuseFirst = 100, maxAttempts = 2)

        stream.setEnabled(true)
        // Every connect is refused and every retry runs synchronously, so the initial
        // attempt and the two retries have all happened by the time setEnabled returns.
        assertEquals(3, clients.size, "the retry budget was not bounded")

        stream.setEnabled(false)
        // A retry scheduled before the disable must find nothing to do.
        assertTrue(clients.all { it.connects.toInt() == 0 })
        assertFalse(stream.open)
    }

    @Test fun `a refused connect is retried and the budget resets on success`() {
        val clients = mutableListOf<Client>()
        val stream = stream(clients, refuseFirst = 2, maxAttempts = 5)

        assertTrue(stream.setEnabled(true), "the stream is open once a connect succeeds")
        assertEquals(3, clients.size, "two refusals then a success")
        assertTrue(stream.open)

        // The successful stream is lost once: the budget counted from zero again, so the
        // next attempt is available.
        clients.last().lost()
        assertEquals(4, clients.size)
        assertTrue(stream.open)
    }

    @Test fun `the retry budget is per outage, not for the life of the stream`() {
        val clients = mutableListOf<Client>()
        val stream = stream(clients, maxAttempts = 5)

        stream.setEnabled(true)

        // More outages than the retry budget allows, one at a time: every successful
        // reconnect has to reset the counter, or the sixth loss leaves the process without
        // core lines for good.
        repeat(7) {
            assertTrue(stream.open, "the stream was open before outage ${it + 1}")
            clients.last().lost()
        }

        assertEquals(8, clients.size, "an outage was not recovered")
        assertTrue(stream.open)
        assertEquals(1, clients.last().connects.toInt())
    }

    @Test fun `disable disconnects the live stream`() {
        val clients = mutableListOf<Client>()
        val stream = stream(clients)
        stream.setEnabled(true)

        assertFalse(stream.setEnabled(false))
        assertEquals(1, clients.single().disconnects.toInt(), "disabling must disconnect the live stream")
        assertFalse(stream.open)
    }
}
