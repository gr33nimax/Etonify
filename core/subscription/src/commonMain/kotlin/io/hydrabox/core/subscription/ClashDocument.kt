package io.hydrabox.core.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A Clash subscription, as the core needs it.
 *
 * Clash names the same things differently — `cipher` for a method, `servername` for an SNI,
 * `skip-cert-verify` for insecure — so this is a translation, not a projection. The mapping
 * follows HydraBox 1.x (`lib/data/subscription/parsers/clash_parser.dart`) type by type,
 * because a provider's config that worked in 1.x has to keep working.
 */
internal object ClashDocument {
    fun outbounds(document: String): List<CatalogOutbound>? {
        if (!document.lineSequence().any { it.trimStart().startsWith("proxies:") }) return null
        val root = runCatching { MiniYaml.parse(document) }.getOrNull() ?: return null
        val proxies = root.entries["proxies"].sequence().ifEmpty { return null }
        val seen = mutableSetOf<String>()
        val collected = proxies.mapIndexedNotNull { index, node ->
            val proxy = node.mapping().ifEmpty { return@mapIndexedNotNull null }
            val server = proxy["server"].scalar() ?: return@mapIndexedNotNull null
            val port = proxy["port"].scalar()?.toIntOrNull() ?: return@mapIndexedNotNull null
            val type = proxy["type"].scalar()?.lowercase() ?: return@mapIndexedNotNull null
            val name = proxy["name"].scalar()?.takeIf(String::isNotEmpty) ?: "$server-$port-$index"
            val tag = if (seen.add(name)) name else "$name-$index".also { seen.add(it) }
            outbound(type, tag, server, port, proxy)?.let {
                CatalogOutbound(
                    tag = tag,
                    type = coreType(type),
                    json = it,
                    endpoint = coreType(type) == "wireguard",
                )
            }
        }
        return collected.takeIf(List<CatalogOutbound>::isNotEmpty)
    }

    /** Clash spells three of the types differently from the core. */
    private fun coreType(type: String) = when (type) {
        "ss" -> "shadowsocks"
        "ssr" -> "shadowsocksr"
        "socks5" -> "socks"
        else -> type
    }

    private fun outbound(
        type: String,
        tag: String,
        server: String,
        port: Int,
        proxy: Map<String, YamlNode>,
    ): JsonObject? {
        val core = coreType(type)
        if (core !in supported) return null
        // WireGuard is an endpoint in the core the app ships, and an endpoint has a shape of
        // its own: local addresses on the interface, the remote peer in a list. The outbound
        // shape Clash implies (`peer_public_key`, `local_address`) is the pre-1.11 one and
        // the core no longer registers it.
        if (core == "wireguard") return wireguardEndpoint(tag, server, port, proxy)
        return buildJsonObject {
            put("type", core)
            put("tag", tag)
            put("server", server)
            put("server_port", port)
            when (core) {
                "vmess" -> {
                    proxy["uuid"].scalar()?.let { put("uuid", it) }
                    proxy["alterId"].scalar()?.toIntOrNull()?.let { put("alter_id", it) }
                    proxy["cipher"].scalar()?.let { put("security", it) }
                }
                "vless" -> {
                    proxy["uuid"].scalar()?.let { put("uuid", it) }
                    proxy["flow"].scalar()?.let { put("flow", it) }
                }
                "trojan", "anytls" -> proxy["password"].scalar()?.let { put("password", it) }
                "shadowsocks" -> {
                    proxy["cipher"].scalar()?.let { put("method", it) }
                    proxy["password"].scalar()?.let { put("password", it) }
                    plugin(proxy)?.let { (name, options) -> put("plugin", name); put("plugin_opts", options) }
                }
                "shadowsocksr" -> {
                    proxy["cipher"].scalar()?.let { put("method", it) }
                    proxy["password"].scalar()?.let { put("password", it) }
                    proxy["protocol"].scalar()?.let { put("protocol", it) }
                    proxy["protocol-param"].scalar()?.let { put("protocol_param", it) }
                    proxy["obfs"].scalar()?.let { put("obfs", it) }
                    proxy["obfs-param"].scalar()?.let { put("obfs_param", it) }
                }
                "hysteria2" -> {
                    (proxy["password"].scalar() ?: proxy["auth"].scalar())?.let { put("password", it) }
                    proxy["obfs"].scalar()?.let { name ->
                        putJsonObject("obfs") {
                            put("type", name)
                            proxy["obfs-password"].scalar()?.let { put("password", it) }
                        }
                    }
                    proxy["up"].scalar()?.let { put("up_mbps", it.filter(Char::isDigit).toIntOrNull() ?: 0) }
                    proxy["down"].scalar()?.let { put("down_mbps", it.filter(Char::isDigit).toIntOrNull() ?: 0) }
                }
                "hysteria" -> {
                    (proxy["auth-str"].scalar() ?: proxy["auth_str"].scalar())?.let { put("auth_str", it) }
                    proxy["up"].scalar()?.let { put("up", it) }
                    proxy["down"].scalar()?.let { put("down", it) }
                    proxy["obfs"].scalar()?.let { put("obfs", it) }
                }
                "tuic" -> {
                    proxy["uuid"].scalar()?.let { put("uuid", it) }
                    proxy["password"].scalar()?.let { put("password", it) }
                    proxy["congestion-controller"].scalar()?.let { put("congestion_control", it) }
                    proxy["udp-relay-mode"].scalar()?.let { put("udp_relay_mode", it) }
                }
                "socks", "http" -> {
                    proxy["username"].scalar()?.let { put("username", it) }
                    proxy["password"].scalar()?.let { put("password", it) }
                }
            }
            if (core != "wireguard") {
                transport(proxy)?.let { put("transport", it) }
                tls(proxy, core, server)?.let { put("tls", it) }
            }
        }
    }

