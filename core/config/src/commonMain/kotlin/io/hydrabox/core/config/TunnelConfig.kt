package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Everything the generator needs that does not come from the selected outbounds. */
data class TunnelInput(
    val outbounds: List<CatalogOutbound>,
    val selectedTag: String?,
    val proxyDnsResolver: String = "https://dns.cloudflare.com/dns-query",
    val directDnsResolver: String = "1.1.1.1",
    /**
     * What resolves a name before anything else can: the servers' own hostnames, and the
     * resolvers' hostnames. It has to be reachable on a network that allows almost nothing,
     * which is why it is not the platform's resolver by default — behind an operator white
     * list the platform's answers are the operator's.
     */
    val bootstrapDnsResolver: String = "udp://77.88.8.8",
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
    /** Rejects packets that try to leave behind the tunnel's back. 1.x: `vpn_strict_route`. */
    val strictRoute: Boolean = false,
    /** `system`, `gvisor` or `mixed`, as 1.x's `vpn_tun_implementation`. */
    val tunStack: String = "mixed",
    val tcpFastOpen: Boolean = false,
    val tcpMultiPath: Boolean = false,
    /** Off, `record` or `fragment`: how a TLS handshake is split to survive inspection. */
    val tlsFragmentation: String = "disabled",
    /** How much better another server must be before the automatic choice moves, in ms. */
    val urlTestToleranceMillis: Int = 50,
    /**
     * The automatic group's own probe budget. Null keeps the field out of the configuration,
     * which is what a core without `urltest_probe_budget` requires — it would reject the
     * whole configuration over fields it does not know, and its built-in budget applies.
     */
    val urlTestProbeTimeoutMillis: Long? = null,
    val urlTestProbeConcurrency: Int? = null,
    /** Whether switching servers tears down the connections that are already open. */
    val interruptExistingConnections: Boolean = false,
    /** The system tunnel. Off, with [proxyInbound] on, is 1.x's proxy-only mode. */
    val vpnInbound: Boolean = true,
    val proxyInbound: Boolean = false,
    val proxyListen: String = "127.0.0.1",
    val proxyPort: Int = 2080,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    /** Blocks advertising and tracking domains, when the set has been downloaded. */
    val adBlock: Boolean = false,
    /**
     * Which address family the resolver may answer with: `ipv4_only`, `ipv6_only`, or `auto`
     * to leave the field out and let the core answer with both.
     */
    val dnsStrategy: String = "ipv4_only",
    /**
     * Answers address queries from a reserved range and keeps the domain for routing. It costs
     * one fewer round trip per name and makes domain rules work for applications that only
     * ever hand the core an address.
     */
    val fakeIp: Boolean = false,
    /** The compiled rule sets available on this device. */
    val routeData: RouteData = RouteData.None,
    /**
     * Where the core serves its own profiler, or empty for not at all.
     *
     * This is `net/http/pprof` inside the process that owns the tunnel, so it hands out stacks
     * and heap contents to whoever can reach it. Empty is the only value a shipped build may
     * have, and a loopback address is the only value any build may have.
     */
    val debugListen: String = "",
)

const val ADBLOCK_BLOCK = "adblock-block"
const val ADBLOCK_ALLOW = "adblock-allow"

const val SELECTOR_TAG = "select"
const val DIRECT_TAG = "direct"

/**
 * The core's own latency group. It exists for two reasons: it lets the user pick
 * "fastest" instead of a named server, and it is what makes the core measure and report a
 * delay per outbound at all — a plain selector measures nothing.
 */
const val AUTO_TAG = "auto"

/**
 * The addresses a fake answer comes from. `198.18.0.0/15` is reserved for benchmarking and is
 * what every implementation of this trick uses, so nothing on a real network collides with it.
 */
const val FAKE_IPV4_RANGE = "198.18.0.0/15"
const val FAKE_IPV6_RANGE = "fc00::/18"

/** The resolver every other resolver and every server hostname is reached through. */
const val BOOTSTRAP_DNS_TAG = "dns-bootstrap"

/** The platform resolver, as a stored value. 1.x wrote the same marker. */
private const val PLATFORM_RESOLVER = "device://network"

