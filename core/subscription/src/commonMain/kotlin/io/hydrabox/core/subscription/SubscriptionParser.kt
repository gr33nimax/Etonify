package io.hydrabox.core.subscription

import io.hydrabox.core.diagnostics.Secret
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * One server as a share link describes it.
 *
 * [query] carries the link's own parameters unchanged, because that is where the transport
 * lives: a `vless://` without `type=xhttp` and a `vmess://` without `net=ws` are different
 * servers, and building the outbound from the address alone produces something that dials a
 * real host and is refused by it.
 */
sealed interface ShareLink {
    val server: String
    val port: Int
    val name: String
    val query: Map<String, String>

    data class Vless(
        override val server: String,
        override val port: Int,
        override val name: String,
        val uuid: Secret,
        override val query: Map<String, String> = emptyMap(),
    ) : ShareLink

    data class Trojan(
        override val server: String,
        override val port: Int,
        override val name: String,
        val password: Secret,
        override val query: Map<String, String> = emptyMap(),
    ) : ShareLink

    data class Proxy(
        override val server: String,
        override val port: Int,
        override val name: String,
        val type: String,
        val tls: Boolean,
        val username: Secret?,
        val password: Secret?,
        override val query: Map<String, String> = emptyMap(),
    ) : ShareLink

    /**
     * A WireGuard peer, which the core runs as an endpoint rather than an outbound. The
     * AmneziaWG obfuscation parameters travel in [query]: a provider that sends `jc`, `s1`
     * and `h1..h4` is describing a peer that will not answer a plain WireGuard handshake.
     */
    data class WireGuard(
        override val server: String,
        override val port: Int,
        override val name: String,
        val privateKey: Secret,
        val peerPublicKey: Secret,
        val localAddresses: List<String> = emptyList(),
        val preSharedKey: Secret? = null,
        override val query: Map<String, String> = emptyMap(),
    ) : ShareLink
}

enum class SubscriptionDocumentFormat { SINGBOX, XRAY, CLASH, SIP008, HYDRA, UNKNOWN }

