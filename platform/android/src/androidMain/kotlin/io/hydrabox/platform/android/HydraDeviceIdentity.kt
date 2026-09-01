package io.hydrabox.platform.android

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.IDN
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The pseudonymous device identifier a Hydra subscription server is allowed to see.
 *
 * Carried over from HydraBox 1.x (`io/hydrabox/client/HydraDeviceIdentity.kt` and
 * `HydraDeviceIdPolicy.kt`) with the derivation unchanged, because the privacy policy the
 * app ships describes exactly this behaviour: the value is derived per origin, so two
 * providers cannot correlate one device; the raw `ANDROID_ID` never leaves this file; and
 * the fallback seed is wrapped with a keystore key. Keeping the derivation identical also
 * keeps a person's identifier stable when they move from 1.x to 2.0.
 */
internal object HydraDeviceIdentity {
    private const val KEY_STORE = "AndroidKeyStore"
    private const val WRAPPING_KEY_ALIAS = "hydrabox.hwid.seed.wrap.v1"
    private const val PREFERENCES_NAME = "hydrabox_device_identity"
    private const val WRAPPED_SEED = "wrapped_seed_v1"
    private const val SEED_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val DOMAIN_SEPARATOR = "hydrabox-hwid-v1"
    /** 1.x joined the fields with NUL, and the identifier has to hash to the same value. */
    private const val FIELD_SEPARATOR = '\u0000'

    @Synchronized
    fun forOrigin(context: Context, origin: String): String {
        val androidId = Settings.Secure
            .getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.trim().orEmpty()
        val component = androidId.ifEmpty {
            Base64.encodeToString(
                fallbackSeed(context),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
        }
        return derive(context.packageName, component, origin)
    }

    /** `https://host[:port]` and nothing else: the identifier must not vary by path. */
    fun canonicalHttpsOrigin(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "hydra device origin must use HTTPS" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "hydra device origin must not contain credentials, query or fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "hydra device origin must not contain a path" }
        val rawHost = requireNotNull(uri.host) { "hydra device origin host is missing" }
        val asciiHost = if (rawHost.contains(':')) {
            rawHost.lowercase()
        } else {
            IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase()
        }
        val host = if (asciiHost.contains(':') && !asciiHost.startsWith("[")) "[$asciiHost]" else asciiHost
        val port = uri.port
        require(port == -1 || port in 1..65535) { "hydra device origin port is invalid" }
        return if (port == -1 || port == 443) "https://$host" else "https://$host:$port"
    }

    fun derive(packageName: String, deviceComponent: String, origin: String): String {
        require(packageName.isNotBlank()) { "package name is missing" }
        require(deviceComponent.isNotBlank()) { "device identity is missing" }
        val canonical = canonicalHttpsOrigin(origin)
        val input = buildString {
            append(DOMAIN_SEPARATOR)
            append(FIELD_SEPARATOR)
            append(packageName)
            append(FIELD_SEPARATOR)
            append(deviceComponent)
            append(FIELD_SEPARATOR)
            append(canonical)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return "hbx1_" + Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun fallbackSeed(context: Context): ByteArray {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val key = wrappingKey()
        preferences.getString(WRAPPED_SEED, null)?.let { stored ->
            runCatching { unwrap(stored, key) }.getOrNull()?.takeIf { it.size == SEED_BYTES }?.let { return it }
        }
        val seed = ByteArray(SEED_BYTES).also(SecureRandom()::nextBytes)
        check(preferences.edit().putString(WRAPPED_SEED, wrap(seed, key)).commit()) {
            "failed to persist the HydraBox identity seed"
        }
        return seed
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    WRAPPING_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun wrap(seed: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(seed), Base64.NO_WRAP)
    }

    private fun unwrap(envelope: String, key: SecretKey): ByteArray {
        val decoded = Base64.decode(envelope, Base64.NO_WRAP)
        require(decoded.size > NONCE_BYTES) { "invalid wrapped identity seed" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoded.copyOfRange(0, NONCE_BYTES)))
        }
        return cipher.doFinal(decoded.copyOfRange(NONCE_BYTES, decoded.size))
    }
}
