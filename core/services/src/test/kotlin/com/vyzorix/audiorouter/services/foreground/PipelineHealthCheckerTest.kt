package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PipelineHealthCheckerTest {

    private val nowMs: AtomicLong = AtomicLong(10_000L)

    private fun newChecker(): PipelineHealthChecker =
        PipelineHealthChecker(clock = { nowMs.get() })

    @Test fun `idle pipeline reports OK`() {
        val c = newChecker()
        c.setIdle(true)
        assertEquals(SignalSeverity.OK, c.current().severity)
    }

    @Test fun `no frames ever recorded reports CRIT`() {
        val c = newChecker()
        assertEquals(SignalSeverity.CRIT, c.current().severity)
    }

    @Test fun `both surfaces fresh reports OK`() {
        val c = newChecker()
        c.recordCaptureFrame(byteCount = 1024, atEpochMs = 9_500L)
        c.recordPlaybackFrame(byteCount = 1024, atEpochMs = 9_500L)
        nowMs.set(10_000L)
        assertEquals(SignalSeverity.OK, c.current().severity)
    }

    @Test fun `one surface stale reports WARN`() {
        val c = newChecker()
        c.recordCaptureFrame(byteCount = 1024, atEpochMs = 9_500L) // fresh
        // playback never recorded, but capture is < warn → WARN
        nowMs.set(10_000L)
        assertEquals(SignalSeverity.WARN, c.current().severity)
    }

    @Test fun `both surfaces stale beyond crit reports CRIT`() {
        val c = newChecker()
        c.recordCaptureFrame(byteCount = 1024, atEpochMs = 1_000L)
        c.recordPlaybackFrame(byteCount = 1024, atEpochMs = 1_000L)
        nowMs.set(20_000L) // 19s ago — well past CRIT
        assertEquals(SignalSeverity.CRIT, c.current().severity)
    }
}
