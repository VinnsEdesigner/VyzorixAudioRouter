package com.vyzorix.audiorouter.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vyzorix.audiorouter.common.utils.SecretIntegrityException
import com.vyzorix.audiorouter.common.utils.TokenEncryptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Encrypted DataStore container for the per-device C2 `command_secret`.
 *
 * Implements the API surface pinned in
 * doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.9 verbatim:
 *
 * ```kotlin
 * suspend fun put(secret: String)
 * suspend fun getSecret(): String?
 * suspend fun clear()
 * val hasSecret: Flow<Boolean>
 * ```
 *
 * Storage container: a Preferences DataStore (the caller-supplied
 * [dataStore]). The on-disk file holds only the AES-GCM hex blob produced
 * by [tokenEncryptor]; the plaintext `command_secret` is never written to
 * disk, never written to logcat, never serialized into crash bundles, and
 * never cached in a field on this class.
 *
 * Failure semantics (matching DOC_7 §3.9):
 *  - [getSecret] returns `null` when no secret has been provisioned yet.
 *  - If the on-disk blob fails its AEAD tag check, [TokenEncryptor] raises
 *    [SecretIntegrityException]; we surface that to the caller and DO NOT
 *    silently delete the blob. The C2 layer is expected to enter safe
 *    mode + re-register the device.
 */
public class DeviceSecretStore(
    private val dataStore: DataStore<Preferences>,
    private val tokenEncryptor: TokenEncryptor,
) {

    /** Encrypts [secret] and writes it to the DataStore. */
    public suspend fun put(secret: String) {
        val sealed = tokenEncryptor.encrypt(secret)
        dataStore.edit { prefs ->
            prefs[KEY_SECRET_SEALED] = sealed
        }
    }

    /**
     * Returns the plaintext `command_secret`, decrypting on every read.
     * Returns `null` when no secret has been provisioned yet.
     * Throws [SecretIntegrityException] when the stored blob has been tampered with.
     */
    public suspend fun getSecret(): String? {
        val sealed = dataStore.data.map { it[KEY_SECRET_SEALED] }.first() ?: return null
        return tokenEncryptor.decrypt(sealed)
    }

    /** Wipes the persisted blob — used by deregistration / safe-mode flows. */
    public suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SECRET_SEALED)
        }
    }

    /**
     * Cold flow emitting `true` when a sealed blob is present. Consumed by
     * `BootStateRestorer` and `DaemonStatusAggregator` (later layers) so
     * the dashboard can show "device registered" without ever touching the
     * plaintext.
     */
    public val hasSecret: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SECRET_SEALED] != null
    }

    public companion object {
        public const val DATASTORE_NAME: String = "device_secret"
        internal val KEY_SECRET_SEALED: Preferences.Key<String> =
            stringPreferencesKey("command_secret_sealed")
    }
}
