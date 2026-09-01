package io.hydrabox.core.subscription

import io.hydrabox.core.diagnostics.Secret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed interface ShareLink {
    val server: String
    val port: Int
    val name: String

    /**
     * Transport parameters carried by the link query string: security, sni, flow, type,
     * path, host, alpn and friends. Kept as given so config generation can honour them;
     * a link without a query yields an empty map.
     */
    val query: Map<String, String>

    data class Vless(override val server: String, override val port: Int, override val name: String, val uuid: Secret, override val query: Map<String, String> = emptyMap()) : ShareLink
    data class Trojan(override val server: String, override val port: Int, override val name: String, val password: Secret, override val query: Map<String, String> = emptyMap()) : ShareLink
    data class Proxy(override val server: String, override val port: Int, override val name: String, val type: String, val tls: Boolean, val username: Secret?, val password: Secret?, override val query: Map<String, String> = emptyMap()) : ShareLink
    data class WireGuard(override val server: String, override val port: Int, override val name: String, val privateKey: Secret, val peerPublicKey: Secret, override val query: Map<String, String> = emptyMap()) : ShareLink
}

enum class SubscriptionDocumentFormat { SINGBOX, XRAY, CLASH, SIP008, HYDRA, UNKNOWN }

object SubscriptionParser {
    private val link = Regex("^([A-Za-z0-9+]+)://(?:([^@/?#]+)@)?([^:/?#]+):(\\d+)(?:\\?([^#]*))?(?:#(.*))?$")

    fun parse(value: String): ShareLink {
        if (value.trimStart().startsWith("[Interface]")) return parseWireGuard(value)
        if (value.startsWith("vmess://", ignoreCase = true)) return parseVmess(value)
        if (value.startsWith("ssr://", ignoreCase = true)) return parseSsr(value)
        val match = link.matchEntire(value.trim()) ?: error("invalid subscription link")
        val scheme = match.groupValues[1].lowercase()
        val credential = decode(match.groupValues[2])
        val server = decode(match.groupValues[3]).takeIf(String::isNotEmpty) ?: error("missing link server")
        val port = match.groupValues[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid link port")
        val query = parseQuery(match.groupValues[5])
        val name = decode(match.groupValues[6])
        return when (scheme) {
            "vless" -> ShareLink.Vless(server, port, name, Secret.of(credential.takeIf(String::isNotEmpty) ?: error("missing link credential")), query)
            "trojan" -> ShareLink.Trojan(server, port, name, Secret.of(credential.takeIf(String::isNotEmpty) ?: error("missing link credential")), query)
            "socks", "socks4", "socks4a", "socks5", "socks5h", "http", "https", "ss", "hysteria", "hy", "hy2", "hysteria2", "naive+https", "naive+quic", "tuic", "anytls" -> proxy(scheme, server, port, name, credential, query)
            else -> error("unsupported subscription link")
        }
    }

    private fun parseWireGuard(content: String): ShareLink.WireGuard {
        var section = ""
        val values = mutableMapOf<String, String>()
        content.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            if (line.startsWith("[") && line.endsWith("]")) section = line.removeSurrounding("[", "]")
            else line.split('=', limit = 2).takeIf { it.size == 2 }?.let { values["$section.${it[0].trim()}"] = it[1].trim() }
        }
        val endpoint = values["Peer.Endpoint"] ?: error("missing WireGuard endpoint")
        val divider = endpoint.lastIndexOf(':').takeIf { it > 0 } ?: error("invalid WireGuard endpoint")
        val server = endpoint.substring(0, divider).removeSurrounding("[", "]")
        val port = endpoint.substring(divider + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid WireGuard port")
        return ShareLink.WireGuard(server, port, "WireGuard", Secret.of(values["Interface.PrivateKey"] ?: error("missing WireGuard private key")), Secret.of(values["Peer.PublicKey"] ?: error("missing WireGuard peer key")))
    }

    private fun parseVmess(value: String): ShareLink.Proxy {
        val document = decodeBase64(value.substringAfter("://").substringBefore('#')) ?: error("invalid VMess link")
        val fields = Json.parseToJsonElement(document) as? JsonObject ?: error("invalid VMess link")
        val server = fields["add"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) ?: error("missing VMess server")
        val port = fields["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid VMess port")
        val uuid = fields["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) ?: error("missing VMess credential")
        val name = fields["ps"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ShareLink.Proxy(server, port, name, "vmess", fields["tls"]?.jsonPrimitive?.contentOrNull == "tls", Secret.of(uuid), null)
    }

    private fun parseSsr(value: String): ShareLink.Proxy {
        val document = decodeBase64(value.substringAfter("://").substringBefore('#')) ?: error("invalid SSR link")
        val fields = document.substringBefore("/?").split(':')
        if (fields.size < 6) error("invalid SSR link")
        val port = fields[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid SSR port")
        val password = decodeBase64(fields.last()) ?: error("invalid SSR credential")
        return ShareLink.Proxy(fields[0], port, "", "shadowsocksr", false, Secret.of(fields[3]), Secret.of(password))
    }

    fun parseAll(content: String): List<ShareLink> = content
        .replace(" -> ", "\n")
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::parse)
        .toList()

    private fun proxy(scheme: String, server: String, port: Int, name: String, credential: String, query: Map<String, String>): ShareLink.Proxy {
        val parts = credential.split(':', limit = 2)
        val username = parts.firstOrNull()?.takeIf(String::isNotEmpty)?.let(Secret::of)
        val password = parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.let(Secret::of)
        val type = when {
            scheme.startsWith("socks") -> "socks"
            scheme == "ss" -> "shadowsocks"
            scheme == "hy2" || scheme == "hysteria2" -> "hysteria2"
            scheme == "hysteria" || scheme == "hy" -> "hysteria"
            scheme.startsWith("naive+") -> "naive"
            scheme == "tuic" -> "tuic"
            scheme == "anytls" -> "anytls"
            else -> "http"
        }
        val secured = scheme == "https" || scheme == "naive+https" ||
            query["security"] == "tls" || query["tls"] == "1" ||
            type == "hysteria2" || type == "hysteria" || type == "tuic" || type == "anytls"
        return ShareLink.Proxy(server, port, name, type, secured, username, password, query)
    }

    private fun parseQuery(raw: String): Map<String, String> = raw
        .split('&')
        .asSequence()
        .filter(String::isNotEmpty)
        .mapNotNull { pair ->
            val divider = pair.indexOf('=')
            if (divider <= 0) null else decode(pair.substring(0, divider)) to decode(pair.substring(divider + 1))
        }
        .filter { it.second.isNotEmpty() }
        .toMap()
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(value: String): String? = runCatching {
    Base64.Default.decode(value.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }).decodeToString()
}.getOrNull()

private fun decode(value: String): String {
    val bytes = ArrayList<Byte>()
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val hex = value.substring(index + 1, index + 3).toIntOrNull(16) ?: error("invalid URL escape")
            bytes += hex.toByte(); index += 3
        } else {
            bytes += value[index++].code.toByte()
        }
    }
    return bytes.toByteArray().decodeToString()
}
