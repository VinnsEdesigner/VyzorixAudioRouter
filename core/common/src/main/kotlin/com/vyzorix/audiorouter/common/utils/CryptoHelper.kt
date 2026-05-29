package com.vyzorix.audiorouter.common.utils

import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * Bridges [KeystoreManager] (master key) ↔ SQLCipher (database passphrase).
 *
 * Responsibilities (per doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.2 and
 * the diagram in §1):
 *  1. On first run, generate a random 256-bit database passphrase, seal it
 *     under the master key, and persist the sealed hex in [prefs].
 *  2. On subsequent runs, unseal the persisted blob to recover the passphrase.
 *
 * Important: this class returns the *plaintext* passphrase as a `ByteArray`.
 * The caller (typically `SecureSupportHelper` in `:core:data`) is responsible
 * for passing those bytes straight to SQLCipher and immediately zeroing the
 * local reference. Layer 1 callers do this; future call sites must too.
 *
 * The choice to keep the passphrase as random bytes (rather than a
 * user-derived password) avoids a slow PBKDF2 step on every cold start —
 * the C22's eMMC + Unisoc CPU combination is slow enough that derivation on
 * every open visibly extended boot time during the prototype phase.
 */
public class CryptoHelper(
    private val keystoreManager: KeystoreManager,
    private val prefs: SharedPreferences,
) {

    /**
     * Returns the SQLCipher passphrase bytes (32 bytes). Generates and seals
     * a new passphrase if none has been provisioned yet.
     */
    public fun loadOrProvisionDatabasePassphrase(): ByteArray {
        val sealedHex = prefs.getString(KEY_DB_PASSPHRASE_SEALED, null)
        if (sealedHex != null) {
            return keystoreManager.unseal(sealedHex)
        }
        val fresh = ByteArray(PASSPHRASE_BYTES).also(SecureRandom()::nextBytes)
        val sealed = keystoreManager.seal(fresh)
        prefs.edit().putString(KEY_DB_PASSPHRASE_SEALED, sealed).apply()
        return fresh
    }

    /**
     * Wipes the persisted sealed passphrase. The next call to
     * [loadOrProvisionDatabasePassphrase] will generate a fresh one — only
     * useful in destructive-reset paths (e.g. safe-mode wipe), since the
     * existing database becomes unreadable without the original passphrase.
     */
    public fun clearDatabasePassphrase() {
        prefs.edit().remove(KEY_DB_PASSPHRASE_SEALED).apply()
    }

    public companion object {
        public const val DEFAULT_PREFS_NAME: String = "vyzorix_db_passphrase"
        public const val KEY_DB_PASSPHRASE_SEALED: String = "db_passphrase_sealed"
        public const val PASSPHRASE_BYTES: Int = 32
    }
}
