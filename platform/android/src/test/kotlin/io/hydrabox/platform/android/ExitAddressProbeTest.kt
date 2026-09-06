package io.hydrabox.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The exit address itself is the core's answer, not this app's own request; what is left to
 * test here is the part that turns a country code into what a row shows.
 */
class ExitAddressProbeTest {
    @Test fun `a country code becomes its flag`() {
        assertEquals("🇩🇪", ExitAddressProbe.flagOf("de"))
        assertEquals("🇩🇪", ExitAddressProbe.flagOf("DE"))
    }

    @Test fun `anything but two letters is not a country code`() {
        assertNull(ExitAddressProbe.flagOf(null))
        assertNull(ExitAddressProbe.flagOf(""))
        assertNull(ExitAddressProbe.flagOf("d"))
        assertNull(ExitAddressProbe.flagOf("deu"))
        assertNull(ExitAddressProbe.flagOf("12"))
        assertNull(ExitAddressProbe.flagOf("d1"))
    }
}