/**
 * Builds a complete core configuration: a tun inbound, every outbound the subscription
 * contributed — embedded exactly as it was described, so detour chains keep resolving — a
 * selector over the selectable ones, and the DNS layout the plan fixes. The proxy
 * resolver bootstraps through the local resolver and never routes application queries
 * outside the tunnel.
 */
/** The outbound type of the VK transport, as a subscription writes it. */
private const val CALL_TYPE = "call"

/**
 * Leaves the VK transport out of the configuration unless it is the route that was chosen.
 *
 * That outbound is not passive: the core dials it at start — four calls with four workers each,
 * with TURN credentials fetched from the VK API per call — whether or not a single byte is ever
 * routed through it. Connecting to an ordinary server used to raise all of it anyway, which is
 * both the flood-control exposure and several seconds of the start budget spent on a tunnel
 * nobody asked for.
 *
 * Two things are never dropped. A tag another outbound dials through, or lists as a member of
 * its own group, is part of that outbound's configuration and removing it makes the core refuse
 * the document whole. And if dropping would leave nothing selectable at all, nothing is dropped:
 * a subscription whose only server is a VK transport must still be usable.
 */
private fun withoutIdleCallTransports(
    outbounds: List<CatalogOutbound>,
    selectedTag: String?,
): List<CatalogOutbound> {
    val calls = outbounds.filter { it.type.equals(CALL_TYPE, ignoreCase = true) }.map(CatalogOutbound::tag)
    if (calls.isEmpty()) return outbounds
    val keep = referencedTags(outbounds) + setOfNotNull(selectedTag)
    val dropped = calls.filterNot { it in keep }.toSet()
    if (dropped.isEmpty()) return outbounds
    val remaining = outbounds.filterNot { it.tag in dropped }
    return if (remaining.none(CatalogOutbound::selectable)) outbounds else remaining
}

/** Every tag the embedded documents point at: a detour, or a member of a group they carry. */
private fun referencedTags(outbounds: List<CatalogOutbound>): Set<String> = buildSet {
    outbounds.forEach { outbound ->
        (outbound.json["detour"] as? JsonPrimitive)?.contentOrNull?.let(::add)
        (outbound.json["outbounds"] as? JsonArray)?.forEach { member ->
            (member as? JsonPrimitive)?.contentOrNull?.let(::add)
        }
    }
}

/**
 * Whether switching the chosen route from one outbound to another has to restart the core.
 *
 * The configuration carries the VK transport only while it is the chosen route
 * ([withoutIdleCallTransports]), so a switch across that boundary changes what the document
 * itself contains. An in-place switch in one direction fails (the chosen route is not in the
 * running configuration), and in the other — the worse one — succeeds and parks the
 * transport's workers, calls and TURN allocations until the whole core closes, because
 * nothing removes an outbound from a running configuration.
 */
fun selectionCrossesCallBoundary(
    previous: String?,
    next: String,
    isCallTransport: (String) -> Boolean,
): Boolean = previous != null && isCallTransport(previous) != isCallTransport(next)

object TunnelConfigGenerator {
    private val json = Json { prettyPrint = false; encodeDefaults = true }

    fun generate(input: TunnelInput): String = json.encodeToString(JsonObject.serializer(), build(input))

