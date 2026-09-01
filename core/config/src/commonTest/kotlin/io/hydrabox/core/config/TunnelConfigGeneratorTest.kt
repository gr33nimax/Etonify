package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
