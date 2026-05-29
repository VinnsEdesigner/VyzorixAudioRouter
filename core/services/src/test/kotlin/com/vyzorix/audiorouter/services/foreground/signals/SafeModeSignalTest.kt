package com.vyzorix.audiorouter.services.foreground.signals

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafeModeSignalTest {

    private class Probe(val active: Boolean, val reason: String = "test") : SafeModeProbe {
        override fun isActive(): Boolean = active
        override fun lastEngagedReason(): String = reason
    }

    @Test fun `null provider yields UNKNOWN`() {
        val s = SafeModeSignal(probeProvider = { null })
        assertEquals(SignalSeverity.UNKNOWN, s.current().severity)
    }

    @Test fun `inactive probe yields OK`() {
        val s = SafeModeSignal(probeProvider = { Probe(active = false) })
        assertEquals(SignalSeverity.OK, s.current().severity)
    }

    @Test fun `active probe yields WARN`() {
        val s = SafeModeSignal(probeProvider = { Probe(active = true, reason = "memory") })
        val v = s.current()
        assertEquals(SignalSeverity.WARN, v.severity)
        assertEquals(true, v.details.contains("memory"))
    }

    @Test fun `provider exception yields UNKNOWN`() {
        val s = SafeModeSignal(probeProvider = { throw IllegalStateException("oops") })
        assertEquals(SignalSeverity.UNKNOWN, s.current().severity)
    }
}
