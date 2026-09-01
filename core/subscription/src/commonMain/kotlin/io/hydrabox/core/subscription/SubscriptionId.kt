package io.hydrabox.core.subscription

/**
 * Identifiers for stored subscriptions.
 *
 * Carried over from HydraBox 1.x (`lib/data/subscription/subscription_storage_id.dart`) with
 * the same rule and the same reason: an id is an internal storage namespace, not a label the
 * provider chose. Keeping it narrow is what stops an imported backup from writing over a
 * schema marker or a payload-generation key.
 */
object SubscriptionId {
    const val MAX_LENGTH = 128
    private const val SCHEMA_VERSION_KEY = "__hydra_storage_schema_version__"
    private const val PAYLOAD_SEPARATOR = "::payload::"
    private val allowed = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun isSafe(value: String): Boolean = value.isNotEmpty() &&
        value.length <= MAX_LENGTH &&
        value != SCHEMA_VERSION_KEY &&
        !value.contains(PAYLOAD_SEPARATOR) &&
        allowed.matches(value)

    fun validate(value: String): String {
        require(isSafe(value)) { "invalid subscription storage id" }
        return value
    }

    /**
     * A stable identifier for a source: the same address gives the same id, so adding a link
     * twice cannot produce two entries that then drift apart. A counter would: after removing
     * the first of two sources, the next add would reuse the second one's id.
     */
    fun of(source: String, existing: Set<String> = emptySet()): String {
        val normalized = source.trim().substringBefore('#').lowercase()
        val digest = normalized.fold(0x811c9dc5.toInt()) { hash, char ->
            (hash xor char.code) * 0x01000193
        }
        val base = "sub-" + digest.toUInt().toString(16).padStart(8, '0')
        if (base !in existing) return base
        var index = 2
        while ("$base-$index" in existing) index += 1
        return "$base-$index"
    }
}