    fun build(input: TunnelInput): JsonObject {
        // Tags are made unique before anything reads them, including the three this generator
        // writes itself. They used to be dropped instead, and a subscription whose only server
        // was called `auto` became a configuration with no proxy in it and `direct` as its final
        // route — a tunnel that reported itself connected and carried everything in the clear.
        val normalized = OutboundTags.normalize(input.outbounds)
        val selectedTag = input.selectedTag?.let { normalized.renames[it] ?: it }
        val embedded = withoutIdleCallTransports(normalized.outbounds, selectedTag)
        // The core runs WireGuard from `endpoints`; the same object in `outbounds` is not a
        // bad server but a configuration it refuses whole, which takes every other server in
        // the subscription down with it.
        val endpoints = embedded.filter(CatalogOutbound::endpoint)
        val dialable = embedded.filterNot(CatalogOutbound::endpoint)
        val choices = embedded.filter(CatalogOutbound::selectable).map(CatalogOutbound::tag)
        val hasProxies = choices.isNotEmpty()
        // Refusing to start says what is wrong; routing directly instead would send the traffic
        // a person asked to hide out through the network they were hiding it from.
        check(hasProxies || input.outbounds.none(CatalogOutbound::selectable)) {
            "the subscription offered a server and none survived; refusing to route directly"
        }
        check(embedded.distinctBy(CatalogOutbound::tag).size == embedded.size) {
            "two outbounds share a tag; the core would refuse the whole configuration"
        }
        val selected = selectedTag?.takeIf { it == AUTO_TAG || it in choices }
        return buildJsonObject {
            // "off" is a different thing from a quiet level: it turns the core's log factory
            // off, and that is the only way to stop it formatting lines it will not write.
            putJsonObject("log") {
                if (input.logLevel == "off") put("disabled", true) else put("level", input.logLevel)
            }
            put("dns", dns(input, hasProxies))
            putJsonArray("inbounds") {
                if (input.vpnInbound) add(tun(input))
                if (input.proxyInbound) add(mixed(input))
            }
            if (endpoints.isNotEmpty()) {
                putJsonArray("endpoints") { endpoints.forEach { add(it.json) } }
            }
            putJsonArray("outbounds") {
                dialable.forEach { add(dialOptions(it.json, input)) }
                add(
                    buildJsonObject {
                        put("type", "direct")
                        put("tag", DIRECT_TAG)
                        if (input.tcpFastOpen) put("tcp_fast_open", true)
                        if (input.tcpMultiPath) put("tcp_multi_path", true)
                    },
                )
                if (hasProxies) {
                    add(
                        buildJsonObject {
                            put("type", "urltest")
                            put("tag", AUTO_TAG)
                            putJsonArray("outbounds") { choices.forEach { add(JsonPrimitive(it)) } }
                            put("url", input.urlTestUrl)
                            put("interval", "${input.urlTestIntervalSeconds}s")
                            put("idle_timeout", "${input.urlTestIntervalSeconds}s")
                            put("tolerance", input.urlTestToleranceMillis)
                            input.urlTestProbeTimeoutMillis?.let { put("probe_timeout", "${it}ms") }
                            input.urlTestProbeConcurrency?.let { put("probe_concurrency", it) }
                            put("interrupt_exist_connections", false)
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
                            put("interrupt_exist_connections", input.interruptExistingConnections)
                        },
                    )
                }
            }
            put("route", route(input, hasProxies))
            // Without a cache file the core re-measures every server and re-learns every
            // rejected domain on each start, which is a slow first minute after every
            // connect. 1.x ships `store_rdrc`; the alpha shipped no `experimental` at all.
            putJsonObject("experimental") {
                putJsonObject("cache_file") {
                    put("enabled", true)
                    put("store_rdrc", true)
                    // A fake address that changes on every start is a stale mapping in every
                    // application that cached it, so the table outlives the process.
                    if (input.fakeIp) put("store_fakeip", true)
                }
                // The core's own profiler, when a build asked for it. It is the only way to say
                // where the transport spends its cycles instead of guessing, and it is a loopback
                // address in a debug build or nothing at all.
                if (input.debugListen.isNotEmpty()) {
                    putJsonObject("debug") { put("listen", input.debugListen) }
                }
            }
        }
    }

