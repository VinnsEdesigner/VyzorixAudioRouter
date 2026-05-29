package com.vyzorix.audiorouter.common.extensions

import com.vyzorix.audiorouter.common.utils.HexCodec

/**
 * Lower-case hex helpers for `ByteArray` / `String`.
 *
 * These are the user-facing API surface for hex encoding throughout the
 * daemon — `CommandHmacValidator` (Layer 8) uses [toHex] to format
 * computed-HMAC bytes for the wire, and `CryptoHelper` (Layer 1) uses
 * [hexToByteArray] to parse the persisted master-key fingerprint.
 *
 * Implementation delegates to [HexCodec] so the encoder/decoder lives in
 * exactly one place (lower-case hex; see DOC_7 §3.8 for the rationale).
 */

/** Lower-case hex encoding of `this`. */
public fun ByteArray.toHex(): String = HexCodec.encode(this)

/** Parses a lower-case (or mixed-case) hex string back into bytes. */
public fun String.hexToByteArray(): ByteArray = HexCodec.decode(this)

/**
 * Constant-time byte-array comparison. Use this when comparing HMAC tags
 * or any other secret-derived bytes — `Arrays.equals` short-circuits on
 * the first mismatched byte and is a timing-oracle leak.
 */
public infix fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (this.size != other.size) return false
    var diff = 0
    for (i in indices) {
        diff = diff or (this[i].toInt() xor other[i].toInt())
    }
    return diff == 0
}
