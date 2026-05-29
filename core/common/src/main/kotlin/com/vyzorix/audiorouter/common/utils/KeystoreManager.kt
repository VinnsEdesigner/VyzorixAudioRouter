package com.vyzorix.audiorouter.common.utils

/**
 * Sealed Android Keystore manager — secures the SQLCipher passphrase and the
 * per-device `command_secret` key.
 *
 * Layer 1 (this PR) fills in the implementation. There are two concrete
 * back-ends:
 *  - [AndroidKeystoreManager] — TEE/StrongBox-backed key material via the
 *    `AndroidKeyStore` provider.
 *  - [SoftwareKeystoreManager] — install-time-derived software key. Used on
 *    devices with [com.vyzorix.audiorouter.common.device.KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK]
 *    (e.g. Nokia C22 / Unisoc SC9863A — see doc/NOKIA_C22_NOTES.md and
 *    doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.1).
 *
 * Pick a back-end via [KeystoreManagerFactory.create].
 *
 * Crypto contract: every [seal]/[unseal] pair is AES-256-GCM with a fresh
 * 12-byte IV and a 128-bit auth tag. The on-disk blob layout is
 * `IV || ciphertext_with_tag`, hex-encoded for transport through
 * `String`-typed preference / DataStore containers.
 *
 * Thread-safety: implementations are safe for concurrent use from any
 * dispatcher (operations create a fresh `Cipher` instance per call).
 */
public interface KeystoreManager {

    /**
     * Encrypts [plaintext] under the manager's master key and returns the
     * sealed blob as hex (`IV || ciphertext_with_tag`).
     */
    public fun seal(plaintext: ByteArray): String

    /**
     * Decrypts a previously [seal]ed hex blob. Throws
     * [KeystoreFailureException] (cause: `AEADBadTagException`) if the auth
     * tag does not verify — callers MUST treat that as evidence of tampering
     * and not silently regenerate the underlying secret.
     */
    public fun unseal(sealed: String): ByteArray

    /** True iff the implementation backs onto a hardware keystore (TEE / StrongBox). */
    public val isHardwareBacked: Boolean
}

/** Thrown by [KeystoreManager] when sealing or unsealing fails. */
public class KeystoreFailureException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
