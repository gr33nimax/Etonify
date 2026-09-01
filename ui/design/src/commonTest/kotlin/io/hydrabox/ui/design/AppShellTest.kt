package io.hydrabox.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals

class AppShellTest {
    @Test fun `three width classes remain stable`() {
        assertEquals(WindowClass.COMPACT, windowClass(599))
        assertEquals(WindowClass.MEDIUM, windowClass(600))
        assertEquals(WindowClass.EXPANDED, windowClass(840))
    }
}
