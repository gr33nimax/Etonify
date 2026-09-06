package io.hydrabox.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
    /**
     * Whether the core runs this from `endpoints` rather than `outbounds`. WireGuard is the
     * only such entry today, and putting it in the wrong array is not a bad server — the
     * core refuses the whole configuration, so every server in the subscription stops
     * working.
     */
    val endpoint: Boolean = false,
    /**
     * The name the provider gave this server, when the document carries one. The tag is an
     * identity for the configuration and for the runtime; a person reading `call-vk-out`
     * instead of "обход БС" is reading our plumbing. Hydra carries it per profile.
     */
    val label: String? = null,
    /**
     * The tag this outbound arrived under, when it had to be renamed to keep the configuration's
     * tags unique. It is how a choice of server stored before the rename can still be followed
     * afterwards; nothing else may use it as an identity.
     */
    val originTag: String? = null,
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
/**
 * A parsed subscription and what did not survive the parse.
 *
 * [skipped] exists because "three servers out of four" is a normal answer for a real
 * subscription — a provider mixes in a protocol this build has no mapping for — and the
 * fourth one has to be nameable afterwards. Skipping silently is how an import that dropped
 * half a list came to look like a working import.
 */
data class CatalogParse(val catalog: OutboundCatalog, val skipped: List<String> = emptyList())

object OutboundCatalogParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Types that describe routing rather than a server. */
    private val metaTypes = setOf("direct", "block", "dns", "selector", "urltest")

    const val HYDRA_API_VERSION = "hydra.io/subscription/v2"

    fun parse(content: String): OutboundCatalog = inspect(content).catalog

    /** The same parse, with the entries it could not use named. */
    fun inspect(content: String): CatalogParse {
        val skipped = mutableListOf<String>()
        val body = content.trim()
        require(body.isNotEmpty()) { "empty subscription" }
        decodeJson(body)?.let { root ->
            hydra(root, skipped)?.let { return CatalogParse(it, skipped) }
            singbox(root, skipped)?.let { return CatalogParse(it, skipped) }
            sip008(root)?.let { return CatalogParse(it, skipped) }
            xray(root)?.let { return CatalogParse(it, skipped) }
        }
        clash(body)?.let { return CatalogParse(it, skipped) }
        expandBase64(body)?.let { expanded ->
            decodeJson(expanded)?.let { root ->
                hydra(root, skipped)?.let { return CatalogParse(it, skipped) }
                singbox(root, skipped)?.let { return CatalogParse(it, skipped) }
                sip008(root)?.let { return CatalogParse(it, skipped) }
                xray(root)?.let { return CatalogParse(it, skipped) }
            }
            clash(expanded)?.let { return CatalogParse(it, skipped) }
            return CatalogParse(links(expanded, skipped), skipped)
        }
        return CatalogParse(links(body, skipped), skipped)
    }

    fun isEncryptedHydra(content: String): Boolean = decodeJson(content.trim())
        ?.let { it is JsonObject && it.containsKey("protected") && !it.containsKey("resources") } == true

    // --- Hydra v2 -----------------------------------------------------------------

    private fun hydra(root: JsonElement, skipped: MutableList<String>): OutboundCatalog? {
        val document = root as? JsonObject ?: return null
        val api = document["api_version"]?.jsonPrimitive?.contentOrNull
        if (api != HYDRA_API_VERSION && document["kind"]?.jsonPrimitive?.contentOrNull != "Subscription") return null
        if (document.containsKey("protected") && !document.containsKey("resources")) {
            error("encrypted Hydra subscriptions must be opened by the core first")
        }
        val resources = document["resources"] as? JsonArray ?: error("Hydra subscription has no resources")
        require(resources.isNotEmpty()) { "Hydra subscription has no resources" }

        // A profile points at one tag inside one resource. Both halves matter: two resources
        // may name a server the same way, and reading the tag alone made a profile in one of
        // them offer a server from the other.
        val entrypoints = mutableMapOf<String, MutableSet<String>>()
        val labels = mutableMapOf<String, MutableMap<String, String>>()
        var defaultKey: Pair<String, String>? = null
        val defaultProfile = document["default_profile"]?.jsonPrimitive?.contentOrNull
        (document["profiles"] as? JsonArray).orEmptyArray().forEach { entry ->
            val profile = entry as? JsonObject ?: return@forEach
            if (profile["enabled"]?.jsonPrimitive?.contentOrNull == "false") return@forEach
            val entrypoint = profile["entrypoint"] as? JsonObject ?: return@forEach
            val tag = entrypoint["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val resource = profile["resource"]?.jsonPrimitive?.contentOrNull.orEmpty()
            entrypoints.getOrPut(resource) { mutableSetOf() } += tag
            // The profile's name is the one the provider wrote for a person to read; the
            // entrypoint tag underneath it is ours to run and nobody else's to look at.
            localized(profile["name"])?.let { labels.getOrPut(resource) { mutableMapOf() }[tag] = it }
            val id = profile["id"]?.jsonPrimitive?.contentOrNull
            if (defaultKey == null || (defaultProfile != null && id == defaultProfile)) {
                defaultKey = resource to tag
            }
        }
        val named = entrypoints.values.any(Set<String>::isNotEmpty)

        val collected = mutableListOf<CatalogOutbound>()
        val taken = mutableSetOf<String>()
        var defaultTag: String? = null
        resources.forEach { entry ->
            val resource = entry as? JsonObject ?: return@forEach
            val scope = resource["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val body = resource["document"] as? JsonObject ?: return@forEach
            val sections = listOf("outbounds", "endpoints").map { section ->
                section to (body[section] as? JsonArray).orEmptyArray().mapNotNull { it as? JsonObject }
            }
            // One configuration cannot hold two outbounds with the same tag — the core refuses
            // the whole document. Dropping the second one silently lost a server, so a
            // colliding tag is renamed within its resource instead, and every reference to it
            // in that same resource is renamed with it.
            val renames = mutableMapOf<String, String>()
            sections.forEach { (_, outbounds) ->
                outbounds.forEach { outbound ->
                    val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val unique = generateSequence(0) { it + 1 }
                        .map { attempt -> if (attempt == 0) tag else "$tag@$scope" + if (attempt > 1) "-$attempt" else "" }
                        .first { taken.add(it) }
                    if (unique != tag) renames[tag] = unique
                }
            }
            sections.forEach { (section, outbounds) ->
                outbounds.forEach { outbound ->
                    val tag = outbound["tag"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val type = outbound["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    collected += CatalogOutbound(
                        tag = renames[tag] ?: tag,
                        type = type.ifEmpty { if (section == "endpoints") "endpoint" else "unknown" },
                        json = if (renames.isEmpty()) outbound else rename(outbound, renames),
                        scope = scope,
                        endpoint = section == "endpoints",
                        // Everything is embedded so detour chains keep resolving, but only
                        // an entrypoint — or any real server, when the document names no
                        // profiles — is offered as a choice.
                        selectable = if (!named) type !in metaTypes else tag in entrypoints[scope].orEmpty(),
                        label = labels[scope]?.get(tag),
                    )
                    if (defaultKey == (scope to tag)) defaultTag = renames[tag] ?: tag
                }
            }
        }
        val usable = resolveReferences(collected, skipped)
        require(usable.any(CatalogOutbound::selectable)) { "Hydra subscription has no usable entrypoint" }
        return OutboundCatalog(SubscriptionDocumentFormat.HYDRA, usable, defaultTag)
    }

    // --- plain sing-box -----------------------------------------------------------

    private fun singbox(root: JsonElement, skipped: MutableList<String>): OutboundCatalog? {
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
                if (!seen.add(tag)) return@forEach
                collected += CatalogOutbound(
                    tag = tag,
                    type = type.ifEmpty { "unknown" },
                    json = outbound,
                    selectable = type.isNotEmpty() && type !in metaTypes,
                    endpoint = section == "endpoints",
                )
            }
        }
        return resolveReferences(collected, skipped).takeIf { list -> list.any(CatalogOutbound::selectable) }
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

    private fun links(body: String, skipped: MutableList<String>): OutboundCatalog {
        val parsed = body.replace(" -> ", "\n").lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        val wireGuard = body.trimStart().startsWith("[Interface]")
        val sources = if (wireGuard) listOf(body) else parsed
        val taken = mutableSetOf<String>()
        val collected = sources.mapIndexedNotNull { index, value ->
            val link = runCatching { SubscriptionParser.parse(value) }
                .onFailure { failure ->
                    // The scheme, never the line: a share link carries a credential.
                    val scheme = value.substringBefore("://", "").lowercase().take(24)
                    skipped += "${scheme.ifEmpty { "line ${index + 1}" }}: ${failure.message ?: "unrecognised"}"
                }
                .getOrNull() ?: return@mapIndexedNotNull null
            val preferred = link.name.trim().takeIf(String::isNotEmpty) ?: "${link.server}-${link.port}"
            // Providers repeat a name across servers, and two outbounds sharing one tag is a
            // configuration the core refuses outright.
            val tag = generateSequence(0) { it + 1 }
                .map { suffix -> if (suffix == 0) preferred else "$preferred ($suffix)" }
                .first(taken::add)
            CatalogOutbound(
                tag = tag,
                type = ShareLinkOutbound.typeOf(link),
                json = ShareLinkOutbound.toJson(link, tag),
                endpoint = ShareLinkOutbound.isEndpoint(link),
            )
        }
        require(collected.isNotEmpty()) { "no usable outbound in subscription" }
        return OutboundCatalog(SubscriptionDocumentFormat.UNKNOWN, collected)
    }

    // --- references ---------------------------------------------------------------

    /** Every tag one outbound points at: what it dials through, and what its group holds. */
    private fun referencesOf(outbound: CatalogOutbound): Set<String> = buildSet {
        (outbound.json["detour"] as? JsonPrimitive)?.contentOrNull?.let(::add)
        (outbound.json["outbounds"] as? JsonArray)?.forEach { member ->
            (member as? JsonPrimitive)?.contentOrNull?.let(::add)
        }
        remove(outbound.tag)
    }

    /**
     * Keeps what the document can actually stand behind.
     *
     * A group — a `selector` or a `urltest` — used to be dropped on sight while everything else
     * was kept exactly as written. An outbound that dialled through one of those groups was then
     * imported with a `detour` naming a tag that no longer existed anywhere, and the core refuses
     * a document with a dangling detour whole: one such outbound took every other server in the
     * subscription down with it.
     *
     * So groups are kept when something reaches them, and anything whose references cannot be
     * satisfied is dropped and named. A group nothing reaches is left out: it is the provider's
     * own entry point, not a dependency, and embedding it would list every server in it — which
     * is also what would keep an idle VK transport in the configuration it is deliberately left
     * out of.
     */
    private fun resolveReferences(
        collected: List<CatalogOutbound>,
        skipped: MutableList<String>,
    ): List<CatalogOutbound> {
        var present = collected.map(CatalogOutbound::tag).toMutableSet()
        var kept = collected
        while (true) {
            val broken = kept.filter { outbound -> referencesOf(outbound).any { it !in present } }
            if (broken.isEmpty()) break
            broken.forEach { outbound ->
                val missing = referencesOf(outbound).filterNot { it in present }
                skipped += "${outbound.tag}: dials ${missing.joinToString()}, which the document does not contain"
            }
            val gone = broken.map(CatalogOutbound::tag).toSet()
            kept = kept.filterNot { it.tag in gone }
            present = kept.map(CatalogOutbound::tag).toMutableSet()
        }
        // Servers are all kept, as they always were — a detour may name any of them. Only the
        // groups are filtered, and only to what something actually reaches.
        val groups = kept.filter { it.type in groupTypes }.map(CatalogOutbound::tag).toSet()
        if (groups.isEmpty()) return kept
        val byTag = kept.associateBy(CatalogOutbound::tag)
        val reachable = mutableSetOf<String>()
        val pending = ArrayDeque(
            kept.filter { it.tag !in groups || it.selectable }.map(CatalogOutbound::tag),
        )
        while (true) {
            val tag = pending.removeFirstOrNull() ?: break
            if (!reachable.add(tag)) continue
            byTag[tag]?.let { outbound -> pending += referencesOf(outbound) }
        }
        return kept.filter { it.tag !in groups || it.tag in reachable }
    }

    /** Outbound types that describe a choice between other outbounds rather than a server. */
    private val groupTypes = setOf("selector", "urltest")

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

    /**
     * A Hydra name: either a string, or a map of translations with a `default`. The same shape
     * the subscription's own `display.name` uses, and the same rule — a translation the app
     * cannot pick between is still better than showing the tag underneath it.
     */
    private fun localized(value: JsonElement?): String? {
        if (value == null) return null
        (value as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)?.let { return it }
        val translations = value as? JsonObject ?: return null
        return translations["default"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: translations.values.firstNotNullOfOrNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
    }

    /**
     * One outbound with its own tag and its references renamed.
     *
     * A `detour` names a sibling, and a group names its members. Renaming the sibling without
     * renaming the reference leaves the core with a detour to a tag that does not exist, and it
     * refuses the whole document for that — every server in the subscription with it. Only the
     * three keys that hold tags are touched; `alpn` or `address` are left alone.
     */
    private fun rename(outbound: JsonObject, renames: Map<String, String>): JsonObject = buildJsonObject {
        outbound.forEach { (key, value) ->
            when (key) {
                "tag", "detour" -> {
                    val current = (value as? JsonPrimitive)?.contentOrNull
                    if (current == null) put(key, value) else put(key, JsonPrimitive(renames[current] ?: current))
                }
                "outbounds" -> {
                    val members = value as? JsonArray
                    if (members == null) {
                        put(key, value)
                    } else {
                        putJsonArray(key) {
                            members.forEach { member ->
                                val text = (member as? JsonPrimitive)?.contentOrNull
                                if (text == null) add(member) else add(JsonPrimitive(renames[text] ?: text))
                            }
                        }
                    }
                }
                else -> put(key, value)
            }
        }
    }
}
