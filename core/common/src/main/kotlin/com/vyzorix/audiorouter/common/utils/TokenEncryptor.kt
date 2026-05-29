package com.vyzorix.audiorouter.common.utils

import javax.crypto.AEADBadTagException

/**
 * Thin convenience wrapper around [KeystoreManager.seal] / [KeystoreManager.unseal]
 * with a `String` ↔ `String` shape that matches what the C2 layer wants when
 * round-tripping the per-device `command_secret` through DataStore.
 *
 * See doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.8 and §3.9. The two
 * callers documented there are:
 *  - `DeviceSecretStore.put(secret)` — encrypts before write.
 *  - `DeviceSecretStore.getSecret()` — decrypts on each read.
 *
 * `TokenEncryptor` adds no crypto of its own; it only normalizes the API
 * shape and surfaces tamper detection as [SecretIntegrityException] so the
 * `DeviceSecretStore` failure-semantics paragraph in DOC_7 §3.9 can be
 * implemented with a single `catch`.
 *
 * The canonical location for this class in older drafts of DOC_7 was
 * `core/services/security/TokenEncryptor.kt`. PR #5 noted that the
 * `services/security/KeystoreManager.kt` path was stale and moved
 * `KeystoreManager` to `core/common/utils/`. We're doing the same for
 * `TokenEncryptor` because the class has no service-layer dependencies
 * (it depends only on `KeystoreManager`) and pulling Layer 3 forward just
 * to hold it would inflate Layer 1's surface for no benefit. DOC_7 §3.8 is
 * updated in this PR to match.
 */
public class TokenEncryptor(
    private val keystoreManager: KeystoreManager,
) {

    /** Encrypts a UTF-8 plaintext string and returns the sealed hex blob. */
    public fun encrypt(plaintext: String): String =
        keystoreManager.seal(plaintext.toByteArray(Charsets.UTF_8))

    /** Decrypts a previously [encrypt]ed sealed hex blob back into UTF-8. */
    public fun decrypt(sealed: String): String {
        val raw = try {
            keystoreManager.unseal(sealed)
        } catch (e: KeystoreFailureException) {
            // Bubble AEADBadTagException specifically as SecretIntegrityException
            // so the DeviceSecretStore §3.9 contract has a single catch surface.
            val rootCause = generateSequence<Throwable>(e) { it.cause }
                .firstOrNull { it is AEADBadTagException }
            if (rootCause != null) {
                throw SecretIntegrityException(
                    "AEAD tag mismatch: stored blob has been tampered with",
                    e,
                )
            }
            throw e
        }
        return String(raw, Charsets.UTF_8)
    }
}

/**
 * Thrown when a sealed blob fails its AES-GCM tag check. The
 * `DeviceSecretStore` translates this into the safe-mode entry described in
 * doc/DOC_7_DATA_SECURITY_AND_PERSISTENCE.md §3.9; callers MUST NOT silently
 * regenerate the secret.
 */
public class SecretIntegrityException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
