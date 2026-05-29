package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nowMs: AtomicLong = AtomicLong(1_000L)

    @Before fun setUp() {
        nowMs.set(1_000L)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    /**
     * Starts the probe, waits for its background coroutine to fire its
     * first heartbeat (so `lastBeatEpochMs` is set to the current
     * `nowMs` value), then immediately stops the loop. This is the only
     * way to keep `lastBeatEpochMs` stable for subsequent staleness
     * banding assertions — the production heartbeat coroutine runs on
     * `Dispatchers.Default` (not the scope's dispatcher) and would
     * otherwise race with the test's clock advances.
     */
    private fun startAndQuiesce(probe: LivenessProbe) {
        probe.start()
        val deadlineMs = System.currentTimeMillis() + 2_000L
        while (probe.beatCount() == 0L && System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(5L)
        }
        probe.stop()
    }

    @Test fun `before start the probe reports CRIT not_yet_started`() {
        val probe = LivenessProbe(scope = scope, clock = { nowMs.get() })
        val v = probe.current()
        assertEquals(SignalSeverity.CRIT, v.severity)
    }

    @Test fun `after a recent beat OK`() {
        val probe = LivenessProbe(scope = scope, intervalMs = 5_000L, clock = { nowMs.get() })
        startAndQuiesce(probe)
        // lastBeat is now pinned at nowMs=1_000 (the value at start
        // time + first heartbeat). Move clock forward by 1s — staleness
        // = 1000ms which is well below the OK threshold of 7500ms.
        nowMs.set(2_000L)
        assertEquals(SignalSeverity.OK, probe.current().severity)
    }

    @Test fun `decision banding maps to severity buckets`() {
        val probe = LivenessProbe(scope = scope, intervalMs = 5_000L, clock = { nowMs.get() })
        startAndQuiesce(probe)
        // lastBeat is pinned at nowMs=1_000. Move the clock forward to
        // sweep through each severity band:
        //   - staleness <= 7500 → OK
        //   - staleness in (7500, 15000] → WARN
        //   - staleness > 15000 → CRIT
        nowMs.set(1_000L + 7_000L) // staleness=7000ms — within OK band
        assertEquals(SignalSeverity.OK, probe.current().severity)
        nowMs.set(1_000L + 10_000L) // staleness=10000ms — within WARN band
        assertEquals(SignalSeverity.WARN, probe.current().severity)
        nowMs.set(1_000L + 30_000L) // staleness=30000ms — beyond CRIT cutoff
        assertEquals(SignalSeverity.CRIT, probe.current().severity)
    }
}