object SubscriptionParser {
    private val link = Regex("""^([A-Za-z0-9+]+)://(?:([^@/?#]+)@)?([^:/?#]+):(\d+)(?:/[^?#]*)?(?:\?([^#]*))?(?:#(.*))?$""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Schemes whose userinfo is a single credential rather than `user:password`. */
    private val proxySchemes = setOf(
        "socks", "socks4", "socks4a", "socks5", "socks5h", "http", "https",
        "hysteria", "hy", "hy2", "hysteria2", "naive+https", "naive+quic", "tuic", "anytls",
    )

    fun parse(value: String): ShareLink {
        val trimmed = value.trim()
        if (trimmed.startsWith("[Interface]")) return parseWireGuardConfig(trimmed)
        if (trimmed.startsWith("vmess://", ignoreCase = true)) return parseVmess(trimmed)
        if (trimmed.startsWith("ssr://", ignoreCase = true)) return parseSsr(trimmed)
        if (trimmed.startsWith("ss://", ignoreCase = true)) return parseShadowsocks(trimmed)
        if (trimmed.startsWith("wg://", ignoreCase = true) ||
            trimmed.startsWith("wireguard://", ignoreCase = true)
        ) {
            return parseWireGuardLink(trimmed)
        }
        val match = link.matchEntire(trimmed) ?: error("not a share link")
        val scheme = match.groupValues[1].lowercase()
        val credential = decode(match.groupValues[2])
        val server = decode(match.groupValues[3]).takeIf(String::isNotEmpty) ?: error("missing link server")
        val port = match.groupValues[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid link port")
        val query = parseQuery(match.groupValues[5])
        val name = decode(match.groupValues[6])
        return when (scheme) {
            "vless" -> ShareLink.Vless(
                server,
                port,
                name,
                Secret.of(credential.takeIf(String::isNotEmpty) ?: error("missing link credential")),
                query,
            )
            "trojan" -> ShareLink.Trojan(
                server,
                port,
                name,
                Secret.of(credential.takeIf(String::isNotEmpty) ?: error("missing link credential")),
                query,
            )
            in proxySchemes -> proxy(scheme, server, port, name, credential, query)
            else -> error("unsupported scheme $scheme")
        }
    }

    /** Every link the body holds, skipping the ones this build has no mapping for. */
    fun parseAll(content: String): List<ShareLink> = content
        .replace(" -> ", "\n")
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
        .toList()

    /**
     * A `wg://` or `wireguard://` link. Providers put the private key in the query rather
     * than the userinfo, and AmneziaWG adds its own obfuscation fields next to it.
     */
    private fun parseWireGuardLink(value: String): ShareLink.WireGuard {
        val match = link.matchEntire(value.trim()) ?: error("not a WireGuard link")
        val server = decode(match.groupValues[3]).takeIf(String::isNotEmpty) ?: error("missing WireGuard server")
        val port = match.groupValues[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid WireGuard port")
        val query = parseQuery(match.groupValues[5])
        val userInfo = decode(match.groupValues[2])
        val privateKey = query["private_key"] ?: query["privatekey"] ?: query["secret_key"]
            ?: userInfo.takeIf(String::isNotEmpty)
            ?: error("missing WireGuard private key")
        val peerKey = query["public_key"] ?: query["publickey"] ?: query["peer_public_key"] ?: query["pubkey"]
        val local = (query["local_address"] ?: query["address"] ?: query["ip"]).orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
        return ShareLink.WireGuard(
            server = server,
            port = port,
            name = decode(match.groupValues[6]),
            privateKey = Secret.of(privateKey),
            // A peer with no public key cannot be dialled, and a subscription that omits it
            // is describing something else; skipping it is better than a config the core
            // refuses as a whole.
            peerPublicKey = Secret.of(peerKey ?: error("missing WireGuard peer public key")),
            localAddresses = local,
            preSharedKey = (query["pre_shared_key"] ?: query["presharedkey"])?.let(Secret::of),
            query = query,
        )
    }

    /** A `wg-quick` configuration file, as exported by every WireGuard client. */
    private fun parseWireGuardConfig(content: String): ShareLink.WireGuard {
        var section = ""
        val values = mutableMapOf<String, String>()
        content.lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            if (line.startsWith("[") && line.endsWith("]")) section = line.removeSurrounding("[", "]")
            else line.split('=', limit = 2).takeIf { it.size == 2 }?.let { values["$section.${it[0].trim()}"] = it[1].trim() }
        }
        val endpoint = values["Peer.Endpoint"] ?: error("missing WireGuard endpoint")
        val divider = endpoint.lastIndexOf(':').takeIf { it > 0 } ?: error("invalid WireGuard endpoint")
        val server = endpoint.substring(0, divider).removeSurrounding("[", "]")
        val port = endpoint.substring(divider + 1).toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("invalid WireGuard port")
        val amnezia = listOf("Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4")
            .mapNotNull { field -> values["Interface.$field"]?.let { field.lowercase() to it } }
            .toMap()
        return ShareLink.WireGuard(
            server = server,
            port = port,
            name = values["Interface.Name"] ?: "WireGuard",
            privateKey = Secret.of(values["Interface.PrivateKey"] ?: error("missing WireGuard private key")),
            peerPublicKey = Secret.of(values["Peer.PublicKey"] ?: error("missing WireGuard peer key")),
            localAddresses = values["Interface.Address"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
            preSharedKey = values["Peer.PresharedKey"]?.let(Secret::of),
            query = amnezia + listOfNotNull(
                values["Interface.MTU"]?.let { "mtu" to it },
                values["Peer.PersistentKeepalive"]?.let { "keepalive" to it },
                values["Peer.AllowedIPs"]?.let { "allowed_ips" to it },
            ).toMap(),
        )
    }

    /**
     * A `vmess://` link: base64 of the v2rayN JSON object.
     *
     * Every field here decides whether the handshake is recognised. `net` and `path` place
     * the connection inside WebSocket or gRPC, `host` and `sni` decide which name TLS
     * presents, `scy` names the cipher and `aid` the alternative id. The alpha read four of
     * them and built a plain TCP outbound, which dials the right host and is refused by it —
     * the single most common way a working subscription looked broken.
     */
    private fun parseVmess(value: String): ShareLink.Proxy {
        val payload = value.substringAfter("://").substringBefore('#')
        val document = decodeBase64(payload) ?: error("invalid vmess link")
        val fields = runCatching { json.parseToJsonElement(document) as? JsonObject }.getOrNull()
            ?: error("invalid vmess document")
        fun field(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            fields[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }
        val server = field("add", "address", "host") ?: error("missing vmess server")
        val port = field("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid vmess port")
        val uuid = field("id") ?: error("missing vmess credential")
        val transport = field("net", "network")?.lowercase().orEmpty()
        val security = field("tls", "security")?.lowercase().orEmpty()
        val host = field("host", "sni")
        val query = buildMap {
            transport.takeIf { it.isNotEmpty() && it != "tcp" }?.let { put("type", it) }
            field("path")?.let { put("path", it) }
            // gRPC calls it a service name; v2rayN puts it in `path` for every transport.
            if (transport == "grpc") field("path", "serviceName")?.let { put("serviceName", it) }
            host?.let { put("host", it) }
            field("sni", "peer")?.let { put("sni", it) }
            field("alpn")?.let { put("alpn", it) }
            field("fp")?.let { put("fp", it) }
            field("scy", "security")?.takeIf { it != "tls" && it != "none" && it != "reality" }
                ?.let { put("scy", it) }
            field("aid", "alterId")?.let { put("aid", it) }
            field("mode")?.let { put("mode", it) }
            if (security == "tls" || security == "reality") put("security", security)
            if (field("allowInsecure") == "1" || field("skip-cert-verify") == "true") put("insecure", "1")
        }
        return ShareLink.Proxy(
            server = server,
            port = port,
            name = field("ps", "remarks").orEmpty().ifEmpty { decode(value.substringAfter('#', "")) },
            type = "vmess",
            tls = security == "tls" || security == "reality",
            username = Secret.of(uuid),
            password = null,
            query = query,
        )
    }

    /**
     * A `ss://` link in either shape: SIP002, where the userinfo is
     * `base64(method:password)`, and the older form where the whole body is base64 of
     * `method:password@host:port`. Splitting SIP002 on `:` without decoding it — what the
     * alpha did — produces a cipher name that is a fragment of base64.
     */
    private fun parseShadowsocks(value: String): ShareLink.Proxy {
        val name = decode(value.substringAfter('#', ""))
        val body = value.substringAfter("://").substringBefore('#')
        val credential: String
        val address: String
        if (body.contains('@')) {
            val raw = body.substringBeforeLast('@')
            credential = decodeBase64(raw)?.takeIf { it.contains(':') } ?: decode(raw)
            address = body.substringAfterLast('@')
        } else {
            val decoded = decodeBase64(body.substringBefore('?')) ?: error("invalid shadowsocks link")
            credential = decoded.substringBeforeLast('@')
            address = decoded.substringAfterLast('@')
        }
        val hostPort = address.substringBefore('?').substringBefore('/')
        val divider = hostPort.lastIndexOf(':').takeIf { it > 0 } ?: error("invalid shadowsocks address")
        val server = hostPort.substring(0, divider).removeSurrounding("[", "]")
        val port = hostPort.substring(divider + 1).toIntOrNull()?.takeIf { it in 1..65535 }
            ?: error("invalid shadowsocks port")
        val method = credential.substringBefore(':').takeIf(String::isNotEmpty) ?: error("missing shadowsocks method")
        val password = credential.substringAfter(':', "").takeIf(String::isNotEmpty)
            ?: error("missing shadowsocks password")
        return ShareLink.Proxy(
            server = server,
            port = port,
            name = name,
            type = "shadowsocks",
            tls = false,
            username = Secret.of(method),
            password = Secret.of(password),
            query = parseQuery(address.substringAfter('?', "")),
        )
    }

    /** An `ssr://` link. Kept for the subscriptions that still carry them. */
    private fun parseSsr(value: String): ShareLink.Proxy {
        val document = decodeBase64(value.substringAfter("://").substringBefore('#')) ?: error("invalid ssr link")
        val fields = document.substringBefore("/?").split(':')
        if (fields.size < 6) error("invalid ssr link")
        val port = fields[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: error("invalid ssr port")
        val password = decodeBase64(fields.last()) ?: error("invalid ssr credential")
        return ShareLink.Proxy(
            server = fields[0],
            port = port,
            name = "",
            type = "shadowsocksr",
            tls = false,
            username = Secret.of(fields[3]),
            password = Secret.of(password),
        )
    }

    private fun proxy(
        scheme: String,
        server: String,
        port: Int,
        name: String,
        credential: String,
        query: Map<String, String>,
    ): ShareLink.Proxy {
        val parts = credential.split(':', limit = 2)
        val username = parts.firstOrNull()?.takeIf(String::isNotEmpty)?.let(Secret::of)
        val password = parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.let(Secret::of)
        val type = when {
            scheme.startsWith("socks") -> "socks"
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
    Base64.Default.decode(
        value.trim().replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) },
    ).decodeToString()
}.getOrNull()?.takeIf { text -> text.none { it.code == 0 } }

private fun decode(value: String): String {
    if (!value.contains('%') && !value.contains('+')) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val symbol = value[index]
        when {
            symbol == '%' && index + 2 < value.length -> {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16) ?: error("invalid URL escape")
                bytes += hex.toByte()
                index += 3
            }
            else -> {
                bytes += symbol.code.toByte()
                index += 1
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}
