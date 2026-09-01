package io.hydrabox.core.subscription

/**
 * The slice of YAML a Clash subscription actually uses.
 *
 * HydraBox 1.x parsed these with a full YAML library; `commonMain` has none, and pulling
 * one in for a single document format costs more than the subset does. What providers emit
 * is block mappings, block sequences, flow mappings and flow sequences of scalars — no
 * anchors, no multi-line scalars, no tags — so that is exactly what this reads, and
 * anything else fails the import loudly rather than half-parsing it.
 */
internal sealed interface YamlNode {
    data class Scalar(val value: String) : YamlNode
    data class Mapping(val entries: Map<String, YamlNode>) : YamlNode
    data class Sequence(val items: List<YamlNode>) : YamlNode
}

internal fun YamlNode?.scalar(): String? = (this as? YamlNode.Scalar)?.value?.takeIf(String::isNotEmpty)
internal fun YamlNode?.mapping(): Map<String, YamlNode> = (this as? YamlNode.Mapping)?.entries ?: emptyMap()
internal fun YamlNode?.sequence(): List<YamlNode> = (this as? YamlNode.Sequence)?.items ?: emptyList()
internal fun YamlNode?.flag(): Boolean = scalar()?.lowercase() in setOf("true", "yes", "on", "1")

internal object MiniYaml {
    fun parse(document: String): YamlNode.Mapping {
        val lines = document.lines()
            .map { it.substringBefore(" #").trimEnd() }
            .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
        val cursor = Cursor(lines)
        return YamlNode.Mapping(block(cursor, indentOf(lines.firstOrNull() ?: "")))
    }

    private class Cursor(val lines: List<String>) {
        var index = 0
        fun peek(): String? = lines.getOrNull(index)
    }

    private fun indentOf(line: String) = line.length - line.trimStart().length

    /** One block mapping: every line at [indent] that is `key:` or `key: value`. */
    private fun block(cursor: Cursor, indent: Int): Map<String, YamlNode> {
        val entries = linkedMapOf<String, YamlNode>()
        while (true) {
            val line = cursor.peek() ?: break
            val lineIndent = indentOf(line)
            if (lineIndent < indent) break
            val trimmed = line.trim()
            if (lineIndent > indent || trimmed.startsWith("- ")) break
            val key = trimmed.substringBefore(':').trim().removeSurrounding("\"").removeSurrounding("'")
            if (!trimmed.contains(':')) break
            val inline = trimmed.substringAfter(':').trim()
            cursor.index += 1
            entries[key] = if (inline.isNotEmpty()) flow(inline) else nested(cursor, indent)
        }
        return entries
    }

    /** What follows a bare `key:` — a deeper mapping, a sequence, or nothing. */
    private fun nested(cursor: Cursor, parentIndent: Int): YamlNode {
        val next = cursor.peek() ?: return YamlNode.Scalar("")
        val nextIndent = indentOf(next)
        if (nextIndent <= parentIndent && !next.trim().startsWith("- ")) return YamlNode.Scalar("")
        return if (next.trim().startsWith("- ")) {
            YamlNode.Sequence(sequence(cursor, nextIndent))
        } else {
            YamlNode.Mapping(block(cursor, nextIndent))
        }
    }

    private fun sequence(cursor: Cursor, indent: Int): List<YamlNode> {
        val items = mutableListOf<YamlNode>()
        while (true) {
            val line = cursor.peek() ?: break
            if (indentOf(line) != indent || !line.trim().startsWith("- ")) break
            val body = line.trim().removePrefix("- ").trim()
            cursor.index += 1
            items += when {
                body.startsWith("{") -> flow(body)
                body.contains(':') && !body.startsWith("\"") && !body.startsWith("'") -> {
                    // A block mapping that started on the dash line: its first entry is
                    // here, the rest are indented under it.
                    val first = body.substringBefore(':').trim()
                    val value = body.substringAfter(':').trim()
                    val rest = cursor.peek()?.let { next ->
                        if (indentOf(next) > indent) block(cursor, indentOf(next)) else emptyMap()
                    } ?: emptyMap()
                    YamlNode.Mapping(linkedMapOf<String, YamlNode>().apply { put(first, flow(value)); putAll(rest) })
                }
                else -> flow(body)
            }
        }
        return items
    }

    /** A scalar, a flow mapping `{a: b, c: d}` or a flow sequence `[a, b]`. */
    private fun flow(raw: String): YamlNode {
        val value = raw.trim()
        return when {
            value.startsWith("{") && value.endsWith("}") -> YamlNode.Mapping(
                splitFlow(value.removeSurrounding("{", "}")).mapNotNull { part ->
                    val key = part.substringBefore(':').trim().unquote()
                    val item = part.substringAfter(':', "").trim()
                    if (key.isEmpty()) null else key to flow(item)
                }.toMap(),
            )
            value.startsWith("[") && value.endsWith("]") -> YamlNode.Sequence(
                splitFlow(value.removeSurrounding("[", "]")).map { flow(it) },
            )
            else -> YamlNode.Scalar(value.unquote())
        }
    }

    /** Splits on commas that are not inside quotes or a nested flow collection. */
    private fun splitFlow(body: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var quote: Char? = null
        body.forEach { char ->
            when {
                quote != null -> {
                    if (char == quote) quote = null
                    current.append(char)
                }
                char == '"' || char == '\'' -> { quote = char; current.append(char) }
                char == '{' || char == '[' -> { depth += 1; current.append(char) }
                char == '}' || char == ']' -> { depth -= 1; current.append(char) }
                char == ',' && depth == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(char)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.map(String::trim).filter(String::isNotEmpty)
    }

    private fun String.unquote(): String = trim()
        .let { if (it.length >= 2 && it.first() == '"' && it.last() == '"') it.substring(1, it.length - 1) else it }
        .let { if (it.length >= 2 && it.first() == '\'' && it.last() == '\'') it.substring(1, it.length - 1) else it }
}
