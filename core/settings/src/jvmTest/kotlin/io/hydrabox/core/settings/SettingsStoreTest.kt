package io.hydrabox.core.settings

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.hydrabox.core.diagnostics.Secret
import io.hydrabox.core.storage.SecretFieldCipher
import io.hydrabox.core.storage.SecretFieldCodec
import io.hydrabox.core.storage.StorageDatabase
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class SettingsStoreTest {
    @Test fun `clearing password deletes the stored secret`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(StorageDatabase.Schema::create)
        val cipher = SecretFieldCodec(FixedCipher)
        val store = SettingsStore(StorageDatabase(driver), cipher, cipher)
        val initial = SettingsCodec().decode(emptyMap()).copy(proxyPassword = Secret.of("secret"))
        store.save(initial)
        store.save(initial.copy(proxyPassword = null))
        kotlin.test.assertNull(store.load().proxyPassword)
    }

    @Test fun `settings use SQLDelight and keep proxy password encrypted`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(StorageDatabase.Schema::create)
        val cipher = SecretFieldCodec(FixedCipher)
        val store = SettingsStore(StorageDatabase(driver), cipher, cipher)
        store.save(SettingsCodec().decode(emptyMap()).copy(proxyPassword = Secret.of("LocalOnlyPassword123456")))

        val encrypted = StorageDatabase(driver).storageDatabaseQueries.selectSecretValue("proxy_password") { requireNotNull(it) }.executeAsOne()
        assertFalse(encrypted.decodeToString().contains("LocalOnlyPassword123456"))
        assertContentEquals(encrypted, store.load().proxyPassword!!.sealWith(cipher))
    }
}

private object FixedCipher : SecretFieldCipher {
    override fun encrypt(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(ciphertext: ByteArray) = encrypt(ciphertext)
}
