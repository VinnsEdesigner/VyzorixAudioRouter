package com.vyzorix.audiorouter.services.foreground.signals

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectionTokenSignalTest {

    @Test fun `null provider yields UNKNOWN`() {
        val s = ProjectionTokenSignal(tokenManagerProvider = { null })
        assertEquals(SignalSeverity.UNKNOWN, s.current().severity)
    }

    @Test fun `provider exception yields UNKNOWN`() {
        val s = ProjectionTokenSignal(tokenManagerProvider = { throw RuntimeException("boom") })
        assertEquals(SignalSeverity.UNKNOWN, s.current().severity)
    }

    @Test fun `id is projection_token`() {
        val s = ProjectionTokenSignal(tokenManagerProvider = { null })
        assertEquals("projection_token", s.id)
    }
}
