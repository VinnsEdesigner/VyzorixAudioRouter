package com.vyzorix.audiorouter.common.audio

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class AudioBufferPoolTest {

    @Test
    fun acquire_returns_buffer_of_correct_size() {
        val pool = AudioBufferPool(bufferSize = 256)
        val buf = pool.acquire()
        assertEquals(256, buf.size)
    }

    @Test
    fun release_reuses_buffers() {
        val pool = AudioBufferPool(bufferSize = 128)
        val first = pool.acquire()
        pool.release(first)
        val second = pool.acquire()
        assertSame(first, second)
    }

    @Test
    fun release_caps_pool_size_to_max_retained() {
        val pool = AudioBufferPool(bufferSize = 64, maxRetained = 2)
        val a = pool.acquire()
        val b = pool.acquire()
        val c = pool.acquire()
        pool.release(a)
        pool.release(b)
        pool.release(c) // dropped because pool is full
        assertEquals(2, pool.size())
    }

    @Test
    fun release_rejects_wrong_sized_buffers() {
        val pool = AudioBufferPool(bufferSize = 16)
        pool.release(ByteArray(32))
        assertEquals(0, pool.size())
    }

    @Test
    fun reset_empties_the_pool() {
        val pool = AudioBufferPool(bufferSize = 16, maxRetained = 4)
        repeat(3) { pool.release(ByteArray(16)) }
        assertEquals(3, pool.size())
        pool.reset()
        assertEquals(0, pool.size())
        // After reset, the next acquire allocates a fresh buffer.
        val fresh = pool.acquire()
        assertEquals(16, fresh.size)
        // Pool retains no references, so the next acquire is independent.
        val another = pool.acquire()
        assertNotSame(fresh, another)
    }

    @Test
    fun ctor_rejects_invalid_sizes() {
        assertFailsWith<IllegalArgumentException> { AudioBufferPool(bufferSize = 0) }
        assertFailsWith<IllegalArgumentException> { AudioBufferPool(bufferSize = 16, maxRetained = 0) }
    }
}
