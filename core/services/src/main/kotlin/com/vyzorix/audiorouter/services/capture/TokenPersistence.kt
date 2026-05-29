// TokenPersistence — encrypts and stores MediaProjection metadata at rest.
//
// IMPORTANT: this class does NOT persist the raw projection `Intent` /
// `resultCode`. Android forbids re-using those across process deaths —
// the operating system invalidates the token as soon as the original
// granting Activity is destroyed. What we DO persist:
//   - Hashed identifier of the granting session (for forensics; lets us
//     correlate route_history rows with the projection origin).
//   - Trigger origin label (e.g. "bootstrap", "auto_reacquire").
//   - Encrypted blob of (sample_rate_hz, channel_count, granted_at_ms),
//     so that on cold-boot the daemon can quickly re-establish the
//     capture config without waiting for a fresh grant.
//
// Encryption uses [TokenEncryptor] (AES-GCM via AndroidKeystore on real
// devices, SoftwareKeystoreManager on the C22 — see
// `KeystoreManagerFactory.create(profile)`). The same encryption gate
// the DeviceSecretStore uses, so the same threat model applies:
//  - Tampering → SecretIntegrityException → caller clears the entry and
//    requests a fresh grant via the trampoline.
//  - Decrypt failure on key reset → swallow the entry and treat as
//    "no persisted token", falling back to the trampoline.
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.9 +
// doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.8.

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.common.utils.SecretIntegrityException
import com.vyzorix.audiorouter.common.utils.TokenEncryptor
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicReference

/** Plaintext view of the persisted token-side metadata. */
public data class PersistedProjectionMetadata(
    public val grantedAtEpochMs: Long,
    public val sampleRateHz: Int,
    public val channelCount: Int,
    public val triggerOrigin: String,
)

/**
 * Encrypted-at-rest persistence for the small slice of MediaProjection
 * metadata that survives process death.
 *
 * Backing store is in-memory by default (production callers should pass
 * a [Storage] that wraps DataStore Preferences — see [DataStoreBackedStorage]
 * companion factory). Keeping the backing store an interface lets tests
 * use a fake without standing up a DataStore.
 */
public class TokenPersistence(
    private val tokenEncryptor: TokenEncryptor,
    private val storage: Storage = InMemoryStorage(),
) {

    /** Backing storage interface — thin Preferences-like contract. */
    public interface Storage {
        public suspend fun put(key: String, value: String?)
        public suspend fun get(key: String): String?
        public suspend fun clear()
    }

    /** Default in-memory storage — useful for tests + cold-boot before DataStore wires up. */
    public class InMemoryStorage : Storage {
        private val ref: AtomicReference<MutableMap<String, String>> =
            AtomicReference(mutableMapOf())
        public override suspend fun put(key: String, value: String?) {
            val map = ref.get()
            synchronized(map) {
                if (value == null) {
                    map.remove(key)
                } else {
                    map[key] = value
                }
            }
        }
        public override suspend fun get(key: String): String? {
            val map = ref.get()
            return synchronized(map) { map[key] }
        }
        public override suspend fun clear() {
            ref.set(mutableMapOf())
        }
    }

    /** Persist the given metadata. Best-effort — failures are logged, not thrown. */
    public suspend fun persist(metadata: PersistedProjectionMetadata) {
        val serialised = serialise(metadata)
        val sealed = try {
            tokenEncryptor.encrypt(serialised)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "token.persist_failed phase=encrypt err=${t.javaClass.simpleName}",
            )
            return
        }
        try {
            storage.put(KEY_SEALED_METADATA, sealed)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "token.persist_failed phase=write err=${t.javaClass.simpleName}",
            )
        }
    }

    /**
     * Read the previously persisted metadata. Returns `null` on any of:
     *  - No entry present.
     *  - Decrypt failure on missing/wrapped key (typical after Safe Mode).
     *  - Parse failure on garbled serialised payload.
     *  - Tamper detected ([SecretIntegrityException]) — the entry is also
     *    cleared in this case.
     */
    public suspend fun read(): PersistedProjectionMetadata? {
        val sealed = try {
            storage.get(KEY_SEALED_METADATA)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "token.read_failed phase=read err=${t.javaClass.simpleName}",
            )
            return null
        } ?: return null

        val plaintext = try {
            tokenEncryptor.decrypt(sealed)
        } catch (e: SecretIntegrityException) {
            DaemonLogger.get().warn(
                TAG,
                "token.read_failed phase=decrypt reason=integrity err=${e.javaClass.simpleName}",
            )
            // Tamper detected — purge the entry so the next persist starts clean.
            runCatching { storage.put(KEY_SEALED_METADATA, null) }
            return null
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "token.read_failed phase=decrypt err=${t.javaClass.simpleName}",
            )
            return null
        }

        return try {
            deserialise(plaintext)
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "token.read_failed phase=parse err=${t.javaClass.simpleName}",
            )
            null
        }
    }

    /** Wipe the persisted entry. Used by Safe Mode entry and on revoke. */
    public suspend fun clear() {
        try {
            storage.clear()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "token.clear_failed err=${t.javaClass.simpleName}",
            )
        }
    }

    /** Pipe-delimited serialisation — same shape used by COMMAND_SECURITY §2. */
    private fun serialise(metadata: PersistedProjectionMetadata): String =
        "${metadata.grantedAtEpochMs}|${metadata.sampleRateHz}|${metadata.channelCount}|${metadata.triggerOrigin}"

    private fun deserialise(payload: String): PersistedProjectionMetadata {
        val parts = payload.split("|")
        require(parts.size == 4) { "expected 4 pipe-delimited fields, got ${parts.size}" }
        return PersistedProjectionMetadata(
            grantedAtEpochMs = parts[0].toLong(),
            sampleRateHz = parts[1].toInt(),
            channelCount = parts[2].toInt(),
            triggerOrigin = parts[3],
        )
    }

    public companion object {
        public const val KEY_SEALED_METADATA: String = "projection_metadata_v1"
        private const val TAG: String = "TokenPersistence"
    }
}
