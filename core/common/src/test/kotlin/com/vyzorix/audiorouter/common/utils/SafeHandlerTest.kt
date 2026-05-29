package com.vyzorix.audiorouter.common.utils

import android.os.Handler
import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafeHandlerTest {

    @Test
    fun `post forwards thrown exceptions to the reporter and keeps the looper alive`() {
        val reported = mutableListOf<Throwable>()
        val handler = Handler(Looper.getMainLooper())
        val safe = SafeHandler(handler) { reported.add(it) }

        safe.post {
            error("boom")
        }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, reported.size)
        assertEquals("boom", reported.single().message)

        // Looper is still alive — we can post another action afterwards.
        var second = false
        safe.post { second = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second)
    }

    @Test
    fun `postDelayed honours the delay`() {
        val handler = Handler(Looper.getMainLooper())
        val safe = SafeHandler(handler) { /* unused */ }
        var fired = false
        safe.postDelayed(delayMillis = 200L) { fired = true }
        shadowOf(Looper.getMainLooper()).idleFor(199L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assert(!fired) { "Should not have fired before the delay elapsed" }
        shadowOf(Looper.getMainLooper()).idleFor(1L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(fired)
    }

    @Test
    fun `exception in the reporter never propagates`() {
        val handler = Handler(Looper.getMainLooper())
        val safe = SafeHandler(handler) { throw IllegalStateException("reporter bug") }
        safe.post { error("boom") }
        // If the reporter exception escaped we'd see it bubble out of idle().
        shadowOf(Looper.getMainLooper()).idle()
        // Looper still alive proves the reporter exception was swallowed.
        var second = false
        safe.post { second = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second)
    }

    @Test
    fun `constructor-from-looper produces an equivalent handler`() {
        val safe = SafeHandler(Looper.getMainLooper()) { /* unused */ }
        var fired = false
        safe.post { fired = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(safe)
        assertTrue(fired)
    }
}
