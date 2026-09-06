package io.hydrabox.platform.android

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class SubscriptionRefreshJobTest {
    @Test fun `cancelling before attach disconnects the new request`() {
        val cancellation = SubscriptionFetcher.Cancellation()
        val connection = TrackingConnection()

        cancellation.cancel()

        assertFailsWith<CancellationException> { cancellation.attach(connection) }
        assertTrue(connection.disconnected)
    }

    @Test fun `stopped run cannot start another target or finish the job`() {
        val gate = RefreshGate()
        gate.stop()

        assertFalse(gate.active())
        assertFalse(gate.claimFinish())
    }

    @Test fun `run completion is claimed once`() {
        val gate = RefreshGate()

        assertTrue(gate.claimFinish())
        assertFalse(gate.claimFinish())
    }

    @Test fun `refresh deadline falls back for missing invalid and overflowing provider intervals`() {
        assertEquals(6L * 60 * 60 * 1000, refreshAt(0, null))
        assertEquals(6L * 60 * 60 * 1000, refreshAt(0, "0"))
        assertEquals(6L * 60 * 60 * 1000, refreshAt(0, Long.MAX_VALUE.toString()))
        assertEquals(6L * 60 * 60 * 1000, refreshAt(0, "999999999999999999999"))
    }

    @Test fun `scoped selection follows a collision when its source becomes first`() {
        assertEquals(
            "same",
            resolveSelection(
                stored = "same@two",
                sourceId = "two",
                originalTag = "same",
                selections = mapOf("same" to ScopedSelection("two", "same")),
            ),
        )
    }
}

private class TrackingConnection : HttpURLConnection(URL("https://example.test")) {
    var disconnected = false

    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() = Unit
}
