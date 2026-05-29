package com.vyzorix.audiorouter.services.foreground.signals

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebSocketConnectionSignalTest {

    private class Probe(val connected: Boolean) : WebSocketConnectionProbe {
        override fun isConnected(): Boolean = connected
    }

    @Test fun `null provider yields UNKNOWN`() {
        val s = WebSocketConnectionSignal(probeProvider = { null })
        assertEquals(SignalSeverity.UNKNOWN, s.current().severity)
    }

    @Test fun `connected yields OK`() {
        val s = WebSocketConnectionSignal(probeProvider = { Probe(connected = true) })
        assertEquals(SignalSeverity.OK, s.current().severity)
    }

    @Test fun `disconnected yields WARN`() {
        val s = WebSocketConnectionSignal(probeProvider = { Probe(connected = false) })
        assertEquals(SignalSeverity.WARN, s.current().severity)
    }
}
