package io.hydrabox.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual class StorageContext(val context: Context)

/**
 * Opens the shared database in write-ahead logging mode.
 *
 * Both processes open this file: the interface writes subscriptions and the selection, `:core`
 * appends the journal. In the default rollback-journal mode a writer holds an exclusive lock for
 * the whole transaction, so a read model being assembled in the interface waited on the core's
 * journal flush and the core's flush waited on it. WAL lets readers carry on against the last
 * committed state, and it turns each commit into an append to the log instead of a journal file
 * created, fsynced and deleted.
 *
 * The pragma is issued in `onConfigure`, before any schema work, because the mode is a property
 * of the database file rather than of a connection.
 */
actual fun openStorageDriver(context: StorageContext, databaseName: String): SqlDriver =
    AndroidSqliteDriver(
        schema = StorageDatabase.Schema,
        context = context.context,
        name = databaseName,
        callback = object : AndroidSqliteDriver.Callback(StorageDatabase.Schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                runCatching { db.query("PRAGMA journal_mode=WAL").use { it.moveToFirst() } }
            }
        },
    )

actual fun platformSecretFieldCipher(driver: SqlDriver): SecretFieldCipher =
    AesGcmFieldCipher(androidKey())

private fun androidKey(): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        "AndroidKeyStore",
    ).run {
        init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        generateKey()
    }
}

private class AesGcmFieldCipher(private val key: SecretKey) : SecretFieldCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        iv + doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(ciphertext.size > GCM_IV_BYTES)
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ciphertext.copyOfRange(0, GCM_IV_BYTES)))
        doFinal(ciphertext.copyOfRange(GCM_IV_BYTES, ciphertext.size))
    }
}

private const val KEY_ALIAS = "hydrabox.storage.field.v1"
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
