package com.vyzorix.audiorouter.common.utils

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pin the lower-case hex convention used by both Go and Kotlin sides of
 * the canonical HMAC contract (doc/COMMAND_SECURITY.md §3). Mixing cases
 * here would silently break HMAC comparisons at the Go ↔ Kotlin boundary.
 */
class HexCodecTest {

    @Test
    fun emits_lower_case_hex() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x10, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0xFF.toByte())
        assertEquals("000f10abcdefff", HexCodec.encode(bytes))
    }

    @Test
    fun decode_round_trips_random_bytes() {
        val sample = ByteArray(64) { (it * 31).toByte() }
        assertContentEquals(sample, HexCodec.decode(HexCodec.encode(sample)))
    }

    @Test
    fun decode_accepts_mixed_case_input() {
        assertContentEquals(byteArrayOf(0xAB.toByte()), HexCodec.decode("aB"))
    }

    @Test
    fun decode_rejects_odd_length() {
        assertFailsWith<IllegalArgumentException> { HexCodec.decode("abc") }
    }

    @Test
    fun decode_rejects_non_hex_chars() {
        assertFailsWith<IllegalArgumentException> { HexCodec.decode("zz") }
    }
}
