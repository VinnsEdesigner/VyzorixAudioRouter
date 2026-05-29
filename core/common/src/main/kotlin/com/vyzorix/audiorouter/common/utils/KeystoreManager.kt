package com.vyzorix.audiorouter.common.utils

/**
 * Sealed Android Keystore manager — secures the SQLCipher passphrase and the
 * per-device `command_secret` key.
 *
 * Layer 0 ships ONLY the interface (per BUILD_ORDER.md §"Stubs only"). The
 * Android Keystore-backed implementation arrives in Layer 1 once `core/data`
 * is wired up and a `Context` is available; the C2 unsealCommandSecretKey()
 * call site is enabled in Layer 8.
 *
 * Devices with [com.vyzorix.audiorouter.common.device.KeystoreReliability.UNRELIABLE_USE_SOFTWARE_FALLBACK]
 * use a software-only implementation — see doc/NOKIA_C22_NOTES.md.
 */
public interface KeystoreManager {

    /** Seals [plaintext] under a hardware-backed key, returning the sealed blob (hex). */
    public fun seal(plaintext: ByteArray): String

    /** Unseals a previously [seal]ed blob. Throws if the blob has been tampered with. */
    public fun unseal(sealed: String): ByteArray

    /** Convenience: unseal the per-device `command_secret` and return it as raw bytes. */
    public fun unsealCommandSecretKey(): ByteArray

    /** True iff the implementation backs onto hardware keystore (TEE / StrongBox). */
    public val isHardwareBacked: Boolean
}

/** Thrown by [KeystoreManager] when sealing or unsealing fails. */
public class KeystoreFailureException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
