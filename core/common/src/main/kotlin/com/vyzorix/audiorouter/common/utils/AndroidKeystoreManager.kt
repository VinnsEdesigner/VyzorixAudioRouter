package com.vyzorix.audiorouter.common.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed [KeystoreManager] using the `AndroidKeyStore` provider.
 *
 * The master key is an AES-256 key generated under [keyAlias] with
 * GCM/NoPadding purposes. The key never leaves the TEE/StrongBox once
 * generated — we only ever hand `Cipher.init` a reference to it.
 *
 * Threat model and fallback rationale: see
 * doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §1 / §3.1 and
 * doc/NOKIA_C22_NOTES.md. The Nokia C22 (Unisoc SC9863A) is expected to
 * fall back to [SoftwareKeystoreManager] via [KeystoreManagerFactory] — this
 * class is the canonical path for Qualcomm / MediaTek silicon.
 */
public class AndroidKeystoreManager internal constructor(
    private val keyAlias: String,
) : KeystoreManager {

    override val isHardwareBacked: Boolean = true

    override fun seal(plaintext: ByteArray): String {
        return try {
            HexCodec.encode(AesGcm.encrypt(loadOrCreateKey(), plaintext))
        } catch (t: Throwable) {
            throw KeystoreFailureException("AndroidKeystore seal failed", t)
        }
    }

    override fun unseal(sealed: String): ByteArray {
        return try {
            AesGcm.decrypt(loadOrCreateKey(), HexCodec.decode(sealed))
        } catch (t: Throwable) {
            throw KeystoreFailureException("AndroidKeystore unseal failed", t)
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    public companion object {
        public const val DEFAULT_KEY_ALIAS: String = "vyzorix-master-key-v1"
        private const val ANDROID_KEY_STORE: String = "AndroidKeyStore"
        private const val KEY_SIZE_BITS: Int = 256

        // Kept here so callers don't have to depend on AesGcm directly when
        // they want to know the IV size we emit.
        @Suppress("unused")
        public const val GCM_IV_BYTES: Int = AesGcm.IV_BYTES

        @Suppress("unused")
        public val GCM_TAG_SPEC_BITS: Int = GCMParameterSpec(128, ByteArray(GCM_IV_BYTES)).tLen
    }
}