    private fun wireguardEndpoint(
        tag: String,
        server: String,
        port: Int,
        proxy: Map<String, YamlNode>,
    ): JsonObject = buildJsonObject {
        put("type", "wireguard")
        put("tag", tag)
        putJsonArray("address") {
            val addresses = listOfNotNull(proxy["ip"].scalar(), proxy["ipv6"].scalar())
                .map { if (it.contains('/')) it else if (it.contains(':')) "$it/128" else "$it/32" }
            addresses.ifEmpty { listOf("172.16.0.2/32") }.forEach { add(JsonPrimitive(it)) }
        }
        proxy["private-key"].scalar()?.let { put("private_key", it) }
        proxy["mtu"].scalar()?.toIntOrNull()?.let { put("mtu", it) }
        putJsonArray("peers") {
            add(
                buildJsonObject {
                    put("address", server)
                    put("port", port)
                    proxy["public-key"].scalar()?.let { put("public_key", it) }
                    proxy["pre-shared-key"].scalar()?.let { put("pre_shared_key", it) }
                    putJsonArray("allowed_ips") {
                        val allowed = proxy["allowed-ips"].sequence().mapNotNull { it.scalar() }
                        allowed.ifEmpty { listOf("0.0.0.0/0", "::/0") }.forEach { add(JsonPrimitive(it)) }
                    }
                },
            )
        }
    }

    /** Shadowsocks obfuscation, which Clash carries as a plugin with an options string. */
    private fun plugin(proxy: Map<String, YamlNode>): Pair<String, String>? {
        val name = proxy["plugin"].scalar() ?: return null
        val options = proxy["plugin-opts"].mapping()
            .mapNotNull { (key, value) -> value.scalar()?.let { "$key=$it" } }
            .joinToString(";")
        return (if (name == "obfs") "obfs-local" else name) to options
    }

    private fun transport(proxy: Map<String, YamlNode>): JsonObject? {
        val network = proxy["network"].scalar()?.lowercase() ?: return null
        val options = proxy["$network-opts"].mapping()
        return when (network) {
            "ws" -> buildJsonObject {
                put("type", "ws")
                options["path"].scalar()?.let { put("path", it) }
                (options["headers"].mapping()["Host"] ?: options["headers"].mapping()["host"])
                    .scalar()?.let { host -> putJsonObject("headers") { put("Host", host) } }
                options["max-early-data"].scalar()?.toIntOrNull()?.let { put("max_early_data", it) }
                options["early-data-header-name"].scalar()?.let { put("early_data_header_name", it) }
            }
            "grpc" -> buildJsonObject {
                put("type", "grpc")
                options["grpc-service-name"].scalar()?.let { put("service_name", it) }
            }
            "h2", "http" -> buildJsonObject {
                put("type", "http")
                options["host"].sequence().mapNotNull { it.scalar() }.takeIf { it.isNotEmpty() }
                    ?.let { hosts -> putJsonArray("host") { hosts.forEach { add(JsonPrimitive(it)) } } }
                options["path"].scalar()?.let { put("path", it) }
            }
            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                options["path"].scalar()?.let { put("path", it) }
                options["host"].scalar()?.let { put("host", it) }
            }
            else -> null
        }
    }

    /** Some types are TLS by definition; the rest say so with a flag. */
    private fun tls(proxy: Map<String, YamlNode>, core: String, server: String): JsonObject? {
        val implied = core in setOf("trojan", "hysteria", "hysteria2", "tuic", "anytls")
        if (!implied && !proxy["tls"].flag()) return null
        return buildJsonObject {
            put("enabled", true)
            put("server_name", proxy["servername"].scalar() ?: proxy["sni"].scalar() ?: server)
            if (proxy["skip-cert-verify"].flag()) put("insecure", true)
            proxy["alpn"].sequence().mapNotNull { it.scalar() }.takeIf { it.isNotEmpty() }
                ?.let { values -> putJsonArray("alpn") { values.forEach { add(JsonPrimitive(it)) } } }
            proxy["client-fingerprint"].scalar()
                ?.let { putJsonObject("utls") { put("enabled", true); put("fingerprint", it) } }
            proxy["reality-opts"].mapping().takeIf { it.isNotEmpty() }?.let { reality ->
                putJsonObject("reality") {
                    put("enabled", true)
                    reality["public-key"].scalar()?.let { put("public_key", it) }
                    reality["short-id"].scalar()?.let { put("short_id", it) }
                }
            }
        }
    }

    private val supported = setOf(
        "vmess", "vless", "trojan", "shadowsocks", "shadowsocksr",
        "hysteria", "hysteria2", "tuic", "anytls", "socks", "http", "wireguard",
    )
}