    private fun dns(input: TunnelInput, hasProxies: Boolean) = buildJsonObject {
        putJsonArray("servers") {
            add(buildJsonObject { put("type", "local"); put("tag", "dns-local") })
            add(resolverServer(BOOTSTRAP_DNS_TAG, input.bootstrapDnsResolver, DIRECT_TAG))
            add(resolverServer("dns-direct", input.directDnsResolver, DIRECT_TAG))
            if (hasProxies) {
                add(resolverServer("dns-proxy", input.proxyDnsResolver, SELECTOR_TAG))
            }
            if (input.fakeIp) {
                add(
                    buildJsonObject {
                        put("type", "fakeip")
                        put("tag", "dns-fake")
                        put("inet4_range", FAKE_IPV4_RANGE)
                        put("inet6_range", FAKE_IPV6_RANGE)
                    },
                )
            }
        }
        // An application asking for an address gets one immediately, and the domain it asked
        // for survives into routing — the core maps the fake address back before it dials.
        // Only address queries are answered this way; everything else keeps its resolver, and
        // the bootstrap paths (`domain_resolver`, `default_domain_resolver`) are not routed
        // through these rules at all.
        if (input.fakeIp) {
            putJsonArray("rules") {
                add(
                    buildJsonObject {
                        putJsonArray("query_type") { add(JsonPrimitive("A")); add(JsonPrimitive("AAAA")) }
                        put("server", "dns-fake")
                    },
                )
            }
        }
        // Before readiness the proxy resolver refuses rather than answering outside the
        // tunnel: routing queries to a direct resolver would leak them on every start.
        put("final", if (hasProxies) "dns-proxy" else "dns-direct")
        // Android applications ask for AAAA on their own, and an IPv6 answer through an exit
        // with no working IPv6 route is a site that never loads while the tunnel says it is
        // connected. 1.x defaults to an IPv4-only answer set for exactly this reason
        // (`singbox_config_builder.dart`), and the alpha left the strategy unset. It is a
        // choice now, and "auto" is expressed by leaving the field out rather than by naming
        // a strategy the core does not have.
        if (input.dnsStrategy != "auto") put("strategy", input.dnsStrategy)
        put("independent_cache", true)
        put("cache_capacity", 4096)
    }

