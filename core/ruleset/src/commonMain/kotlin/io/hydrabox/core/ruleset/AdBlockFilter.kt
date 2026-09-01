package io.hydrabox.core.ruleset

/**
 * Turns an AdGuard-style filter list into the two domain sets a rule set needs.
 *
 * Carried over from HydraBox 1.x's `_parseAdBlockFilter`/`_extractDomain`: the source is a
 * hosts-and-filter-syntax mixture, and only the part of it that is a plain domain can become
 * a rule set. Cosmetic rules, regular-expression rules and `$badfilter` lines are skipped
 * rather than half-understood, and an allow rule wins over a block rule for the same domain.
 */
object AdBlockFilter {
    data class Lists(val blocked: List<String>, val allowed: List<String>)

    fun parse(content: String): Lists {
        val blocked = linkedSetOf<String>()
        val allowed = linkedSetOf<String>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("#") || line.startsWith("[")) return@forEach
            val allow = line.startsWith("@@")
            val domain = domainOf(line) ?: return@forEach
            if (allow) allowed += domain else blocked += domain
        }
        blocked -= allowed
        return Lists(blocked.sorted(), allowed.sorted())
    }

    private fun domainOf(line: String): String? {
        var value = line.trim()
        if (value.startsWith("@@")) value = value.substring(2)
        if (value.contains("\$badfilter") || value.startsWith("/") ||
            value.contains("##") || value.contains("#@#") || value.contains("#?#")
        ) {
            return null
        }
        hostsLine.matchEntire(value)?.let { return normalize(it.groupValues[1]) }
        value.indexOf('$').takeIf { it >= 0 }?.let { value = value.substring(0, it) }
        value = value.trim()
            .replaceFirst(Regex("^\\|\\|"), "")
            .replaceFirst(Regex("^\\|https?://", RegexOption.IGNORE_CASE), "")
            .replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")
            .replaceFirst(Regex("^\\|"), "")
        if (value.startsWith("*.")) value = value.substring(2)
        value = value.trimStart('.')
        var end = value.length
        listOf('^', '/', '|', '*', '?', ',', '=').forEach { token ->
            val index = value.indexOf(token)
            if (index in 0 until end) end = index
        }
        value = value.substring(0, end).trim()
        if (value.isEmpty()) return null
        val colon = value.indexOf(':')
        if (colon > 0 && value.substring(colon + 1).all(Char::isDigit)) value = value.substring(0, colon)
        return normalize(value)
    }

    private fun normalize(value: String): String? {
        val domain = value.trim().lowercase().trim('.')
        if (domain.isEmpty() || !domain.contains('.') || domain.contains("..") || domain.contains(':')) return null
        if (ipv4.matches(domain)) return null
        if (!allowedCharacters.matches(domain)) return null
        return domain
    }

    private val hostsLine = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::|::1)\\s+([A-Za-z0-9.\\-_]+)$", RegexOption.IGNORE_CASE)
    private val ipv4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    private val allowedCharacters = Regex("^[a-z0-9.\\-]+$")
}
