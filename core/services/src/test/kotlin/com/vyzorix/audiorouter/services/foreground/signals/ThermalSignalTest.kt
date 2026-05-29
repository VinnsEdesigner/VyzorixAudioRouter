package com.vyzorix.audiorouter.services.foreground.signals

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThermalSignalTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test fun `id is thermal`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val s = ThermalSignal(powerManager = pm)
        assertEquals("thermal", s.id)
    }

    @Test fun `signal returns a non-null value`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val s = ThermalSignal(powerManager = pm, clock = { 7_777L })
        val v = s.current()
        assertNotNull(v)
        assertEquals(7_777L, v.readEpochMs)
    }
}
