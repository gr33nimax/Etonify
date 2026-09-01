package io.hydrabox.core.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two formats HydraBox 1.x imported and 2.0 could not. These go through the real import
 * path — `OutboundCatalogParser.parse`, the one the app calls — not through a parser that
 * only tests reach.
 */
class ClashXrayImportTest {
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.child(key: String) = this[key]?.jsonObject

    private val clash = """
        port: 7890
        mode: rule
        proxies:
          - name: "Tokyo 01"
            type: vmess
            server: tokyo.example
            port: 443
            uuid: 7c6a5b3e-4f1a-4d2b-8c9e-1a2b3c4d5e6f
            alterId: 0
            cipher: auto
            tls: true
            servername: tokyo.sni.example
            skip-cert-verify: true
            network: ws
            ws-opts:
              path: /vm
              headers:
                Host: tokyo.host.example
          - {name: Frankfurt, type: trojan, server: fra.example, port: 8443, password: s3cret, sni: fra.sni.example}
          - name: Osaka
            type: ss
            server: osaka.example
            port: 8388
            cipher: aes-256-gcm
            password: ss-pass
            plugin: obfs
            plugin-opts:
              mode: http
              host: bing.com
        proxy-groups:
          - name: Proxy
            type: select
            proxies: [Tokyo 01, Frankfurt]
    """.trimIndent()

    @Test
    fun `a Clash subscription becomes core outbounds, not a list of names`() {
        val catalog = OutboundCatalogParser.parse(clash)
        assertEquals(SubscriptionDocumentFormat.CLASH, catalog.format)
        assertEquals(listOf("Tokyo 01", "Frankfurt", "Osaka"), catalog.outbounds.map(CatalogOutbound::tag))
        assertEquals(listOf("vmess", "trojan", "shadowsocks"), catalog.outbounds.map(CatalogOutbound::type))
        assertTrue(catalog.outbounds.all(CatalogOutbound::selectable))
    }

    @Test
    fun `Clash names for the same things are translated, not copied`() {
        val vmess = OutboundCatalogParser.parse(clash).outbounds.first().json
        assertEquals("tokyo.example", vmess.text("server"))
        assertEquals("443", vmess.text("server_port"))
        assertEquals("7c6a5b3e-4f1a-4d2b-8c9e-1a2b3c4d5e6f", vmess.text("uuid"))
        assertEquals("auto", vmess.text("security"))
        assertEquals("0", vmess.text("alter_id"))
        assertEquals("tokyo.sni.example", vmess.child("tls")?.text("server_name"))
        assertEquals("true", vmess.child("tls")?.text("insecure"))
        assertEquals("ws", vmess.child("transport")?.text("type"))
        assertEquals("/vm", vmess.child("transport")?.text("path"))
        assertEquals("tokyo.host.example", vmess.child("transport")?.child("headers")?.text("Host"))
    }

    @Test
    fun `a flow-style entry parses as well as a block one, and TLS types imply TLS`() {
        val trojan = OutboundCatalogParser.parse(clash).outbounds[1].json
        assertEquals("fra.example", trojan.text("server"))
        assertEquals("s3cret", trojan.text("password"))
        assertEquals("fra.sni.example", trojan.child("tls")?.text("server_name"))
        assertEquals("true", trojan.child("tls")?.text("enabled"))
    }

    @Test
    fun `Shadowsocks obfuscation survives as a plugin with its options`() {
        val ss = OutboundCatalogParser.parse(clash).outbounds[2].json
        assertEquals("aes-256-gcm", ss.text("method"))
        assertEquals("obfs-local", ss.text("plugin"))
        assertEquals("mode=http;host=bing.com", ss.text("plugin_opts"))
        assertNull(ss["tls"])
    }

    private val xray = """
        {
          "outbounds": [
            {
              "tag": "reality-node",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": "reality.example",
                    "port": 443,
                    "users": [{"id": "1f2e3d4c-5b6a-7988-9a0b-1c2d3e4f5a6b", "flow": "xtls-rprx-vision"}]
                  }
                ]
              },
              "streamSettings": {
                "network": "grpc",
                "security": "reality",
                "realitySettings": {
                  "serverName": "reality.sni.example",
                  "publicKey": "public-key-value",
                  "shortId": "ab12",
                  "fingerprint": "chrome"
                },
                "grpcSettings": {"serviceName": "grpc-service"}
              }
            },
            {"tag": "direct", "protocol": "freedom"},
            {
              "tag": "ss-node",
              "protocol": "shadowsocks",
              "settings": {
                "servers": [
                  {"address": "ss.example", "port": 8388, "method": "chacha20-ietf-poly1305", "password": "xray-pass"}
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an Xray config imports its servers and skips its routing outbounds`() {
        val catalog = OutboundCatalogParser.parse(xray)
        assertEquals(SubscriptionDocumentFormat.XRAY, catalog.format)
        assertEquals(listOf("reality-node", "ss-node"), catalog.outbounds.map(CatalogOutbound::tag))
    }

    @Test
    fun `Xray stream settings become core transport and TLS`() {
        val vless = OutboundCatalogParser.parse(xray).outbounds.first().json
        assertEquals("reality.example", vless.text("server"))
        assertEquals("1f2e3d4c-5b6a-7988-9a0b-1c2d3e4f5a6b", vless.text("uuid"))
        assertEquals("xtls-rprx-vision", vless.text("flow"))
        assertEquals("grpc", vless.child("transport")?.text("type"))
        assertEquals("grpc-service", vless.child("transport")?.text("service_name"))
        assertEquals("reality.sni.example", vless.child("tls")?.text("server_name"))
        assertEquals("public-key-value", vless.child("tls")?.child("reality")?.text("public_key"))
        assertEquals("ab12", vless.child("tls")?.child("reality")?.text("short_id"))
        assertEquals("chrome", vless.child("tls")?.child("utls")?.text("fingerprint"))
    }

    @Test
    fun `a sing-box document is still projected verbatim rather than translated`() {
        val catalog = OutboundCatalogParser.parse(
            "{\"outbounds\":[{\"type\":\"vless\",\"tag\":\"kept\",\"server\":\"s.example\",\"weird_field\":true}]}",
        )
        assertEquals(SubscriptionDocumentFormat.SINGBOX, catalog.format)
        assertEquals("true", catalog.outbounds.single().json.text("weird_field"))
    }

    @Test
    fun `a base64 wrapped Clash subscription is unwrapped first`() {
        val encoded = base64("proxies:\n  - {name: B64, type: trojan, server: b.example, port: 443, password: p}")
        val catalog = OutboundCatalogParser.parse(encoded)
        assertEquals(SubscriptionDocumentFormat.CLASH, catalog.format)
        assertEquals("B64", catalog.outbounds.single().tag)
    }

    private fun base64(value: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val bytes = value.encodeToByteArray()
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xff
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xff else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xff else 0
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            out.append(if (index + 1 < bytes.size) alphabet[((b1 and 0x0f) shl 2) or (b2 shr 6)] else '=')
            out.append(if (index + 2 < bytes.size) alphabet[b2 and 0x3f] else '=')
            index += 3
        }
        return out.toString()
    }
}
