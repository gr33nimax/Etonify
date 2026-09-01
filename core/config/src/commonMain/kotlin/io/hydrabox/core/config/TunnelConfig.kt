package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Everything the generator needs that does not come from the selected outbounds. */
data class TunnelInput(
    val outbounds: List<CatalogOutbound>,
    val selectedTag: String?,
    val proxyDnsResolver: String = "https://dns.cloudflare.com/dns-query",
    val directDnsResolver: String = "1.1.1.1",
    val mtu: Int = 9000,
    val includePackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val logLevel: String = "warn",
    val urlTestUrl: String = "https://cp.cloudflare.com/generate_204",
    val urlTestIntervalSeconds: Int = 600,
    /**
     * Rejects STUN. A WebRTC handshake asks a STUN server for the real address, and the
     * answer travels outside the tunnel unless the rule below drops it.
     */
    val blockLeaks: Boolean = true,
    /** Keeps the local network reachable: the printer stays a printer while the VPN is up. */
    val bypassLocalNetwork: Boolean = true,
)

const val SELECTOR_TAG = "select"
const val DIRECT_TAG = "direct"

/**
 * The core's own latency group. It exists for two reasons: it lets the user pick
 * "fastest" instead of a named server, and it is what makes the core measure and report a
 * delay per outbound at all — a plain selector measures nothing.
 */
const val AUTO_TAG = "auto"

/**
 * Builds a complete core configuration: a tun inbound, every outbound the subscription
 * contributed — embedded exactly as it was described, so detour chains keep resolving — a
 * selector over the selectable ones, and the DNS layout the plan fixes. The proxy
 * resolver bootstraps through the local resolver and never routes application queries
 * outside the tunnel.
 */
object TunnelConfigGenerator {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun generate(input: TunnelInput): String = json.encodeToString(JsonObject.serializer(), build(input))

    fun build(input: TunnelInput): JsonObject {
        val reserved = setOf(DIRECT_TAG, SELECTOR_TAG, AUTO_TAG)
        val embedded = input.outbounds.filterNot { it.tag in reserved }
        val choices = embedded.filter(CatalogOutbound::selectable).map(CatalogOutbound::tag)
        val hasProxies = choices.isNotEmpty()
        val selected = input.selectedTag?.takeIf { it == AUTO_TAG || it in choices }
        return buildJsonObject {
            putJsonObject("log") { put("level", input.logLevel) }
            put("dns", dns(input, hasProxies))
            putJsonArray("inbounds") { add(tun(input)) }
            putJsonArray("outbounds") {
                embedded.forEach { add(it.json) }
                add(buildJsonObject { put("type", "direct"); put("tag", DIRECT_TAG) })
                if (hasProxies) {
                    add(
                        buildJsonObject {
                            put("type", "urltest")
                            put("tag", AUTO_TAG)
                            putJsonArray("outbounds") { choices.forEach { add(JsonPrimitive(it)) } }
                            put("url", input.urlTestUrl)
                            put("interval", "${input.urlTestIntervalSeconds}s")
                            put("tolerance", 50)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "selector")
                            put("tag", SELECTOR_TAG)
                            putJsonArray("outbounds") {
                                add(JsonPrimitive(AUTO_TAG))
                                choices.forEach { add(JsonPrimitive(it)) }
                            }
                            put("default", selected ?: AUTO_TAG)
                            put("interrupt_exist_connections", true)
                        },
                    )
                }
            }
            put("route", route(input, hasProxies))
        }
    }

    private fun dns(input: TunnelInput, hasProxies: Boolean) = buildJsonObject {
        putJsonArray("servers") {
            add(buildJsonObject { put("type", "local"); put("tag", "dns-local") })
            add(
                buildJsonObject {
                    put("type", "udp")
                    put("tag", "dns-direct")
                    put("server", host(input.directDnsResolver))
                    put("detour", DIRECT_TAG)
                },
            )
            if (hasProxies) {
                add(
                    buildJsonObject {
                        put("type", "https")
                        put("tag", "dns-proxy")
                        put("server", host(input.proxyDnsResolver))
                        put("detour", SELECTOR_TAG)
                        // A resolver cannot resolve its own hostname through itself.
                        put("domain_resolver", "dns-local")
                    },
                )
            }
        }
        // Before readiness the proxy resolver refuses rather than answering outside the
        // tunnel: routing queries to a direct resolver would leak them on every start.
        put("final", if (hasProxies) "dns-proxy" else "dns-direct")
    }

    private fun tun(input: TunnelInput) = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("address") { add(JsonPrimitive("172.19.0.1/30")); add(JsonPrimitive("fdfe:dcba:9876::1/126")) }
        put("mtu", input.mtu)
        put("auto_route", true)
        put("strict_route", false)
        put("stack", "mixed")
        if (input.includePackages.isNotEmpty()) {
            putJsonArray("include_package") { input.includePackages.forEach { add(JsonPrimitive(it)) } }
        }
        if (input.excludePackages.isNotEmpty()) {
            putJsonArray("exclude_package") { input.excludePackages.forEach { add(JsonPrimitive(it)) } }
        }
    }

    private fun route(input: TunnelInput, hasProxies: Boolean) = buildJsonObject {
        put("default_domain_resolver", "dns-local")
        put("auto_detect_interface", true)
        put("final", if (hasProxies) SELECTOR_TAG else DIRECT_TAG)
        putJsonArray("rules") {
            add(buildJsonObject { put("action", "sniff") })
            // A resolver reached by address rather than by protocol still has to be caught,
            // or a hardcoded 8.8.8.8 in an application escapes the tunnel's DNS entirely.
            add(
                buildJsonObject {
                    put("type", "logical")
                    put("mode", "or")
                    putJsonArray("rules") {
                        add(buildJsonObject { put("protocol", "dns") })
                        add(buildJsonObject { put("port", 53) })
                    }
                    put("action", "hijack-dns")
                },
            )
            // The tunnel's own gateway must not answer pings: it is not a host.
            add(
                buildJsonObject {
                    put("inbound", "tun-in")
                    put("network", "icmp")
                    put("ip_cidr", "172.19.0.2/32")
                    put("action", "reject")
                    put("method", "drop")
                },
            )
            if (input.blockLeaks) {
                add(buildJsonObject { put("protocol", "stun"); put("action", "reject") })
            }
            if (input.bypassLocalNetwork) {
                add(buildJsonObject { put("ip_is_private", true); put("outbound", DIRECT_TAG) })
            }
        }
    }

    private fun host(resolver: String): String = resolver
        .substringAfter("://", resolver)
        .substringBefore('/')
        .substringBefore('?')
        .takeIf(String::isNotEmpty) ?: resolver
}
