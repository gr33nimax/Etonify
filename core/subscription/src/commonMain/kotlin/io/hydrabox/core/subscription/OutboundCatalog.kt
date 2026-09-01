package io.hydrabox.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * One entry the core can dial. [json] is the outbound object as the subscription
 * described it — kept verbatim so protocol details and detour chains survive; the
 * generator embeds it unchanged.
 */
data class CatalogOutbound(
    val tag: String,
    val type: String,
    val json: JsonObject,
    val scope: String = "",
    val selectable: Boolean = true,
)

/** Everything one subscription contributes: dialable entries plus its own default. */
data class OutboundCatalog(
    val format: SubscriptionDocumentFormat,
    val outbounds: List<CatalogOutbound>,
    val defaultTag: String? = null,
) {
    val selectable get() = outbounds.filter(CatalogOutbound::selectable)
}

/**
 * Turns any supported subscription body into outbound objects.
 *
 * Formats that already speak sing-box — a Hydra v2 subscription and a plain sing-box
 * document — are projected verbatim, because rewriting them into typed models is how
 * transport details get silently dropped. Share links are built into outbounds instead,
 * since there is nothing to preserve.
 */
object OutboundCatalogParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Types that describe routing rather than a server. */
    private val metaTypes = setOf("direct", "block", "dns", "selector", "urltest")

    const val HYDRA_API_VERSION = "hydra.io/subscription/v2"

    fun parse(content: String): OutboundCatalog {
        val body = content.trim()
        require(body.isNotEmpty()) { "empty subscription" }
        decodeJson(body)?.let { root ->
            hydra(root)?.let { return it }
            singbox(root)?.let { return it }
            sip008(root)?.let { return it }
            xray(root)?.let { return it }
        }
        clash(body)?.let { return it }
        expandBase64(body)?.let { expanded ->
            decodeJson(expanded)?.let { root ->
                hydra(root)?.let { return it }
                singbox(root)?.let { return it }
                sip008(root)?.let { return it }
                xray(root)?.let { return it }
            }
            clash(expanded)?.let { return it }
            return links(expanded)
        }
        return links(body)
    }

    fun isEncryptedHydra(content: String): Boolean = decodeJson(content.trim())
        ?.let { it is JsonObject && it.containsKey("protected") && !it.containsKey("resources") } == true

    // --- Hydra v2 -----------------------------------------------------------------

    private fun hydra(root: JsonElement): OutboundCatalog? {
        val document = root as? JsonObject ?: return null
        val api = document["api_version"]?.jsonPrimitive?.contentOrNull
        if (api != HYDRA_API_VERSION && document["kind"]?.jsonPrimitive?.contentOrNull != "Subscription") return null
        if (document.containsKey("protected") && !document.containsKey("resources")) {
            error("encrypted Hydra subscriptions must be opened by the core first")
        }
        val resources = document["resources"] as? JsonArray ?: error("Hydra subscription has no resources")
        require(resources.isNotEmpty()) { "Hydra subscription has no resources" }

        val entrypoints = mutableSetOf<String>()
        var defaultTag: String? = null
        val defaultProfile = document["default_profile"]?.jsonPrimitive?.contentOrNull
        (document["profiles"] as? JsonArray).orEmptyArray().forEach { entry ->
            val profile = entry as? JsonObject ?: return@forEach
            if (profile["enabled"]?.jsonPrimitive?.contentOrNull == "false") return@forEach
            val tag = (profile["entrypoint"] as? JsonObject)?.get("tag")?.jsonPrimitive?.contentOrNull ?: return@forEach
            entrypoints += tag
            val id = profile["id"]?.jsonPrimitive?.contentOrNull
            if (defaultTag == null || (defaultProfile != null && id == defaultProfile)) defaultTag = tag
        }

        val collected = mutableListOf<CatalogOutbound>()
        val seen = mutableSetOf<String>()
        resources.forEach { entry ->
            val resource = entry as? JsonObject ?: return@forEach
            val scope = resource["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val body = resource["document"] as? JsonObject ?: return@forEach
            listOf("outbounds", "endpoints").forEach { section ->
                (body[section] as? JsonArray).orEmptyArray().forEach { value ->
                    val outbound = value as? JsonObject ?: return@forEach
                    val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val type = outbound["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (type == "selector" || type == "urltest") return@forEach
                    if (!seen.add(tag)) return@forEach
                    collected += CatalogOutbound(
                        tag = tag,
                        type = type.ifEmpty { if (section == "endpoints") "endpoint" else "unknown" },
                        json = outbound,
                        scope = scope,
                        // Everything is embedded so detour chains keep resolving, but only
                        // an entrypoint — or any real server, when the document names no
                        // profiles — is offered as a choice.
                        selectable = if (entrypoints.isEmpty()) type !in metaTypes else tag in entrypoints,
                    )
                }
            }
        }
        require(collected.any(CatalogOutbound::selectable)) { "Hydra subscription has no usable entrypoint" }
        return OutboundCatalog(SubscriptionDocumentFormat.HYDRA, collected, defaultTag)
    }

    // --- plain sing-box -----------------------------------------------------------

    private fun singbox(root: JsonElement): OutboundCatalog? {
        val document = root as? JsonObject ?: return null
        if (!document.containsKey("outbounds") && !document.containsKey("endpoints")) return null
        val collected = mutableListOf<CatalogOutbound>()
        val seen = mutableSetOf<String>()
        listOf("outbounds", "endpoints").forEach { section ->
            (document[section] as? JsonArray).orEmptyArray().forEach { value ->
                val outbound = value as? JsonObject ?: return@forEach
                val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                // An entry without `type` is not a sing-box outbound: an Xray document says
                // `protocol` instead, and claiming it here would hide it from that branch.
                val type = outbound["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                if (type == "selector" || type == "urltest") return@forEach
                if (!seen.add(tag)) return@forEach
                collected += CatalogOutbound(tag, type.ifEmpty { "unknown" }, outbound, selectable = type.isNotEmpty() && type !in metaTypes)
            }
        }
        return collected.takeIf { list -> list.any(CatalogOutbound::selectable) }
            ?.let { OutboundCatalog(SubscriptionDocumentFormat.SINGBOX, it) }
    }

    // --- Xray and Clash -----------------------------------------------------------

    /**
     * Both formats describe the same servers in someone else's vocabulary, so unlike a
     * sing-box document they are translated rather than projected. A provider's Clash or
     * Xray subscription worked in 1.x and has to keep working.
     */
    private fun xray(root: JsonElement): OutboundCatalog? = XrayDocument.outbounds(root)
        ?.let { OutboundCatalog(SubscriptionDocumentFormat.XRAY, it) }

    private fun clash(body: String): OutboundCatalog? = ClashDocument.outbounds(body)
        ?.let { OutboundCatalog(SubscriptionDocumentFormat.CLASH, it) }

    // --- SIP008 -------------------------------------------------------------------

    private fun sip008(root: JsonElement): OutboundCatalog? {
        val servers = when (root) {
            is JsonObject -> root["servers"] as? JsonArray
            is JsonArray -> root.firstNotNullOfOrNull { (it as? JsonObject)?.get("servers") as? JsonArray }
            else -> null
        } ?: return null
        val collected = servers.mapIndexedNotNull { index, value ->
            val server = value as? JsonObject ?: return@mapIndexedNotNull null
            val host = server["server"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val port = server["server_port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@mapIndexedNotNull null
            val tag = server["remarks"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty) ?: "$host-$port-$index"
            CatalogOutbound(
                tag = tag,
                type = "shadowsocks",
                json = buildJsonObject {
                    put("type", JsonPrimitive("shadowsocks"))
                    put("tag", JsonPrimitive(tag))
                    put("server", JsonPrimitive(host))
                    put("server_port", JsonPrimitive(port))
                    server["method"]?.let { put("method", it) }
                    server["password"]?.let { put("password", it) }
                },
            )
        }
        return collected.takeIf(List<CatalogOutbound>::isNotEmpty)
            ?.let { OutboundCatalog(SubscriptionDocumentFormat.SIP008, it) }
    }

    // --- share links --------------------------------------------------------------

    private fun links(body: String): OutboundCatalog {
        val parsed = body.replace(" -> ", "\n").lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val wireGuard = body.trimStart().startsWith("[Interface]")
        val sources = if (wireGuard) listOf(body) else parsed
        val collected = sources.mapIndexedNotNull { index, value ->
            runCatching { SubscriptionParser.parse(value) }.getOrNull()?.let { link ->
                val tag = link.name.trim().takeIf(String::isNotEmpty) ?: "${link.server}-${link.port}-$index"
                CatalogOutbound(tag, ShareLinkOutbound.typeOf(link), ShareLinkOutbound.toJson(link, tag))
            }
        }
        require(collected.isNotEmpty()) { "no usable outbound in subscription" }
        return OutboundCatalog(SubscriptionDocumentFormat.UNKNOWN, collected)
    }

    // --- helpers ------------------------------------------------------------------

    private fun decodeJson(body: String): JsonElement? =
        if (body.startsWith("{") || body.startsWith("[")) runCatching { json.parseToJsonElement(body) }.getOrNull() else null

    @OptIn(ExperimentalEncodingApi::class)
    private fun expandBase64(body: String): String? {
        if (body.contains("://") || body.startsWith("{") || body.startsWith("[")) return null
        val compact = body.filterNot(Char::isWhitespace)
        if (compact.length < 8 || !compact.all { it.isLetterOrDigit() || it in "+/-_=" }) return null
        return runCatching {
            Base64.Default.decode(
                compact.replace('-', '+').replace('_', '/')
                    .let { it + "=".repeat((4 - it.length % 4) % 4) },
            ).decodeToString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun JsonArray?.orEmptyArray(): List<JsonElement> = this ?: emptyList()
}
