package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunnelConfigGeneratorTest {
    private fun outbound(tag: String) = CatalogOutbound(
        tag = tag,
        type = "vless",
        json = buildJsonObject { put("type", "vless"); put("tag", tag); put("server", "example") },
    )

    private fun rules(input: TunnelInput): JsonArray =
        TunnelConfigGenerator.build(input).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray

    private fun input(blockLeaks: Boolean = true, bypassLocal: Boolean = true) = TunnelInput(
        outbounds = listOf(outbound("tokyo")),
        selectedTag = "tokyo",
        blockLeaks = blockLeaks,
        bypassLocalNetwork = bypassLocal,
    )

    private fun JsonObject.field(name: String) = this[name]?.jsonPrimitive?.content

    @Test
    fun `queries to a hardcoded resolver address are hijacked, not only the DNS protocol`() {
        val logical = rules(input()).map { it.jsonObject }.first { it.field("type") == "logical" }
        assertEquals("hijack-dns", logical.field("action"))
        val matched = logical["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(matched.any { it.field("protocol") == "dns" })
        assertTrue(matched.any { it.field("port") == "53" })
    }

    @Test
    fun `the tunnel gateway does not answer pings`() {
        val icmp = rules(input()).map { it.jsonObject }.first { it.field("network") == "icmp" }
        assertEquals("reject", icmp.field("action"))
        assertEquals("drop", icmp.field("method"))
    }

    @Test
    fun `blocking leaks rejects STUN and turning it off removes the rule entirely`() {
        assertTrue(rules(input(blockLeaks = true)).map { it.jsonObject }.any { it.field("protocol") == "stun" })
        assertTrue(rules(input(blockLeaks = false)).map { it.jsonObject }.none { it.field("protocol") == "stun" })
    }

    @Test
    fun `the local network is reachable while the tunnel is up, unless asked otherwise`() {
        val on = rules(input(bypassLocal = true)).map { it.jsonObject }
            .firstOrNull { it["ip_is_private"] != null }
        assertEquals(DIRECT_TAG, on?.field("outbound"))
        assertTrue(rules(input(bypassLocal = false)).map { it.jsonObject }.none { it["ip_is_private"] != null })
    }

    @Test
    fun `the tunnel implementation and strict route come from the settings, not a constant`() {
        val tun = TunnelConfigGenerator
            .build(input().copy(strictRoute = true, tunStack = "gvisor"))
            .jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("true", tun.field("strict_route"))
        assertEquals("gvisor", tun.field("stack"))
    }

    @Test
    fun `TLS fragmentation is applied to the handshake, and only when there is one`() {
        val withTls = TunnelInput(
            outbounds = listOf(
                CatalogOutbound(
                    tag = "tls-node",
                    type = "vless",
                    json = buildJsonObject {
                        put("type", "vless"); put("tag", "tls-node"); put("server", "example")
                        putJsonObject("tls") { put("enabled", true); put("server_name", "example") }
                    },
                ),
                outbound("plain"),
            ),
            selectedTag = "tls-node",
            tlsFragmentation = "fragment",
        )
        val outbounds = TunnelConfigGenerator.build(withTls).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }
        val fragmented = outbounds.first { it.field("tag") == "tls-node" }["tls"]!!.jsonObject
        assertEquals("true", fragmented["fragment"]?.jsonPrimitive?.content)
        assertEquals("300ms", fragmented["fragment_fallback_delay"]?.jsonPrimitive?.content)
        assertEquals("example", fragmented["server_name"]?.jsonPrimitive?.content)
        assertTrue(outbounds.first { it.field("tag") == "plain" }["tls"] == null)
    }

    @Test
    fun `record fragmentation replaces fragment rather than adding to it`() {
        val tls = buildJsonObject {
            put("type", "trojan"); put("tag", "t"); put("server", "example")
            putJsonObject("tls") { put("enabled", true); put("fragment", true) }
        }
        val outbound = TunnelConfigGenerator
            .build(TunnelInput(listOf(CatalogOutbound("t", "trojan", tls)), "t", tlsFragmentation = "record"))
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it.field("tag") == "t" }["tls"]!!.jsonObject
        assertEquals("true", outbound["record_fragment"]?.jsonPrimitive?.content)
        assertTrue(outbound["fragment"] == null)
    }

    @Test
    fun `dial options land on servers and never on the groups`() {
        val outbounds = TunnelConfigGenerator
            .build(input().copy(tcpFastOpen = true, tcpMultiPath = true))
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
        val server = outbounds.first { it.field("tag") == "tokyo" }
        assertEquals("true", server.field("tcp_fast_open"))
        assertEquals("true", server.field("tcp_multi_path"))
        val group = outbounds.first { it.field("type") == "urltest" }
        assertTrue(group["tcp_fast_open"] == null)
    }

    @Test
    fun `the automatic group carries the chosen tolerance and idle timeout`() {
        val strict = TunnelConfigGenerator
            .build(input().copy(urlTestToleranceMillis = 1, urlTestIntervalSeconds = 300))
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it.field("type") == "urltest" }
        assertEquals("1", strict.field("tolerance"))
        assertEquals("300s", strict.field("interval"))
        assertEquals("300s", strict.field("idle_timeout"))
    }

    @Test
    fun `whether a server change drops open connections is a setting, not a constant`() {
        val selector = TunnelConfigGenerator
            .build(input().copy(interruptExistingConnections = true))
            .jsonObject["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it.field("type") == "selector" }
        assertEquals("true", selector.field("interrupt_exist_connections"))
    }

    @Test
    fun `proxy-only replaces the tunnel with one local port`() {
        val inbounds = TunnelConfigGenerator
            .build(input().copy(vpnInbound = false, proxyInbound = true, proxyPort = 3080))
            .jsonObject["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("mixed"), inbounds.map { it.field("type") })
        assertEquals("127.0.0.1", inbounds.single().field("listen"))
        assertEquals("3080", inbounds.single().field("listen_port"))
    }

    @Test
    fun `a proxy port with credentials asks for them, and without them does not`() {
        val guarded = TunnelConfigGenerator
            .build(
                input().copy(
                    vpnInbound = false,
                    proxyInbound = true,
                    proxyUsername = "hydrabox",
                    proxyPassword = "secret",
                ),
            )
            .jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        val user = guarded["users"]!!.jsonArray.single().jsonObject
        assertEquals("hydrabox", user.field("username"))
        assertEquals("secret", user.field("password"))
        val open = TunnelConfigGenerator
            .build(input().copy(vpnInbound = false, proxyInbound = true, proxyUsername = "hydrabox"))
            .jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        assertTrue(open["users"] == null)
    }

    @Test
    fun `both inbounds can run at once`() {
        val inbounds = TunnelConfigGenerator
            .build(input().copy(vpnInbound = true, proxyInbound = true))
            .jsonObject["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tun", "mixed"), inbounds.map { it.field("type") })
    }

    @Test
    fun `apps kept outside the tunnel reach the inbound, not the route rules`() {
        val tun = TunnelConfigGenerator
            .build(input().copy(excludePackages = listOf("com.example.bank")))
            .jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("com.example.bank", tun["exclude_package"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun `only the selected apps use the tunnel when that is the chosen mode`() {
        val tun = TunnelConfigGenerator
            .build(input().copy(includePackages = listOf("com.example.browser")))
            .jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        assertEquals("com.example.browser", tun["include_package"]!!.jsonArray.first().jsonPrimitive.content)
    }
}
