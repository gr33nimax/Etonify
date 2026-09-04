package io.hydrabox.ui.design

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class AppShellTest {
    @Test fun `three width classes remain stable`() {
        assertEquals(WindowClass.COMPACT, windowClass(599))
        assertEquals(WindowClass.MEDIUM, windowClass(600))
        assertEquals(WindowClass.EXPANDED, windowClass(840))
    }

    @Test fun `zero animation scale requests reduced motion`() {
        assertEquals(true, isReducedMotion(0f))
        assertEquals(false, isReducedMotion(1f))
    }

    @Test fun `container radii preserve visual hierarchy`() {
        assertEquals(12.dp, UiTokens.rowRadius)
        assertEquals(16.dp, UiTokens.sectionRadius)
        assertEquals(20.dp, UiTokens.objectRadius)
        assertEquals(28.dp, UiTokens.heroRadius)
    }
}
