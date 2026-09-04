package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the document that decide whether the core accepts it at all, and whether a
 * connected tunnel actually carries traffic.
 */
class TunnelConfigSectionsTest {
    private fun proxy(tag: String) = CatalogOutbound(
        tag = tag,
        type = "vless",
        json = buildJsonObject { put("type", "vless"); put("tag", tag); put("server", "example") },
    )

    private fun peer(tag: String) = CatalogOutbound(
        tag = tag,
        type = "wireguard",
        endpoint = true,
        json = buildJsonObject {
            put("type", "wireguard")
            put("tag", tag)
            put("private_key", "cHJpdmF0ZQ==")
            putJsonArray("address") { add(kotlinx.serialization.json.JsonPrimitive("10.0.0.2/32")) }
        },
    )

    private fun document(vararg outbounds: CatalogOutbound): JsonObject = TunnelConfigGenerator.build(
        TunnelInput(outbounds = outbounds.toList(), selectedTag = outbounds.firstOrNull()?.tag),
    )

    @Test fun `a WireGuard peer is generated as an endpoint, never as an outbound`() {
        val built = document(proxy("tokyo"), peer("awg"))
        val endpoints = built["endpoints"]!!.jsonArray
        assertEquals(listOf("awg"), endpoints.map { it.jsonObject["tag"]!!.jsonPrimitive.content })
        val outboundTags = built["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("awg" !in outboundTags)
        // It is still a server a person can choose, and the groups still name it.
        val selector = built["outbounds"]!!.jsonArray.single { it.jsonObject["tag"]!!.jsonPrimitive.content == SELECTOR_TAG }
        assertTrue("awg" in selector.jsonObject["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `no endpoints section appears when there is no endpoint`() {
        assertNull(document(proxy("tokyo"))["endpoints"])
    }

    @Test fun `the DNS layout answers IPv4 only and keeps its caches`() {
        val dns = document(proxy("tokyo"))["dns"]!!.jsonObject
        assertEquals("ipv4_only", dns["strategy"]!!.jsonPrimitive.content)
        assertEquals("true", dns["independent_cache"]!!.jsonPrimitive.content)
        assertEquals(4096, dns["cache_capacity"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `a chosen address family reaches the resolver, and auto names nothing`() {
        fun strategyOf(mode: String) = TunnelConfigGenerator.build(
            TunnelInput(outbounds = listOf(proxy("tokyo")), selectedTag = "tokyo", dnsStrategy = mode),
        )["dns"]!!.jsonObject["strategy"]
        assertEquals("ipv6_only", strategyOf("ipv6_only")!!.jsonPrimitive.content)
        // The core has no "auto": the absence of the field is what lets it answer with both,
        // and naming a strategy it does not know would be refused with the whole document.
        assertNull(strategyOf("auto"))
    }

    @Test fun `the core is given a cache file so it does not relearn everything on each start`() {
        val cache = document(proxy("tokyo"))["experimental"]!!.jsonObject["cache_file"]!!.jsonObject
        assertEquals("true", cache["enabled"]!!.jsonPrimitive.content)
        assertEquals("true", cache["store_rdrc"]!!.jsonPrimitive.content)
    }

    @Test fun `the direct outbound gets the same socket options as the proxies`() {
        val built = TunnelConfigGenerator.build(
            TunnelInput(
                outbounds = listOf(proxy("tokyo")),
                selectedTag = "tokyo",
                tcpFastOpen = true,
                tcpMultiPath = true,
            ),
        )
        val direct = built["outbounds"]!!.jsonArray
            .single { it.jsonObject["tag"]!!.jsonPrimitive.content == DIRECT_TAG }.jsonObject
        assertEquals("true", direct["tcp_fast_open"]!!.jsonPrimitive.content)
        assertEquals("true", direct["tcp_multi_path"]!!.jsonPrimitive.content)
    }

    @Test fun `an off log level disables the core's log instead of quietening it`() {
        fun log(level: String) = TunnelConfigGenerator.build(
            TunnelInput(outbounds = listOf(proxy("tokyo")), selectedTag = "tokyo", logLevel = level),
        )["log"]!!.jsonObject
        assertEquals("warn", log("warn")["level"]!!.jsonPrimitive.content)
        assertNull(log("warn")["disabled"])
        // The core formats every line whatever the level says, so a quiet level is not
        // a cheap one. Only `disabled` stops the work.
        assertEquals("true", log("off")["disabled"]!!.jsonPrimitive.content)
        assertNull(log("off")["level"])
    }

    @Test fun `the bootstrap resolver is what a server hostname is found through`() {
        val built = TunnelConfigGenerator.build(
            TunnelInput(
                outbounds = listOf(proxy("tokyo")),
                selectedTag = "tokyo",
                bootstrapDnsResolver = "udp://77.88.8.8",
                proxyDnsResolver = "https://dns.quad9.net/dns-query",
            ),
        )
        val servers = built["dns"]!!.jsonObject["servers"]!!.jsonArray.associateBy {
            it.jsonObject["tag"]!!.jsonPrimitive.content
        }
        val bootstrap = servers[BOOTSTRAP_DNS_TAG]!!.jsonObject
        assertEquals("77.88.8.8", bootstrap["server"]!!.jsonPrimitive.content)
        assertEquals(DIRECT_TAG, bootstrap["detour"]!!.jsonPrimitive.content)
        // Everything that has to dial a name before the tunnel exists goes through it, and the
        // named tunnel resolver is found the same way. Nothing routine asks the network's own.
        assertEquals(
            BOOTSTRAP_DNS_TAG,
            built["route"]!!.jsonObject["default_domain_resolver"]!!.jsonPrimitive.content,
        )
        assertEquals(BOOTSTRAP_DNS_TAG, servers["dns-proxy"]!!.jsonObject["domain_resolver"]!!.jsonPrimitive.content)
    }

    @Test fun `the network's own resolver is a choice, not the default`() {
        val servers = TunnelConfigGenerator.build(
            TunnelInput(
                outbounds = listOf(proxy("tokyo")),
                selectedTag = "tokyo",
                bootstrapDnsResolver = "device://network",
            ),
        )["dns"]!!.jsonObject["servers"]!!.jsonArray
        val bootstrap = servers.single { it.jsonObject["tag"]!!.jsonPrimitive.content == BOOTSTRAP_DNS_TAG }.jsonObject
        assertEquals("local", bootstrap["type"]!!.jsonPrimitive.content)
        assertNull(bootstrap["server"])
    }

    @Test fun `a name an outbound dials is resolved by the chosen resolver, never by the network's`() {
        val route = document(proxy("tokyo"))["route"]!!.jsonObject
        // `dns-local` is the platform resolver, which is the network's: pointing this at it
        // asked the provider for every direct-routed name, and a leak test saw it.
        assertEquals(BOOTSTRAP_DNS_TAG, route["default_domain_resolver"]!!.jsonPrimitive.content)
    }

    @Test fun `a resolver keeps its protocol, and only a named one asks the platform`() {
        fun server(tag: String, input: TunnelInput) = TunnelConfigGenerator.build(input)["dns"]!!.jsonObject["servers"]!!
            .jsonArray.single { it.jsonObject["tag"]!!.jsonPrimitive.content == tag }.jsonObject
        val encrypted = server(
            "dns-direct",
            TunnelInput(
                outbounds = listOf(proxy("tokyo")),
                selectedTag = "tokyo",
                directDnsResolver = "https://1.1.1.1/dns-query",
            ),
        )
        // A router that redirects port 53 answers a udp resolver in place of the address that
        // was asked. TLS to the resolver's own address cannot be answered by anything else,
        // and an address needs no name resolved to reach it.
        assertEquals("https", encrypted["type"]!!.jsonPrimitive.content)
        assertEquals("1.1.1.1", encrypted["server"]!!.jsonPrimitive.content)
        assertEquals("/dns-query", encrypted["path"]!!.jsonPrimitive.content)
        assertNull(encrypted["domain_resolver"])
        val plain = server(
            "dns-direct",
            TunnelInput(outbounds = listOf(proxy("tokyo")), selectedTag = "tokyo", directDnsResolver = "udp://9.9.9.9:5353"),
        )
        assertEquals("udp", plain["type"]!!.jsonPrimitive.content)
        assertEquals(5353, plain["server_port"]!!.jsonPrimitive.content.toInt())
        val named = server(
            "dns-proxy",
            TunnelInput(
                outbounds = listOf(proxy("tokyo")),
                selectedTag = "tokyo",
                proxyDnsResolver = "https://dns.cloudflare.com/dns-query",
            ),
        )
        assertEquals(BOOTSTRAP_DNS_TAG, named["domain_resolver"]!!.jsonPrimitive.content)
        // The bootstrap resolver is the one thing that may fall back to the network's own,
        // because when it is named by hostname there is nothing else left to ask.
        val namedBootstrap = server(
            BOOTSTRAP_DNS_TAG,
            TunnelInput(
                outbounds = listOf(proxy("tokyo")),
                selectedTag = "tokyo",
                bootstrapDnsResolver = "https://dns.google/dns-query",
            ),
        )
        assertEquals("dns-local", namedBootstrap["domain_resolver"]!!.jsonPrimitive.content)
    }

    @Test fun `fake addresses are a reserved range, a rule for address queries, and a stored table`() {
        val built = TunnelConfigGenerator.build(
            TunnelInput(outbounds = listOf(proxy("tokyo")), selectedTag = "tokyo", fakeIp = true),
        )
        val dns = built["dns"]!!.jsonObject
        val fake = dns["servers"]!!.jsonArray.single { it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-fake" }.jsonObject
        assertEquals("fakeip", fake["type"]!!.jsonPrimitive.content)
        assertEquals(FAKE_IPV4_RANGE, fake["inet4_range"]!!.jsonPrimitive.content)
        // Only address queries are faked; anything else keeps the resolver it had, or a TXT
        // and an HTTPS record would be answered with a benchmarking address.
        val rule = dns["rules"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("A", "AAAA"), rule["query_type"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("dns-fake", rule["server"]!!.jsonPrimitive.content)
        assertEquals("true", built["experimental"]!!.jsonObject["cache_file"]!!.jsonObject["store_fakeip"]!!.jsonPrimitive.content)
    }

    @Test fun `without fake addresses nothing about them appears`() {
        val built = document(proxy("tokyo"))
        assertNull(built["dns"]!!.jsonObject["rules"])
        assertNull(built["experimental"]!!.jsonObject["cache_file"]!!.jsonObject["store_fakeip"])
        assertTrue(
            built["dns"]!!.jsonObject["servers"]!!.jsonArray.none {
                it.jsonObject["tag"]!!.jsonPrimitive.content == "dns-fake"
            },
        )
    }
}
