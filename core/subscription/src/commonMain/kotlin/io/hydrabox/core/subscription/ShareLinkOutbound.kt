package io.hydrabox.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a core entry from a share link. A link carries nothing worth preserving verbatim,
 * so the mapping is explicit — and the query string is honoured in full, because dropping
 * `security`, `sni`, `type` or `mode` is what makes a real server refuse the handshake.
 */
object ShareLinkOutbound {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun typeOf(link: ShareLink): String = when (link) {
        is ShareLink.Vless -> "vless"
        is ShareLink.Trojan -> "trojan"
        is ShareLink.Proxy -> link.type
        is ShareLink.WireGuard -> "wireguard"
    }

    /**
     * True when the core runs this as an endpoint rather than an outbound. WireGuard moved
     * to `endpoints` in the core the app ships, and an endpoint placed in `outbounds` is not
     * a broken server — it is a configuration the core rejects as a whole.
     */
    fun isEndpoint(link: ShareLink): Boolean = link is ShareLink.WireGuard

    fun toJson(link: ShareLink, tag: String): JsonObject = when (link) {
        is ShareLink.Vless -> buildJsonObject {
            put("type", "vless")
            put("tag", tag)
            put("server", link.server)
            put("server_port", link.port)
            link.uuid.use { put("uuid", it) }
            link.query["flow"]?.takeIf(String::isNotEmpty)?.let { put("flow", it) }
            link.query["encryption"]?.takeIf { it != "none" }?.let { put("encryption", it) }
            transport(link.query)?.let { put("transport", it) }
            tls(link.query, link.server, secured(link.query))?.let { put("tls", it) }
        }

        is ShareLink.Trojan -> buildJsonObject {
            put("type", "trojan")
            put("tag", tag)
            put("server", link.server)
            put("server_port", link.port)
            link.password.use { put("password", it) }
            transport(link.query)?.let { put("transport", it) }
            tls(link.query, link.server, secured = true)?.let { put("tls", it) }
        }

        is ShareLink.Proxy -> buildJsonObject {
            put("type", link.type)
            put("tag", tag)
            put("server", link.server)
            put("server_port", link.port)
            when (link.type) {
                "shadowsocks", "shadowsocksr" -> {
                    link.username?.use { put("method", it) }
                    link.password?.use { put("password", it) }
                    link.query["plugin"]?.let { plugin ->
                        put("plugin", plugin.substringBefore(';'))
                        plugin.substringAfter(';', "").takeIf(String::isNotEmpty)
                            ?.let { put("plugin_opts", it) }
                    }
                }

                "vmess" -> {
                    link.username?.use { put("uuid", it) }
                    // The cipher and the alternative id are part of the identity of a vmess
                    // server: a provider that says `scy=zero` is not offering `auto`.
                    link.query["scy"]?.let { put("security", it) }
                    link.query["aid"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("alter_id", it) }
                }

                "hysteria2", "tuic", "anytls" ->
                    (link.password ?: link.username)?.use { put("password", it) }

                else -> {
                    link.username?.use { put("username", it) }
                    link.password?.use { put("password", it) }
                }
            }
            transport(link.query)?.let { put("transport", it) }
            tls(link.query, link.server, link.tls)?.let { put("tls", it) }
        }

        is ShareLink.WireGuard -> endpoint(link, tag)
    }

    /**
     * A WireGuard peer as an endpoint. The AmneziaWG fields are carried through when the
     * provider sent them: a peer configured with junk packets will not complete a plain
     * handshake, so omitting them is the same as having the wrong key.
     */
    private fun endpoint(link: ShareLink.WireGuard, tag: String): JsonObject = buildJsonObject {
        put("type", "wireguard")
        put("tag", tag)
        putJsonArray("address") {
            link.localAddresses.ifEmpty { listOf("172.16.0.2/32") }.forEach { add(JsonPrimitive(it)) }
        }
        link.privateKey.use { put("private_key", it) }
        link.query["mtu"]?.toIntOrNull()?.takeIf { it in 576..9000 }?.let { put("mtu", it) }
        putJsonArray("peers") {
            add(
                buildJsonObject {
                    put("address", link.server)
                    put("port", link.port)
                    link.peerPublicKey.use { put("public_key", it) }
                    link.preSharedKey?.use { put("pre_shared_key", it) }
                    putJsonArray("allowed_ips") {
                        val allowed = link.query["allowed_ips"]?.split(',')?.map(String::trim)
                            ?.filter(String::isNotEmpty)
                        (allowed ?: listOf("0.0.0.0/0", "::/0")).forEach { add(JsonPrimitive(it)) }
                    }
                    link.query["keepalive"]?.toIntOrNull()?.takeIf { it > 0 }
                        ?.let { put("persistent_keepalive_interval", it) }
                },
            )
        }
        amnezia(link.query)?.let { put("amnezia", it) }
    }

    private fun amnezia(query: Map<String, String>): JsonObject? {
        val fields = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4")
            .mapNotNull { field -> query[field]?.toLongOrNull()?.let { field to it } }
        if (fields.isEmpty()) return null
        return buildJsonObject { fields.forEach { (field, value) -> put(field, value) } }
    }

    private fun secured(query: Map<String, String>): Boolean =
        query["security"].let { it == "tls" || it == "reality" || it == "xtls" }

