package com.vyzorix.audiorouter.services.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeLockGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `acquire holds the lock and release frees it`() {
        val guard = WakeLockGuard(context)
        assertFalse(guard.isHeld(), "guard should start un-held")
        assertTrue(guard.acquire(), "acquire should return true on success")
        assertTrue(guard.isHeld(), "lock should be held after acquire")
        guard.release()
        assertFalse(guard.isHeld(), "lock should be released after release()")
    }

    @Test
    fun `acquire is idempotent — calling twice does not stack reference counts`() {
        val guard = WakeLockGuard(context)
        guard.acquire()
        guard.acquire()
        assertTrue(guard.isHeld(), "double acquire should still hold the lock")
        // One release suffices because setReferenceCounted(false).
        guard.release()
        assertFalse(guard.isHeld(), "single release should drop the lock")
    }

    @Test
    fun `release is idempotent — calling on un-held lock does not throw`() {
        val guard = WakeLockGuard(context)
        // Pre-condition: lock starts un-held.
        guard.release()
        guard.release()
        assertFalse(guard.isHeld())
    }
}
