package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LivenessProbeTest {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val nowMs: AtomicLong = AtomicLong(1_000L)

    @Before fun setUp() {
        nowMs.set(1_000L)
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun `before start the probe reports CRIT not_yet_started`() {
        val probe = LivenessProbe(scope = scope, clock = { nowMs.get() })
        val v = probe.current()
        assertEquals(SignalSeverity.CRIT, v.severity)
    }

    @Test fun `after a recent beat OK`() {
        val probe = LivenessProbe(scope = scope, intervalMs = 5_000L, clock = { nowMs.get() })
        probe.start()
        // Heartbeat sets lastBeat to clock(). Move clock forward 1s.
        nowMs.set(2_000L)
        val v = probe.current()
        assertEquals(SignalSeverity.OK, v.severity)
        probe.stop()
    }

    @Test fun `decision banding maps to severity buckets`() {
        val probe = LivenessProbe(scope = scope, intervalMs = 5_000L, clock = { nowMs.get() })
        probe.start()
        nowMs.set(1_000L + 7_000L) // within 1.5 * interval (7500ms) — OK
        assertEquals(SignalSeverity.OK, probe.current().severity)
        nowMs.set(1_000L + 10_000L) // 2x interval — WARN
        assertEquals(SignalSeverity.WARN, probe.current().severity)
        nowMs.set(1_000L + 30_000L) // > 3x interval — CRIT
        assertEquals(SignalSeverity.CRIT, probe.current().severity)
        probe.stop()
    }
}
