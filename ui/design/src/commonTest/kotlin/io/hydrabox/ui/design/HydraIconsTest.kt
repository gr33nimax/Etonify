package io.hydrabox.ui.design

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class HydraIconsTest {
    @Test
    fun `semantic icons keep the shared 24 dp canvas`() {
        val icons = listOf(
            HydraIcons.Server,
            HydraIcons.Subscription,
            HydraIcons.Traffic,
            HydraIcons.Connections,
            HydraIcons.Document,
            HydraIcons.Lock,
            HydraIcons.Export,
            HydraIcons.Share,
        )

        assertEquals(icons.size, icons.map { it.name }.distinct().size)
        icons.forEach {
            assertEquals(24.dp, it.defaultWidth)
            assertEquals(24.dp, it.defaultHeight)
            assertEquals(24f, it.viewportWidth)
            assertEquals(24f, it.viewportHeight)
        }
    }
}
