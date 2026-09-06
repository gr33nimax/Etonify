package io.hydrabox.platform.android

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rule sets are published as whole generations behind one pointer, and old generations are
 * only removed once nobody holds them. These tests cover the part that decides deletion, because
 * getting it wrong pulls a file out from under a running core rather than merely wasting disk.
 */
class AdBlockRuleSetsTest {
    private val root = File(System.getProperty("java.io.tmpdir"), "adblock-${System.nanoTime()}")

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun generation(name: String, withAllow: Boolean = true): File =
        File(root, "generation-$name").apply {
            mkdirs()
            File(this, "adguard_dns_block.srs").writeBytes(byteArrayOf(1))
            if (withAllow) File(this, "adguard_dns_allow.srs").writeBytes(byteArrayOf(2))
        }

    @Test fun `a generation held by a lease survives collection`() {
        val old = generation("old")
        val current = generation("current")

        val lease = AdBlockRuleSets.acquire(old)
        assertNotNull(lease.paths, "a published generation must hand out its paths")
        try {
            AdBlockRuleSets.clean(root, current)
            assertTrue(old.isDirectory, "the generation a core is reading must not be deleted")
        } finally {
            lease.close()
        }

        AdBlockRuleSets.clean(root, current)
        assertFalse(old.isDirectory, "once the lease is closed the generation is collectable")
        assertTrue(current.isDirectory, "the published generation is never collected")
    }

    @Test fun `collection leaves the published generation and unrelated files alone`() {
        val current = generation("current")
        val stale = generation("stale")
        val stranger = File(root, "notes.txt").apply { writeText("keep me") }

        AdBlockRuleSets.clean(root, current)

        assertTrue(current.isDirectory)
        assertFalse(stale.isDirectory)
        assertTrue(stranger.isFile, "only generation directories are collected")
    }

    @Test fun `a generation with no compiled set hands out no paths and stays collectable`() {
        val empty = File(root, "generation-empty").apply { mkdirs() }
        val current = generation("current")

        val lease = AdBlockRuleSets.acquire(empty)
        assertNull(lease.paths, "without a block set the feature is unavailable, not half-on")
        lease.close()

        AdBlockRuleSets.clean(root, current)
        assertFalse(empty.isDirectory)
    }

    @Test fun `an allow set is optional and its absence is reported as no allow path`() {
        val only = generation("blockonly", withAllow = false)

        val lease = AdBlockRuleSets.acquire(only)
        try {
            val paths = assertNotNull(lease.paths)
            assertTrue(paths.block.endsWith("adguard_dns_block.srs"))
            assertNull(paths.allow)
        } finally {
            lease.close()
        }
    }
}
