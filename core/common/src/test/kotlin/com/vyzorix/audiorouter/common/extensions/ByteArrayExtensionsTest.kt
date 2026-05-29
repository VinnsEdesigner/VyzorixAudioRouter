package com.vyzorix.audiorouter.common.extensions

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteArrayExtensionsTest {

    @Test
    fun to_hex_lowercase_round_trip() {
        val bytes = byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("00017f80ff", bytes.toHex())
        assertTrue(bytes.contentEquals("00017f80ff".hexToByteArray()))
    }

    @Test
    fun to_hex_empty() {
        assertEquals("", byteArrayOf().toHex())
        assertTrue(byteArrayOf().contentEquals("".hexToByteArray()))
    }

    @Test
    fun hex_to_byte_array_rejects_odd_length() {
        assertFailsWith<IllegalArgumentException> { "abc".hexToByteArray() }
    }

    @Test
    fun hex_to_byte_array_accepts_mixed_case() {
        val bytes = "AbCdEf".hexToByteArray()
        assertTrue(byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte()).contentEquals(bytes))
    }

    @Test
    fun constant_time_equals_basic_cases() {
        val a = byteArrayOf(1, 2, 3, 4)
        val b = byteArrayOf(1, 2, 3, 4)
        val c = byteArrayOf(1, 2, 3, 5)
        val d = byteArrayOf(1, 2, 3)
        assertTrue(a constantTimeEquals b)
        assertFalse(a constantTimeEquals c)
        assertFalse(a constantTimeEquals d)
    }
}
