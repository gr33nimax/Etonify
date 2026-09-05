package io.hydrabox.ui.app

import io.hydrabox.core.projection.Connection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServersMeasureTest {
    @Test fun `measurement is available before the tunnel starts`() {
        assertTrue(canMeasure(Connection.Idle(server = null)))
        assertFalse(canMeasure(Connection.Connecting(server = null)))
        assertFalse(canMeasure(Connection.Disconnecting))
    }
}
