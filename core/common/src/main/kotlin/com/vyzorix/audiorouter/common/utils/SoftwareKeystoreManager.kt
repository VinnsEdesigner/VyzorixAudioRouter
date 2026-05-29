package com.vyzorix.audiorouter.common.utils

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Software-only [KeystoreManager] fallback used on devices with unreliable
 * hardware Keystore (Unisoc SC9863A in the Nokia C22 — see
 * doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.1 and
 * doc/NOKIA_C22_NOTES.md).
 *
 * Key derivation:
 *  - Stable inputs: install-time UUID + per-install 16-byte salt.
 *  - PBKDF2WithHmacSHA256, 100 000 iterations, 256-bit output.
 *
 * Both inputs are generated once on first use and persisted in a private
 * SharedPreferences container ([prefsName]). The plaintext key is never
 * persisted — only the inputs to the derivation are stored, so an offline
 * dump of the file alone does not directly yield the key (an attacker would
 * still need to run PBKDF2). This is consistent with the threat model in
 * doc/COMMAND_SECURITY.md §1.
 *
 * This implementation is intentionally NOT bound to any hardware element;
 * [isHardwareBacked] returns `false`.
 */
public class SoftwareKeystoreManager internal constructor(
    private val prefs: SharedPreferences,
) : KeystoreManager {

    override val isHardwareBacked: Boolean = false

    override fun seal(plaintext: ByteArray): String {
        return try {
            HexCodec.encode(AesGcm.encrypt(deriveKey(), plaintext))
        } catch (t: Throwable) {
            throw KeystoreFailureException("Software keystore seal failed", t)
        }
    }

    override fun unseal(sealed: String): ByteArray {
        return try {
            AesGcm.decrypt(deriveKey(), HexCodec.decode(sealed))
        } catch (t: Throwable) {
            throw KeystoreFailureException("Software keystore unseal failed", t)
        }
    }

    private fun deriveKey(): SecretKey {
        val installUuid = prefs.getString(KEY_INSTALL_UUID, null) ?: generateAndStoreUuid()
        val salt = loadOrCreateSalt()
        val keyFactory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec = PBEKeySpec(
            installUuid.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_SIZE_BITS,
        )
        val derived = keyFactory.generateSecret(spec).encoded
        return SecretKeySpec(derived, "AES")
    }

    private fun generateAndStoreUuid(): String {
        val uuid = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_INSTALL_UUID, uuid).apply()
        return uuid
    }

    private fun loadOrCreateSalt(): ByteArray {
        val existing = prefs.getString(KEY_SALT_HEX, null)
        if (existing != null) return HexCodec.decode(existing)
        val fresh = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        prefs.edit().putString(KEY_SALT_HEX, HexCodec.encode(fresh)).apply()
        return fresh
    }

    public companion object {
        public const val DEFAULT_PREFS_NAME: String = "vyzorix_software_keystore"
        private const val KEY_INSTALL_UUID = "install_uuid"
        private const val KEY_SALT_HEX = "salt_hex"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 100_000
        private const val KEY_SIZE_BITS = 256
        private const val SALT_BYTES = 16

        /** Builds an instance over the daemon-default prefs container. */
        public fun create(context: Context): SoftwareKeystoreManager =
            create(context, DEFAULT_PREFS_NAME)

        /** Builds an instance over a named prefs container (useful for tests). */
        public fun create(context: Context, prefsName: String): SoftwareKeystoreManager {
            val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            return SoftwareKeystoreManager(prefs)
        }
    }
}
