package com.vyzorix.audiorouter.common.utils

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Low-level AES-256-GCM primitives. Internal helper used by both
 * [KeystoreManager] back-ends. Centralized here so that the wire format
 * (IV || ciphertext_with_tag) is defined in exactly one place.
 *
 * - IV: 12 bytes from [SecureRandom] (NIST SP 800-38D §8.2.1 recommendation
 *   for GCM; using anything other than 96 bits drops into a slower /
 *   different IV-derivation path and is footgun-prone).
 * - Tag: 128 bits (the maximum, and the only sane choice for our threat model).
 * - Output layout: `IV (12B) || ciphertext || tag (16B)` — the Java GCM
 *   `Cipher.doFinal` already appends the tag to the ciphertext, so the
 *   logical layout above is also the byte layout we emit.
 */
internal object AesGcm {

    const val IV_BYTES: Int = 12
    private const val TAG_BITS: Int = 128
    private const val TRANSFORMATION: String = "AES/GCM/NoPadding"

    private val random = SecureRandom()

    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return ByteArray(iv.size + ciphertextWithTag.size).also { out ->
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ciphertextWithTag, 0, out, iv.size, ciphertextWithTag.size)
        }
    }

    fun decrypt(key: SecretKey, ivAndCiphertext: ByteArray): ByteArray {
        require(ivAndCiphertext.size > IV_BYTES) {
            "sealed blob too short: ${ivAndCiphertext.size} bytes (need >$IV_BYTES)"
        }
        val iv = ivAndCiphertext.copyOfRange(0, IV_BYTES)
        val body = ivAndCiphertext.copyOfRange(IV_BYTES, ivAndCiphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }
}