    /**
     * Dial and TLS options a person chose, applied to every server the subscription
     * contributed. 1.x did the same in `_applyTlsFragmentation` and its dial override, and
     * only for the types that accept those fields — a `selector` has no socket to tune.
     */
    private fun dialOptions(outbound: JsonObject, input: TunnelInput): JsonObject {
        val type = outbound["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type !in dialCapableTypes) return outbound
        val fragmented = fragmentation(outbound["tls"] as? JsonObject, input.tlsFragmentation)
        if (fragmented == null && !input.tcpFastOpen && !input.tcpMultiPath) return outbound
        return buildJsonObject {
            outbound.forEach { (key, value) -> if (key != "tls") put(key, value) }
            (fragmented ?: outbound["tls"])?.let { put("tls", it) }
            if (input.tcpFastOpen) put("tcp_fast_open", true)
            if (input.tcpMultiPath) put("tcp_multi_path", true)
        }
    }

    /** Only a handshake that is actually TLS can be fragmented. */
    private fun fragmentation(tls: JsonObject?, mode: String): JsonObject? {
        if (tls == null || mode == "disabled") return null
        if (tls["enabled"]?.jsonPrimitive?.contentOrNull != "true") return null
        return buildJsonObject {
            tls.forEach { (key, value) ->
                if (key !in setOf("fragment", "fragment_fallback_delay", "record_fragment")) put(key, value)
            }
            when (mode) {
                "record" -> put("record_fragment", true)
                "fragment" -> { put("fragment", true); put("fragment_fallback_delay", "300ms") }
            }
        }
    }

    private val dialCapableTypes = setOf(
        "socks", "http", "shadowsocks", "vmess", "trojan", "naive",
        "hysteria", "hysteria2", "tuic", "anytls", "vless", "mieru", "shadowtls", "ssh",
    )

    private fun tun(input: TunnelInput) = buildJsonObject {
        put("type", "tun")
        put("tag", "tun-in")
        putJsonArray("address") { add(JsonPrimitive("172.19.0.1/30")); add(JsonPrimitive("fdfe:dcba:9876::1/126")) }
        put("mtu", input.mtu)
        put("auto_route", true)
        put("strict_route", input.strictRoute)
        put("stack", input.tunStack)
        if (input.includePackages.isNotEmpty()) {
            putJsonArray("include_package") { input.includePackages.forEach { add(JsonPrimitive(it)) } }
        }
        if (input.excludePackages.isNotEmpty()) {
            putJsonArray("exclude_package") { input.excludePackages.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /**
     * The local proxy port, which is the whole of 1.x's proxy-only mode: no system tunnel,
     * one `mixed` inbound, and applications pointed at it by hand. Credentials are included
     * when there are any, because a port that answers the whole machine without one is a
     * hole in it.
     */
    private fun mixed(input: TunnelInput) = buildJsonObject {
        put("type", "mixed")
        put("tag", "mixed-in")
        put("listen", input.proxyListen)
        put("listen_port", input.proxyPort)
        val user = input.proxyUsername?.takeIf(String::isNotEmpty)
        val password = input.proxyPassword?.takeIf(String::isNotEmpty)
        if (user != null && password != null) {
            putJsonArray("users") {
                add(buildJsonObject { put("username", user); put("password", password) })
            }
        }
    }

    /** True only when the person asked for blocking and the compiled set is on disk. */
    private fun adBlockActive(input: TunnelInput) = input.adBlock && input.routeData.adBlockAvailable

    private fun route(input: TunnelInput, hasProxies: Boolean) = buildJsonObject {
        // What resolves a name when an outbound has to dial one — the server's own hostname
        // above all. It was `dns-local`, the platform resolver, which means the network's own:
        // every direct-routed name was asked of the provider, and behind an operator white
        // list the provider is also the only one who answers, with whatever it likes.
        //
        // It is the bootstrap resolver instead: one address, chosen for being reachable when
        // little else is. It cannot be the tunnel's resolver — that one is reached through the
        // proxy, and the proxy's own hostname is resolved by this very setting, so pointing it
        // at the tunnel would make the first dial wait for itself.
        put("default_domain_resolver", BOOTSTRAP_DNS_TAG)
        put("auto_detect_interface", true)
        put("final", if (hasProxies) SELECTOR_TAG else DIRECT_TAG)
        if (adBlockActive(input)) {
            putJsonArray("rule_set") {
                add(
                    buildJsonObject {
                        put("type", "local"); put("tag", ADBLOCK_BLOCK)
                        put("format", "binary"); put("path", input.routeData.adBlockPath!!)
                    },
                )
                input.routeData.adBlockAllowPath?.let { path ->
                    add(
                        buildJsonObject {
                            put("type", "local"); put("tag", ADBLOCK_ALLOW)
                            put("format", "binary"); put("path", path)
                        },
                    )
                }
            }
        }
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
            // The allow list comes first, as in 1.x: an exception has to win over the block.
            if (adBlockActive(input)) {
                input.routeData.adBlockAllowPath?.let {
                    add(
                        buildJsonObject {
                            put("rule_set", ADBLOCK_ALLOW)
                            put("outbound", if (hasProxies) SELECTOR_TAG else DIRECT_TAG)
                        },
                    )
                }
                add(buildJsonObject { put("rule_set", ADBLOCK_BLOCK); put("action", "reject") })
            }
        }
    }

    /**
     * One stored resolver as a server the core understands, with its protocol kept.
     *
     * The scheme is not decoration. A `udp://` resolver is answered by whoever wants to answer:
     * a router with its own DNS redirects port 53 and replies in place of the address that was
     * asked, which is why a leak test kept naming the router's resolver even after the app
     * stopped using the platform's. `https://` is a TLS session with the resolver itself, and
     * nothing between can answer for it.
     *
     * A resolver named by hostname has to have that name resolved by something else, and the
     * platform is the only thing that can do it before the tunnel exists. A resolver named by
     * address — including `https://1.1.1.1/dns-query` — needs nothing.
     *
     * Everything here is checked rather than assumed. The parse used to cut the authority at the
     * first colon, which turned `2001:4860:4860::8888` into a resolver at `2001`; it mapped every
     * scheme it did not know to `udp`, so a `quic://` resolver silently became a plaintext one on
     * port 53; and it dropped the query of a DoH address, which for a provider that keys on it is
     * a resolver that refuses. All three looked like a value that had been saved successfully.
     */
    private fun resolverServer(tag: String, resolver: String, detour: String) = buildJsonObject {
        val trimmed = resolver.trim()
        if (trimmed.lowercase() == PLATFORM_RESOLVER) {
            put("type", "local")
            put("tag", tag)
            return@buildJsonObject
        }
        val address = parseResolver(trimmed)
        put("type", address.type)
        put("tag", tag)
        put("server", address.server)
        address.port?.let { put("server_port", it) }
        address.path?.let { put("path", it) }
        address.query?.let {
            put("query", it)
            put("force_query", true)
        }
        put("detour", detour)
        // A resolver named by hostname needs something else to resolve that name, and it must
        // not be itself. The bootstrap resolver does it; the bootstrap resolver itself, if a
        // person named it by hostname, falls back to the platform.
        if (!isAddress(address.server)) {
            put("domain_resolver", if (tag == BOOTSTRAP_DNS_TAG) "dns-local" else BOOTSTRAP_DNS_TAG)
        }
    }

    private data class ResolverAddress(
        val type: String,
        val server: String,
        val port: Int?,
        val path: String?,
        val query: String?,
    )

    /**
     * The DNS server types the core has. Anything else is refused rather than mapped onto `udp`:
     * a resolver that was asked for over QUIC and is reached over plaintext port 53 is not the
     * resolver that was chosen.
     */
    private val resolverSchemes = setOf("udp", "tcp", "tls", "https", "quic", "h3")

    private fun parseResolver(resolver: String): ResolverAddress {
        val scheme = if ("://" in resolver) resolver.substringBefore("://").lowercase() else "udp"
        require(scheme in resolverSchemes) { "unsupported DNS scheme: $scheme" }
        val rest = if ("://" in resolver) resolver.substringAfter("://") else resolver
        val authority = rest.substringBefore('/').substringBefore('?')
        val tail = rest.removePrefix(authority)
        require('#' !in tail) { "a DNS URI fragment cannot be carried into the configuration" }
        require(!hasInvalidPercentEncoding(tail)) { "a DNS URI has an invalid percent escape" }
        val (server, port) = splitAuthority(authority)
        require(server.isNotEmpty()) { "a DNS resolver needs a host" }
        require(port == null || port in 1..65_535) { "a DNS port must be between 1 and 65535" }
        val encrypted = scheme == "https" || scheme == "h3"
        require(encrypted || tail.isEmpty()) { "only a DoH resolver may carry a path or query" }
        val queryStart = tail.indexOf('?')
        val rawPath = if (queryStart < 0) tail else tail.substring(0, queryStart)
        require(rawPath.isEmpty() || rawPath.startsWith('/')) { "a DNS URI path must start with /" }
        val path = if (encrypted) rawPath.ifEmpty { "/" } else null
        val query = queryStart.takeIf { encrypted && it >= 0 }?.let { tail.substring(it + 1) }
        return ResolverAddress(type = scheme, server = server, port = port, path = path, query = query)
    }

    /**
     * A host and its port, where the host may be an IPv6 address. Both shapes a person writes are
     * accepted: bracketed with a port, and bare — an address whose colons are its own and not a
     * port separator, which is why it cannot be cut at the first one.
     */
    private fun splitAuthority(authority: String): Pair<String, Int?> {
        if (authority.startsWith("[")) {
            val host = authority.substringAfter('[').substringBefore(']')
            val port = authority.substringAfter("]:", "").takeIf(String::isNotEmpty)
            require(port == null || port.toIntOrNull() != null) { "a DNS port must be a number" }
            return host to port?.toIntOrNull()
        }
        if (authority.count { it == ':' } > 1) return authority to null
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").takeIf(String::isNotEmpty)
        require(port == null || port.toIntOrNull() != null) { "a DNS port must be a number" }
        return host to port?.toIntOrNull()
    }

    private fun isAddress(value: String) = ':' in value || (value.isNotEmpty() && value.all { it.isDigit() || it == '.' })

    private fun hasInvalidPercentEncoding(value: String): Boolean = value.indices.any { index ->
        value[index] == '%' && (index + 2 >= value.length || value[index + 1].digitToIntOrNull(16) == null || value[index + 2].digitToIntOrNull(16) == null)
    }
}
