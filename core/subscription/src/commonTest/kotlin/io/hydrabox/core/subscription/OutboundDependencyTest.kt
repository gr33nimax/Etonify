package io.hydrabox.core.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an outbound needs in order to be dialled at all.
 *
 * A `detour` naming a tag the document no longer contains is not one bad server: the core
 * refuses the whole configuration for it, so one such entry took every other server in the
 * subscription with it. Groups were being dropped unconditionally, which is how a perfectly
 * ordinary provider document — servers that chain through a group — became unusable.
 */
class OutboundDependencyTest {
    private fun singbox(body: String) = OutboundCatalogParser.inspect(body)

    @Test fun `a group an outbound dials through is kept`() {
        val parsed = singbox(
            """
            {"outbounds": [
              {"type": "selector", "tag": "front", "outbounds": ["edge"]},
              {"type": "vless", "tag": "edge", "server": "a.example", "server_port": 443, "uuid": "u"},
              {"type": "trojan", "tag": "relay", "server": "b.example", "server_port": 443, "password": "p", "detour": "front"}
            ]}
            """.trimIndent(),
        )
        val tags = parsed.catalog.outbounds.map(CatalogOutbound::tag)
        assertTrue(tags.contains("front"), "the group a detour names has to survive the import")
        assertEquals(listOf("edge", "relay"), parsed.catalog.selectable.map(CatalogOutbound::tag))
        assertTrue(parsed.skipped.isEmpty())
    }

    @Test fun `a group nothing reaches is left out`() {
        val parsed = singbox(
            """
            {"outbounds": [
              {"type": "selector", "tag": "provider-choice", "outbounds": ["edge"]},
              {"type": "vless", "tag": "edge", "server": "a.example", "server_port": 443, "uuid": "u"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("edge"), parsed.catalog.outbounds.map(CatalogOutbound::tag))
    }

    @Test fun `an outbound whose detour is missing is dropped and named`() {
        val parsed = singbox(
            """
            {"outbounds": [
              {"type": "vless", "tag": "edge", "server": "a.example", "server_port": 443, "uuid": "u"},
              {"type": "trojan", "tag": "orphan", "server": "b.example", "server_port": 443, "password": "p", "detour": "gone"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("edge"), parsed.catalog.outbounds.map(CatalogOutbound::tag))
        assertTrue(parsed.skipped.any { it.contains("orphan") && it.contains("gone") }, parsed.skipped.toString())
    }

    @Test fun `dropping a dependency drops what depended on it`() {
        val parsed = singbox(
            """
            {"outbounds": [
              {"type": "vless", "tag": "edge", "server": "a.example", "server_port": 443, "uuid": "u"},
              {"type": "selector", "tag": "front", "outbounds": ["gone"]},
              {"type": "trojan", "tag": "relay", "server": "b.example", "server_port": 443, "password": "p", "detour": "front"}
            ]}
            """.trimIndent(),
        )
        val tags = parsed.catalog.outbounds.map(CatalogOutbound::tag)
        assertEquals(listOf("edge"), tags)
        assertFalse(tags.contains("relay"))
        assertEquals(2, parsed.skipped.size, parsed.skipped.toString())
    }

    @Test fun `a Hydra resource keeps the group its entrypoint dials through`() {
        val parsed = singbox(
            """
            {
              "api_version": "hydra.io/subscription/v2",
              "kind": "Subscription",
              "resources": [
                {"id": "r1", "document": {"outbounds": [
                  {"type": "selector", "tag": "group", "outbounds": ["edge"]},
                  {"type": "vless", "tag": "edge", "server": "a.example", "server_port": 443, "uuid": "u"},
                  {"type": "trojan", "tag": "relay", "server": "b.example", "server_port": 443, "password": "p", "detour": "group"}
                ]}}
              ],
              "profiles": [
                {"id": "p", "resource": "r1", "entrypoint": {"section": "outbounds", "tag": "relay"}}
              ]
            }
            """.trimIndent(),
        )
        assertTrue(parsed.catalog.outbounds.map(CatalogOutbound::tag).contains("group"))
        assertEquals(listOf("relay"), parsed.catalog.selectable.map(CatalogOutbound::tag))
    }
}
