package io.hydrabox.core.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a provider says about the subscription itself. 1.x read these headers and showed the
 * numbers; a client that ignores them cannot tell a person how much traffic is left.
 */
class SubscriptionMetadataTest {
    @Test
    fun `upload and download add up to what has been used`() {
        val metadata = SubscriptionMetadata.parse("upload=1024; download=3072; total=10737418240; expire=1767225600")
        assertEquals(4096, metadata.usedBytes)
        assertEquals(10737418240, metadata.totalBytes)
        assertEquals(1767225600, metadata.expiresAtEpochSeconds)
    }

    @Test
    fun `providers vary the separators and the case, and it still parses`() {
        val metadata = SubscriptionMetadata.parse("UPLOAD=10 , Download=20 ; Total=100")
        assertEquals(30, metadata.usedBytes)
        assertEquals(100, metadata.totalBytes)
    }

    @Test
    fun `a missing header is not an empty subscription`() {
        val metadata = SubscriptionMetadata.parse(null)
        assertTrue(metadata.empty)
        assertNull(metadata.usedBytes)
        assertNull(metadata.totalBytes)
    }

    @Test
    fun `zero total means no quota rather than a quota of nothing`() {
        assertNull(SubscriptionMetadata.parse("total=0; expire=0").totalBytes)
        assertNull(SubscriptionMetadata.parse("total=0; expire=0").expiresAtEpochSeconds)
    }

    @Test
    fun `only one direction reported still gives a used figure`() {
        assertEquals(512, SubscriptionMetadata.parse("download=512").usedBytes)
    }

    @Test
    fun `the profile title and update interval travel beside the counters`() {
        val metadata = SubscriptionMetadata.parse("total=1", title = " Provider ", updateInterval = "12")
        assertEquals("Provider", metadata.title)
        assertEquals(12, metadata.updateIntervalHours)
    }
}