    private fun tls(query: Map<String, String>, server: String, secured: Boolean): JsonObject? {
        if (!secured) return null
        return buildJsonObject {
            put("enabled", true)
            put("server_name", query["sni"] ?: query["host"] ?: server)
            query["fp"]?.let { putJsonObject("utls") { put("enabled", true); put("fingerprint", it) } }
            query["alpn"]?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() }
                ?.let { values -> putJsonArray("alpn") { values.forEach { add(JsonPrimitive(it)) } } }
            if (query["allowInsecure"] == "1" || query["insecure"] == "1") put("insecure", true)
            query["pbk"]?.let { key ->
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", key)
                    query["sid"]?.let { put("short_id", it) }
                }
            }
        }
    }

    /**
     * The transport the link asks for, in the core's vocabulary.
     *
     * All six the core carries are mapped, not two of them. `xhttp` in particular is what a
     * current vless provider hands out — the link in the subscription this was tested
     * against is `type=xhttp&mode=stream-up` — and mapping it to nothing produced a plain
     * TCP dial against a server that only speaks xhttp.
     */
    private fun transport(query: Map<String, String>): JsonObject? = when (query["type"]?.lowercase()) {
        "ws", "websocket" -> buildJsonObject {
            put("type", "ws")
            query["path"]?.let { path ->
                // v2rayN writes early data into the path as `?ed=2048`, and the core takes
                // it as two separate fields.
                put("path", path.substringBefore('?'))
                path.substringAfter("ed=", "").takeWhile(Char::isDigit).toIntOrNull()
                    ?.let { put("max_early_data", it); put("early_data_header_name", "Sec-WebSocket-Protocol") }
            }
            query["host"]?.let { host -> putJsonObject("headers") { put("Host", host) } }
        }

        "grpc" -> buildJsonObject {
            put("type", "grpc")
            (query["serviceName"] ?: query["path"]?.trimStart('/'))?.let { put("service_name", it) }
        }

        "http", "h2", "h3" -> buildJsonObject {
            put("type", "http")
            query["host"]?.let { host ->
                putJsonArray("host") { host.split(',').map(String::trim).filter(String::isNotEmpty).forEach { add(JsonPrimitive(it)) } }
            }
            query["path"]?.let { put("path", it) }
            query["method"]?.let { put("method", it) }
        }

        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            query["path"]?.let { put("path", it) }
            query["host"]?.let { put("host", it) }
        }

        "xhttp", "splithttp" -> buildJsonObject {
            put("type", "xhttp")
            put("mode", query["mode"]?.takeIf(String::isNotEmpty) ?: "auto")
            query["path"]?.let { put("path", it) }
            query["host"]?.let { put("host", it) }
            // `extra` is a JSON object the provider appends to an xhttp link, and it is
            // written in Xray's vocabulary: `xPaddingBytes`, `scStreamUpServerSecs`, `xmux`.
            // The core reads the same settings under snake_case names, so the keys are
            // translated rather than copied. Copying them verbatim is not a cosmetic
            // difference: the core then sees no padding range at all and refuses the whole
            // configuration with `x_padding_bytes cannot be disabled`.
            query["extra"]?.let { extra ->
                runCatching { json.parseToJsonElement(extra) as? JsonObject }.getOrNull()
                    ?.forEach { (key, value) ->
                        if (key == "type" || key == "mode") return@forEach
                        val name = xhttpExtras[key] ?: snakeCase(key)
                        if (name == "xmux") put(name, xmux(value)) else put(name, value)
                    }
            }
        }

        "quic" -> buildJsonObject { put("type", "quic") }

        "kcp", "mkcp" -> buildJsonObject {
            put("type", "mkcp")
            query["headerType"]?.let { put("header_type", it) }
            query["seed"]?.let { put("seed", it) }
        }

        else -> null
    }

    /**
     * Xray's names for the xhttp extras against the core's. Only the ones the core actually
     * reads are listed; anything else falls back to a plain snake_case rewrite.
     */
    private val xhttpExtras = mapOf(
        "xPaddingBytes" to "x_padding_bytes",
        "scMaxEachPostBytes" to "sc_max_each_post_bytes",
        "scMinPostsIntervalMs" to "sc_min_posts_interval_ms",
        "scMaxBufferedPosts" to "sc_max_buffered_posts",
        "scStreamUpServerSecs" to "sc_stream_up_server_secs",
        "noGRPCHeader" to "no_grpc_header",
        "noSSEHeader" to "no_sse_header",
        "xmux" to "xmux",
        "headers" to "headers",
        "host" to "host",
        "path" to "path",
    )

    private val xmuxFields = mapOf(
        "maxConcurrency" to "max_concurrency",
        "maxConnections" to "max_connections",
        "cMaxReuseTimes" to "c_max_reuse_times",
        "hMaxRequestTimes" to "h_max_request_times",
        "hMaxReusableSecs" to "h_max_reusable_secs",
        "hKeepAlivePeriod" to "h_keep_alive_period",
    )

    private fun xmux(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val fields = value as? JsonObject ?: return value
        return buildJsonObject {
            fields.forEach { (key, inner) -> put(xmuxFields[key] ?: snakeCase(key), inner) }
        }
    }

    /** `scStreamUpServerSecs` to `sc_stream_up_server_secs`, for keys not worth listing. */
    private fun snakeCase(name: String): String = buildString {
        name.forEachIndexed { index, symbol ->
            if (symbol.isUpperCase()) {
                if (index > 0 && !name[index - 1].isUpperCase()) append('_')
                append(symbol.lowercaseChar())
            } else {
                append(symbol)
            }
        }
    }
}
