package io.hydrabox.core.ruleset

/**
 * Writes a sing-box binary rule-set (`.srs`).
 *
 * HydraBox 1.x compiled these itself rather than shipping them
 * (`lib/data/adblock/ad_block_rule_set_service.dart`), and it has to stay that way: the
 * source lists are hosts files of a few hundred thousand domains, and the core will only
 * load the binary form. The format is the one sing-box reads: the magic `SRS`, format
 * version 2, then a zlib-compressed body holding one default rule whose only item is a
 * domain matcher — a succinct trie over the reversed domains.
 *
 * The encoding is byte-for-byte the same as 1.x's, which is what lets a rule set compiled by
 * either version load in either.
 */
object RuleSetWriter {
    /** The compression step, which every platform already has and no platform shares. */
    fun write(domains: Collection<String>, compress: (ByteArray) -> ByteArray): ByteArray {
        val body = ByteAccumulator()
        body.uvarint(if (domains.isEmpty()) 0 else 1)
        if (domains.isNotEmpty()) defaultDomainRule(body, domains)
        val out = ByteAccumulator()
        out.bytes(MAGIC)
        out.byte(FORMAT_VERSION)
        out.bytes(compress(body.toByteArray()))
        return out.toByteArray()
    }

    private fun defaultDomainRule(writer: ByteAccumulator, domains: Collection<String>) {
        writer.byte(0)
        writer.byte(RULE_ITEM_DOMAIN)
        succinctSet(prepare(domains)).writeTo(writer)
        writer.byte(RULE_ITEM_FINAL)
        writer.byte(0)
    }

    /**
     * Domains go in reversed and prefixed with a root marker, so that a suffix match becomes
     * a prefix match in the trie — `example.com` matching `ads.example.com`.
     */
    private fun prepare(domains: Collection<String>): List<String> = domains.asSequence()
        .filter(String::isNotEmpty)
        .distinct()
        .map { domain -> (ROOT_LABEL_MARKER + domain).reversed() }
        .sorted()
        .toList()

    private class QueueEntry(val start: Int, val end: Int, val column: Int)

    private class SuccinctSet(
        val leaves: List<Long>,
        val labelBitmap: List<Long>,
        val labels: ByteArray,
    ) {
        fun writeTo(writer: ByteAccumulator) {
            writer.byte(0)
            writer.uint64List(leaves)
            writer.uint64List(labelBitmap)
            writer.byteList(labels)
        }
    }

    /** The trie, as sing-box stores it: one bit per leaf, one bit per label boundary. */
    private fun succinctSet(keys: List<String>): SuccinctSet {
        val leaves = mutableListOf<Long>()
        val labelBitmap = mutableListOf<Long>()
        val labels = mutableListOf<Byte>()
        var labelIndex = 0
        val queue = mutableListOf(QueueEntry(0, keys.size, 0))
        var index = 0
        while (index < queue.size) {
            val entry = queue[index]
            index += 1
            if (entry.start >= entry.end) continue
            var start = entry.start
            if (entry.column == keys[start].length) {
                start += 1
                setBit(leaves, index - 1)
            }
            var cursor = start
            while (cursor < entry.end) {
                val from = cursor
                val current = keys[from][entry.column]
                while (cursor < entry.end && keys[cursor][entry.column] == current) cursor += 1
                queue += QueueEntry(from, cursor, entry.column + 1)
                labels += current.code.toByte()
                labelIndex += 1
            }
            setBit(labelBitmap, labelIndex)
            labelIndex += 1
        }
        return SuccinctSet(leaves, labelBitmap, labels.toByteArray())
    }

    private fun setBit(bitmap: MutableList<Long>, index: Int) {
        val word = index ushr 6
        while (bitmap.size <= word) bitmap += 0L
        bitmap[word] = bitmap[word] or (1L shl (index and 63))
    }

    private val MAGIC = byteArrayOf(0x53, 0x52, 0x53)
    private const val FORMAT_VERSION = 2
    private const val RULE_ITEM_DOMAIN = 2
    private const val RULE_ITEM_FINAL = 0xFF
    private const val ROOT_LABEL_MARKER = "\n"
}

internal class ByteAccumulator {
    private val buffer = mutableListOf<Byte>()

    fun byte(value: Int) {
        buffer += (value and 0xFF).toByte()
    }

    fun bytes(values: ByteArray) {
        values.forEach { buffer += it }
    }

    fun uvarint(value: Int) {
        var current = value
        while (current >= 0x80) {
            byte((current and 0xFF) or 0x80)
            current = current shr 7
        }
        byte(current and 0xFF)
    }

    fun uint64List(values: List<Long>) {
        uvarint(values.size)
        values.forEach { value ->
            for (shift in 56 downTo 0 step 8) byte(((value ushr shift) and 0xFF).toInt())
        }
    }

    fun byteList(values: ByteArray) {
        uvarint(values.size)
        bytes(values)
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
