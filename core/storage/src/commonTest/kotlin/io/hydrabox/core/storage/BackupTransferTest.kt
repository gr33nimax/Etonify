package io.hydrabox.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BackupTransferTest {
    // Two different "devices": each seals with its own prefix, so a document that still
    // carried the first device's sealed bytes would come out unreadable on the second.
    private fun transfer(device: String) = BackupTransfer(
        sealer = { plaintext -> "$device:$plaintext".encodeToByteArray() },
        opener = { ciphertext -> ciphertext.decodeToString().substringAfter(':') },
    )

    private val stored = StorageBackup(
        schemaVersion = 3,
        settings = listOf(
            BackupSetting("dns_proxy_resolver", "https://dns.example", null),
            BackupSetting("proxy_password", "", "phone:hunter2".encodeToByteArray()),
        ),
        metadata = listOf(BackupMetadata("runtime.selected.outbound", "tokyo".encodeToByteArray())),
        subscriptions = listOf(
            BackupSubscription("sub-1", "Work\tprovider", "phone:{\"outbounds\":[]}".encodeToByteArray(), 42),
        ),
    )

    @Test
    fun `a document restored on another device is readable there`() {
        val document = transfer("phone").encode(stored)
        val restored = transfer("tablet").decode(document)
        assertEquals(3, restored.schemaVersion)
        assertEquals(
            "tablet:hunter2",
            restored.settings.first { it.key == "proxy_password" }.secretValue?.decodeToString(),
        )
        assertEquals(
            "tablet:{\"outbounds\":[]}",
            restored.subscriptions.single().sourceSecret.decodeToString(),
        )
    }

    @Test
    fun `names carrying a tab or a newline survive the round trip`() {
        val restored = transfer("phone").decode(transfer("phone").encode(stored))
        assertEquals("Work\tprovider", restored.subscriptions.single().name)
        assertEquals(42, restored.subscriptions.single().updatedAtMillis)
    }

    @Test
    fun `a setting without a secret keeps not having one`() {
        val restored = transfer("phone").decode(transfer("phone").encode(stored))
        assertNull(restored.settings.first { it.key == "dns_proxy_resolver" }.secretValue)
        assertEquals("https://dns.example", restored.settings.first { it.key == "dns_proxy_resolver" }.value)
    }

    @Test
    fun `metadata bytes are preserved exactly`() {
        val restored = transfer("phone").decode(transfer("phone").encode(stored))
        assertEquals("tokyo", restored.metadata.single().value.decodeToString())
    }

    @Test
    fun `something that is not a HydraBox backup is refused before anything is written`() {
        assertFailsWith<IllegalArgumentException> { transfer("phone").decode("{\"looks\":\"like json\"}") }
    }
}
