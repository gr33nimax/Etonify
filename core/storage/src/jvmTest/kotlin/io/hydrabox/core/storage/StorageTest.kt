package io.hydrabox.core.storage

import io.hydrabox.core.diagnostics.Secret
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StorageTest {
    @Test fun `journal retains kinds by count despite interleaved ids`() {
        val queries = StorageDatabase(StorageTestDriver.currentVersion()).storageDatabaseQueries
        queries.appendJournal("event", 1, "info", "test", "first")
        repeat(10) { queries.appendJournal("trace", 2, "debug", "core", "trace") }
        queries.appendJournal("event", 3, "info", "test", "second")
        queries.trimJournal("event", 2)
        queries.trimJournal("trace", 3)
        assertEquals(listOf("first", "trace", "trace", "trace", "second"), queries.selectJournal().executeAsList().map { it.message })
    }

    @Test fun `migration from the previous schema preserves a setting`() {
        val driver = StorageTestDriver.previousVersion()

        StorageDatabase.Schema.migrate(driver, StorageDatabase.Schema.version - 1, StorageDatabase.Schema.version)

        val setting = StorageDatabase(driver).storageDatabaseQueries.selectAll().executeAsOne()
        assertEquals("theme", setting.setting_key)
        assertEquals("dark", setting.value_)
    }

    @Test fun `secret fields are never stored as plaintext`() {
        val secret = Secret.of("private-credential")
        val codec = SecretFieldCodec(FixedKeyProvider())
        val encrypted = secret.sealWith(codec)
        val driver = StorageTestDriver.currentVersion()
        val queries = StorageDatabase(driver).storageDatabaseQueries

        queries.upsertSetting("subscription", "private", encrypted)
        val stored = queries.selectSecretValue("subscription") { requireNotNull(it) }.executeAsOne()

        assertFalse(stored.decodeToString().contains("private-credential"))
        assertContentEquals(encrypted, Secret.openWith(encrypted, codec).sealWith(codec))
        assertFailsWith<IllegalStateException> { Secret.openWith(encrypted, SecretFieldCodec(NoKeyProvider)) }
    }

    @Test fun `backup round trip preserves every persisted field`() {
        val source = StorageDatabase(StorageTestDriver.currentVersion())
        source.storageDatabaseQueries.upsertSetting("theme", "dark", byteArrayOf(1))
        source.storageDatabaseQueries.upsertValue("marker", byteArrayOf(2))
        source.storageDatabaseQueries.upsertSubscription("s1", "Main", byteArrayOf(3), 42)

        val backup = BackupService(source).export()
        val target = StorageDatabase(StorageTestDriver.currentVersion())
        val result = BackupService(target).import(backup)

        assertEquals(3, assertIs<io.hydrabox.core.model.OperationState.Succeeded<BackupOutcome>>(result).value.entries)
        val restored = BackupService(target).export()
        assertEquals(backup.schemaVersion, restored.schemaVersion)
        assertEquals(backup.settings.map { it.key to it.value }, restored.settings.map { it.key to it.value })
        assertContentEquals(backup.settings.single().secretValue!!, restored.settings.single().secretValue!!)
        assertEquals(backup.metadata.map { it.key }, restored.metadata.map { it.key })
        assertContentEquals(backup.metadata.single().value, restored.metadata.single().value)
        assertEquals(backup.subscriptions.map { Triple(it.id, it.name, it.updatedAtMillis) }, restored.subscriptions.map { Triple(it.id, it.name, it.updatedAtMillis) })
        assertContentEquals(backup.subscriptions.single().sourceSecret, restored.subscriptions.single().sourceSecret)
    }

    @Test fun `previous schema backup migrates with empty subscriptions`() {
        val database = StorageDatabase(StorageTestDriver.currentVersion())
        val backup = StorageBackup(StorageDatabase.Schema.version - 1, listOf(BackupSetting("theme", "dark", null)), emptyList())

        assertIs<io.hydrabox.core.model.OperationState.Succeeded<BackupOutcome>>(BackupService(database).import(backup))
        assertEquals("dark", database.storageDatabaseQueries.selectAll().executeAsOne().value_)
        assertEquals(emptyList(), database.storageDatabaseQueries.selectSubscriptions().executeAsList())
    }
}

private object NoKeyProvider : SecretFieldCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = error("no key provider")
    override fun decrypt(ciphertext: ByteArray): ByteArray = error("no key provider")
}

private class FixedKeyProvider : SecretFieldCipher {
    override fun encrypt(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(ciphertext: ByteArray) = encrypt(ciphertext)
}
