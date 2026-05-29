package com.vyzorix.audiorouter.common.utils

/**
 * Lower-case hex codec. Pinned to lower-case to match the canonical HMAC
 * encoding used everywhere in doc/COMMAND_SECURITY.md (§3) and the Go
 * server's `hex.EncodeToString`. Mixing cases between layers has bitten
 * other Android projects (different `String.equals` paths), so we
 * centralize the encoder.
 */
internal object HexCodec {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0f]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have even length, got ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = digit(hex[i * 2])
            val lo = digit(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("non-hex character: '$c'")
    }
}
