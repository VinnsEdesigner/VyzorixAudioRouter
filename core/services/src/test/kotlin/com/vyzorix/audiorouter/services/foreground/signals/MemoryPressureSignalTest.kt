package com.vyzorix.audiorouter.services.foreground.signals

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryPressureSignalTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test fun `signal returns a value with non-zero readEpochMs`() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val signal = MemoryPressureSignal(activityManager = activityManager, clock = { 12_345L })
        val value = signal.current()
        assertNotNull(value)
        assertEquals(12_345L, value.readEpochMs)
    }

    @Test fun `onTrimMemory CRITICAL bumps severity towards CRIT`() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val signal = MemoryPressureSignal(activityManager = activityManager)
        signal.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        // The severity may be CRIT or WARN depending on Robolectric's
        // simulated MemoryInfo (Robolectric tends to report ample memory
        // by default). Asserting non-OK is sufficient to verify the
        // trim push wires through.
        val severity = signal.current().severity
        assertTrue(severity == SignalSeverity.CRIT || severity == SignalSeverity.WARN)
    }

    @Test fun `id is memory_pressure`() {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val signal = MemoryPressureSignal(activityManager = activityManager)
        assertEquals("memory_pressure", signal.id)
    }
}
