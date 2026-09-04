package io.hydrabox.ui.app

import io.hydrabox.core.projection.Connection
import io.hydrabox.core.projection.ServerRef
import io.hydrabox.core.projection.Trouble
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeActionTest {
    @Test fun `unreachable server opens server selection`() {
        var opened = 0
        var retried = 0

        dispatchHomeAction(
            Connection.Stopped(Trouble.SERVER_UNREACHABLE, ServerRef("id", "Server"), retryable = true),
            sourceId = null,
            actions = AppActions(onRetry = { retried += 1 }),
            onOpenServers = { opened += 1 },
        )

        assertEquals(1, opened)
        assertEquals(0, retried)
    }

    @Test fun `unavailable subscription refreshes its source`() {
        var refreshed: String? = null
        var retried = 0

        dispatchHomeAction(
            Connection.Stopped(Trouble.SUBSCRIPTION_UNAVAILABLE, server = null, retryable = true),
            sourceId = "subscription-1",
            actions = AppActions(onRefreshSource = { refreshed = it }, onRetry = { retried += 1 }),
            onOpenServers = {},
        )

        assertEquals("subscription-1", refreshed)
        assertEquals(0, retried)
    }
}
