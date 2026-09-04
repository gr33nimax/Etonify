package io.hydrabox.core.subscription

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a real subscription body looks like: a mix of protocols, one or two of which this
 * build has no mapping for. The whole import must not fail because of them, and the ones that
 * were dropped must be nameable afterwards — an import that silently returns half a list is
 * how "it imported fine, there are just no servers" happens.
 */
class MixedSubscriptionImportTest {
    @OptIn(ExperimentalEncodingApi::class)
    private fun encoded(vararg lines: String) =
        Base64.Default.encode(lines.joinToString("\n").encodeToByteArray())

    @Test fun `a body with an unsupported scheme imports the rest and names what it skipped`() {
        val outcome = OutboundCatalogParser.inspect(
            encoded(
                "sn://awg?eNotjs1OwkAYRUcSTXwE44adC5J2vplOO7NgAUFoIEQUSokrh3YqWITyVwZ23cLet2DjQh_Cl3JMvLknuauTW0IIUbA8zyKYWu4X",
                "anytls://58155b84deb4915cb2029dff2411b3a915938858f45cd21b20656470e8578b71@homelander.example:443?sni=homelander.example#AnyTLS",
                "vless://3a829484-e21e-4091-a839-cdf5d9cc177b@heisenberg.example:443?encryption=none&security=tls&sni=heisenberg.example&type=xhttp&mode=stream-up#VLESS",
            ),
        )
        assertEquals(2, outcome.catalog.selectable.size)
        assertEquals(1, outcome.skipped.size)
        assertContains(outcome.skipped.single(), "sn")
        assertEquals(listOf("AnyTLS", "VLESS"), outcome.catalog.outbounds.map { it.tag })
    }

    @Test fun `a WireGuard peer in a link body is offered as an endpoint`() {
        val outcome = OutboundCatalogParser.inspect(
            encoded(
                "wg://31.77.203.66:52017?private_key=cHJpdmF0ZQ%3D%3D&public_key=cHVibGlj&local_address=10.67.67.3/32&jc=2#AWG",
                "anytls://token@any.example:443#AnyTLS",
            ),
        )
        val wireGuard = outcome.catalog.outbounds.single { it.tag == "AWG" }
        assertTrue(wireGuard.endpoint)
        assertTrue(outcome.catalog.outbounds.single { it.tag == "AnyTLS" }.endpoint.not())
    }

    @Test fun `servers a provider gave the same name keep separate tags`() {
        val outcome = OutboundCatalogParser.inspect(
            encoded(
                "anytls://one@a.example:443#Germany",
                "anytls://two@b.example:443#Germany",
            ),
        )
        assertEquals(listOf("Germany", "Germany (1)"), outcome.catalog.outbounds.map { it.tag })
    }

    @Test fun `a body with nothing usable is refused rather than imported empty`() {
        kotlin.test.assertFails { OutboundCatalogParser.parse(encoded("sn://awg?abc", "nonsense://x")) }
    }
}
