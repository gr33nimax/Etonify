package io.hydrabox.platform.android

import io.hydrabox.core.contract.RuntimeMode
import io.hydrabox.core.diagnostics.Secret
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The exit address only means something when the request is proven to leave through the
 * tunnel. These tests cover the decision itself, because the failure it guards against is
 * showing the device's own address signed as the tunnel's.
 */
class ExitAddressProbeTest {
    @Test fun `vpn mode with the app inside the tunnel needs no explicit route`() {
        assertEquals(
            ExitAddressProbe.Route.ThroughTun,
            ExitAddressProbe.route(RuntimeMode.VPN, appIncluded = true, proxyInboundEnabled = false, proxyPort = 2080),
        )
    }

    @Test fun `proxy-only mode is proven through the core's local inbound`() {
        val route = ExitAddressProbe.route(RuntimeMode.PROXY, appIncluded = false, proxyInboundEnabled = true, proxyPort = 2080)
        val via = assertIs<ExitAddressProbe.Route.ThroughLocalProxy>(route)
        val address = via.proxy.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(2080, address.port)
        assertNull(via.authorization)
    }

    @Test fun `an app split out of the tunnel is proven the same way`() {
        val route = ExitAddressProbe.route(RuntimeMode.VPN, appIncluded = false, proxyInboundEnabled = true, proxyPort = 2080)
        assertIs<ExitAddressProbe.Route.ThroughLocalProxy>(route)
    }

    @Test fun `without a tunnel path the probe is unprovable rather than direct`() {
        assertEquals(
            ExitAddressProbe.Route.Unprovable,
            ExitAddressProbe.route(RuntimeMode.VPN, appIncluded = false, proxyInboundEnabled = false, proxyPort = 2080),
        )
        assertEquals(
            ExitAddressProbe.Route.Unprovable,
            ExitAddressProbe.route(RuntimeMode.PROXY, appIncluded = true, proxyInboundEnabled = false, proxyPort = 2080),
        )
        assertEquals(
            ExitAddressProbe.Route.Unprovable,
            ExitAddressProbe.route(null, appIncluded = true, proxyInboundEnabled = true, proxyPort = 2080),
        )
    }

    @Test fun `credentials ride in the proxy header when the inbound has them`() {
        val route = ExitAddressProbe.route(
            RuntimeMode.PROXY,
            appIncluded = false,
            proxyInboundEnabled = true,
            proxyPort = 2080,
            proxyUsername = "user",
            proxyPassword = Secret.of("pass"),
        )
        val via = assertIs<ExitAddressProbe.Route.ThroughLocalProxy>(route)
        assertEquals("Basic dXNlcjpwYXNz", via.authorization)
    }

    @Test fun `a username without a password asks for no authentication`() {
        val route = ExitAddressProbe.route(
            RuntimeMode.PROXY,
            appIncluded = false,
            proxyInboundEnabled = true,
            proxyPort = 2080,
            proxyUsername = "user",
        )
        val via = assertIs<ExitAddressProbe.Route.ThroughLocalProxy>(route)
        assertNull(via.authorization)
    }
}
