package io.hydrabox.core.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * An Xray or V2Ray configuration, as the core needs it.
 *
 * Xray keeps the server inside `settings.vnext` or `settings.servers` and the transport in
 * `streamSettings`, with camel-cased keys; the core wants one flat outbound with
 * snake_case. The mapping follows HydraBox 1.x
 * (`lib/data/subscription/parsers/xray_config_parser.dart`).
 */
internal object XrayDocument {
    fun outbounds(root: JsonElement): List<CatalogOutbound>? {
        val document = root as? JsonObject ?: return null
        val outbounds = document["outbounds"] as? JsonArray ?: return null
        // Xray marks a server with `protocol`; sing-box uses `type`. Anything with a type
        // has already been claimed by the sing-box branch.
        if (outbounds.none { (it as? JsonObject)?.containsKey("protocol") == true }) return null
        val seen = mutableSetOf<String>()
        val collected = outbounds.mapIndexedNotNull { index, value ->
            val outbound = value as? JsonObject ?: return@mapIndexedNotNull null
            val protocol = outbound.text("protocol")?.lowercase() ?: return@mapIndexedNotNull null
            if (protocol in ignored) return@mapIndexedNotNull null
            val settings = outbound["settings"] as? JsonObject
            val peer = peer(protocol, settings) ?: return@mapIndexedNotNull null
            val name = outbound.text("tag")?.takeIf(String::isNotEmpty)
                ?: "${peer.server}-${peer.port}-$index"
            val tag = if (seen.add(name)) name else "$name-$index".also { seen.add(it) }
            CatalogOutbound(tag, coreType(protocol), json(protocol, tag, peer, outbound))
        }
        return collected.takeIf(List<CatalogOutbound>::isNotEmpty)
    }

    private data class Peer(
        val server: String,
        val port: Int,
        val id: String? = null,
        val password: String? = null,
        val method: String? = null,
        val flow: String? = null,
        val user: String? = null,
    )

    private fun coreType(protocol: String) = when (protocol) {
        "socks" -> "socks"
        else -> protocol
    }

    private fun peer(protocol: String, settings: JsonObject?): Peer? {
        settings ?: return null
        return when (protocol) {
            "vmess", "vless" -> {
                val node = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                val user = (node["users"] as? JsonArray)?.firstOrNull() as? JsonObject
                Peer(
                    server = node.text("address") ?: return null,
                    port = node.number("port") ?: return null,
                    id = user?.text("id"),
                    flow = user?.text("flow"),
                )
            }
            "trojan", "shadowsocks", "socks", "http" -> {
                val node = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
                val user = (node["users"] as? JsonArray)?.firstOrNull() as? JsonObject
                Peer(
                    server = node.text("address") ?: return null,
                    port = node.number("port") ?: return null,
                    password = node.text("password") ?: user?.text("pass"),
                    method = node.text("method"),
                    user = node.text("user") ?: user?.text("user"),
                )
            }
            else -> null
        }
    }

    private fun json(protocol: String, tag: String, peer: Peer, outbound: JsonObject) = buildJsonObject {
        put("type", coreType(protocol))
        put("tag", tag)
        put("server", peer.server)
        put("server_port", peer.port)
        when (protocol) {
            "vmess", "vless" -> {
                peer.id?.let { put("uuid", it) }
                peer.flow?.takeIf(String::isNotEmpty)?.let { put("flow", it) }
            }
            "trojan" -> peer.password?.let { put("password", it) }
            "shadowsocks" -> {
                peer.method?.let { put("method", it) }
                peer.password?.let { put("password", it) }
            }
            "socks", "http" -> {
                peer.user?.let { put("username", it) }
                peer.password?.let { put("password", it) }
            }
        }
        val stream = outbound["streamSettings"] as? JsonObject
        transport(stream)?.let { put("transport", it) }
        tls(stream, peer.server)?.let { put("tls", it) }
    }

    private fun transport(stream: JsonObject?): JsonObject? = when (stream?.text("network")?.lowercase()) {
        "ws" -> buildJsonObject {
            put("type", "ws")
            val options = stream["wsSettings"] as? JsonObject
            options?.text("path")?.let { put("path", it) }
            ((options?.get("headers") as? JsonObject)?.text("Host") ?: options?.text("host"))
                ?.let { host -> putJsonObject("headers") { put("Host", host) } }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            (stream["grpcSettings"] as? JsonObject)?.text("serviceName")?.let { put("service_name", it) }
        }
        "h2", "http" -> buildJsonObject {
            put("type", "http")
            val options = stream["httpSettings"] as? JsonObject
            (options?.get("host") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.let { hosts -> putJsonArray("host") { hosts.forEach { add(JsonPrimitive(it)) } } }
            options?.text("path")?.let { put("path", it) }
        }
        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            val options = stream["httpupgradeSettings"] as? JsonObject
            options?.text("path")?.let { put("path", it) }
            options?.text("host")?.let { put("host", it) }
        }
        else -> null
    }

    private fun tls(stream: JsonObject?, server: String): JsonObject? {
        val security = stream?.text("security")?.lowercase() ?: return null
        if (security !in setOf("tls", "reality", "xtls")) return null
        val settings = (stream["tlsSettings"] ?: stream["realitySettings"]) as? JsonObject
        return buildJsonObject {
            put("enabled", true)
            put("server_name", settings?.text("serverName")?.takeIf(String::isNotEmpty) ?: server)
            if (settings?.get("allowInsecure")?.jsonPrimitive?.contentOrNull == "true") put("insecure", true)
            (settings?.get("alpn") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.let { values -> putJsonArray("alpn") { values.forEach { add(JsonPrimitive(it)) } } }
            settings?.text("fingerprint")
                ?.let { putJsonObject("utls") { put("enabled", true); put("fingerprint", it) } }
            if (security == "reality") {
                putJsonObject("reality") {
                    put("enabled", true)
                    settings?.text("publicKey")?.let { put("public_key", it) }
                    settings?.text("shortId")?.let { put("short_id", it) }
                }
            }
        }
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()
    private fun JsonObject.number(key: String): Int? = this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    /** Routing outbounds, not servers. */
    private val ignored = setOf("freedom", "blackhole", "dns", "loopback")
}
