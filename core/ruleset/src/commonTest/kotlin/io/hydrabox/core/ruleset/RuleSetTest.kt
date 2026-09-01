package io.hydrabox.core.ruleset

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdBlockFilterTest {
    @Test
    fun `hosts lines, filter lines and plain domains all yield a domain`() {
        val lists = AdBlockFilter.parse(
            """
            ! title: test list
            # a comment
            0.0.0.0 ads.example.com
            ||tracker.example.net^
            https://beacon.example.org/pixel
            plain.example.io
            """.trimIndent(),
        )
        assertEquals(
            listOf("ads.example.com", "beacon.example.org", "plain.example.io", "tracker.example.net"),
            lists.blocked,
        )
    }

    @Test
    fun `an allow rule wins over a block rule for the same domain`() {
        val lists = AdBlockFilter.parse("||cdn.example.com^\n@@||cdn.example.com^")
        assertTrue(lists.blocked.isEmpty())
        assertEquals(listOf("cdn.example.com"), lists.allowed)
    }

    @Test
    fun `rules that are not about a whole domain are skipped, not guessed`() {
        val lists = AdBlockFilter.parse(
            """
            example.com##.ad-banner
            /ads/.*\.js/
            ||example.net^${'$'}badfilter
            0.0.0.0 127.0.0.1
            ::1 localhost
            """.trimIndent(),
        )
        assertTrue(lists.blocked.isEmpty(), "unexpected: ${lists.blocked}")
    }

    @Test
    fun `a port, a leading wildcard and a trailing dot do not change the domain`() {
        assertEquals(listOf("example.com"), AdBlockFilter.parse("||example.com:8080^").blocked)
        assertEquals(listOf("example.com"), AdBlockFilter.parse("*.example.com").blocked)
        assertEquals(listOf("example.com"), AdBlockFilter.parse("example.com.").blocked)
    }
}

class RuleSetWriterTest {
    private fun identity(bytes: ByteArray) = bytes

    @Test
    fun `the file starts with the magic and the format version sing-box expects`() {
        val bytes = RuleSetWriter.write(listOf("example.com"), ::identity)
        assertContentEquals(byteArrayOf(0x53, 0x52, 0x53, 0x02), bytes.copyOfRange(0, 4))
    }

    @Test
    fun `an empty set is a valid rule set with no rules`() {
        val bytes = RuleSetWriter.write(emptyList(), ::identity)
        assertEquals(5, bytes.size)
        assertEquals(0, bytes[4].toInt())
    }

    @Test
    fun `one domain produces one default rule with a domain item`() {
        val bytes = RuleSetWriter.write(listOf("example.com"), ::identity)
        // uvarint rule count, then the rule: default marker, domain item, matcher, final.
        assertEquals(1, bytes[4].toInt())
        assertEquals(0, bytes[5].toInt())
        assertEquals(2, bytes[6].toInt())
        assertEquals(0xFF, bytes.last { it != 0.toByte() }.toInt() and 0xFF)
    }

    @Test
    fun `the trie is deterministic and independent of the input order`() {
        val one = RuleSetWriter.write(listOf("b.example.com", "a.example.com"), ::identity)
        val other = RuleSetWriter.write(listOf("a.example.com", "b.example.com"), ::identity)
        assertContentEquals(one, other)
    }

    @Test
    fun `a duplicate domain does not change the output`() {
        val once = RuleSetWriter.write(listOf("a.example.com"), ::identity)
        val twice = RuleSetWriter.write(listOf("a.example.com", "a.example.com"), ::identity)
        assertContentEquals(once, twice)
    }

    @Test
    fun `a shared suffix is stored once, so the file grows slower than the list`() {
        val ten = RuleSetWriter.write((1..10).map { "node$it.example.com" }, ::identity).size
        val twenty = RuleSetWriter.write((1..20).map { "node$it.example.com" }, ::identity).size
        assertTrue(twenty < ten * 2, "expected sharing: $ten then $twenty")
    }
}
