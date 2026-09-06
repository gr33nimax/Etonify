package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The tags the generator writes itself. A subscription that claims one of them is not a bad
 * subscription: `auto` and `select` are ordinary names for a server, and a provider has no way
 * of knowing what this application calls its own groups.
 */
val RESERVED_TAGS = setOf(DIRECT_TAG, SELECTOR_TAG, AUTO_TAG)

/**
 * Makes every outbound tag unique across the whole configuration.
 *
 * A tag is an identity, and the core refuses a document that holds two of the same one. Two
 * things produced duplicates. Two subscriptions describing the same provider hand over the same
 * names — "same", "Germany", "🇳🇱 NL-1" — and each was correct on its own,
 * so the parser had nothing to complain about. And a server the provider happened to call
 * `auto`, `select` or `direct` collided with the groups this generator adds; the generator used
 * to drop those outbounds outright, which turned a subscription whose only server was called
 * `auto` into a configuration that routed everything directly with no proxy at all.
 *
 * So a colliding tag is renamed rather than dropped, and every reference to it inside its own
 * source is renamed with it: a `detour` or a group member pointing at a tag that no longer
 * exists is a document the core refuses whole, which takes every other server down with it.
 * References are rewritten within one scope only — a subscription cannot name a server in
 * another one, and treating a shared name as a shared reference is how one provider's detour
 * came to point at another provider's server.
 *
 * The first holder of a name keeps it. Renaming both would mean the surviving server changed
 * its identity whenever an unrelated source was added or removed, and that identity is what a
 * stored choice of server refers to.
 */
object OutboundTags {
    /**
     * [outbounds] with unique tags, plus the renames that were needed. [renames] is keyed by the
     * original tag and holds only names that were unambiguous before the pass, so a caller can
     * follow a stored choice of server across the rename; a name two sources both used cannot be
     * followed and is left out.
     */
    data class Normalized(
        val outbounds: List<CatalogOutbound>,
        val renames: Map<String, String> = emptyMap(),
    )

    fun normalize(outbounds: List<CatalogOutbound>): Normalized {
        val taken = RESERVED_TAGS.toMutableSet()
        // Per scope, because a reference may only name a sibling.
        val renamesByScope = mutableMapOf<String, MutableMap<String, String>>()
        val kept = mutableSetOf<String>()
        outbounds.forEach { outbound ->
            val unique = uniqueTag(outbound.tag, outbound.scope, taken)
            if (unique == outbound.tag) {
                kept += outbound.tag
                return@forEach
            }
            renamesByScope.getOrPut(outbound.scope) { mutableMapOf() }[outbound.tag] = unique
        }
        if (renamesByScope.isEmpty()) return Normalized(outbounds)
        // A name is followable only when nothing else answers to it any more and exactly one
        // source used it. Where the first holder kept the name — the ordinary collision — a
        // stored choice still resolves and must not be redirected to somebody else's server.
        val ambiguous = mutableSetOf<String>()
        val followable = mutableMapOf<String, String>()
        renamesByScope.values.forEach { renames ->
            renames.forEach { (original, unique) ->
                if (original in kept || followable.put(original, unique) != null) ambiguous += original
            }
        }
        return Normalized(
            outbounds = outbounds.map { outbound ->
                val renames = renamesByScope[outbound.scope].orEmpty()
                val unique = renames[outbound.tag] ?: outbound.tag
                if (renames.isEmpty()) {
                    outbound
                } else {
                    outbound.copy(
                        tag = unique,
                        json = rename(outbound.json, renames),
                        // The provider's own name for the server survives the rename; nobody
                        // should read `auto@sub-3f2a` on a screen.
                        label = outbound.label ?: outbound.tag.takeIf { unique != outbound.tag },
                        originTag = outbound.originTag ?: outbound.tag,
                    )
                }
            },
            renames = followable - ambiguous,
        )
    }

    /**
     * The first free name in `tag`, `tag@scope`, `tag@scope-2`… The scope is in the name because
     * it says which source the server came from, which is the only thing that distinguishes two
     * servers a person sees under one name.
     */
    private fun uniqueTag(tag: String, scope: String, taken: MutableSet<String>): String {
        val qualified = if (scope.isEmpty()) tag else "$tag@$scope"
        return generateSequence(0) { it + 1 }
            .map { attempt ->
                when (attempt) {
                    0 -> tag
                    1 -> qualified
                    else -> "$qualified-$attempt"
                }
            }
            .first(taken::add)
    }

    /**
     * One outbound with its own tag and its references renamed. Only the keys that carry a
     * tag are touched; `alpn`, `address` or anything else that happens to hold the same text is
     * left exactly as the subscription wrote it.
     */
    private fun rename(outbound: JsonObject, renames: Map<String, String>): JsonObject = buildJsonObject {
        outbound.forEach { (key, value) ->
            when (key) {
                "tag", "detour", "default" -> {
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
