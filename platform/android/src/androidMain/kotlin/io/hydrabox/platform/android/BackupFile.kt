package io.hydrabox.platform.android

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup file as it leaves the device.
 *
 * The portable document carries subscription bodies and keys in the clear — it has to, or it
 * could not be restored anywhere else — so it is never written to storage as it is. The
 * person supplies a passphrase, the key is derived from it, and the file is useless without
 * it. The salt and the nonce travel with the file because they are not secret.
 */
object BackupFile {
    fun encrypt(document: String, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return MAGIC + salt + iv + cipher.doFinal(document.encodeToByteArray())
    }

    fun decrypt(file: ByteArray, passphrase: CharArray): String {
        require(file.size > MAGIC.size + SALT_BYTES + IV_BYTES) { "not_a_hydrabox_backup" }
        require(file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not_a_hydrabox_backup" }
        val salt = file.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = file.copyOfRange(MAGIC.size + SALT_BYTES, MAGIC.size + SALT_BYTES + IV_BYTES)
        val body = file.copyOfRange(MAGIC.size + SALT_BYTES + IV_BYTES, file.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(body).decodeToString()
    }

    private fun key(passphrase: CharArray, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)).encoded,
        "AES",
    )

    private val MAGIC = "HBK1".encodeToByteArray()
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val ITERATIONS = 210_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
