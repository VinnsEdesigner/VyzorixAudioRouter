// LivenessProbe — Layer-B signal source that answers "is the daemon
// process responsive?"
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md lines 614–616:
//     core/services/foreground/LivenessProbe.kt
//       "Layer B signal: answers 'is the daemon process responsive?'
//        Pings active threads at 5s intervals. Reports state as a
//        SignalValue to DaemonStatusAggregator. Does NOT trigger
//        recovery itself."
//
// Mechanism: every 5s a heartbeat coroutine bumps an AtomicLong with
// the current wall-clock time. The probe's `current()` SignalValue is
// derived from the staleness of that timestamp:
//   - within 7.5s of last beat       → OK
//   - within 15s of last beat        → WARN
//   - beyond 15s OR never beat       → CRIT
//
// Why this works as a signal: the heartbeat coroutine runs on the same
// SupervisorJob scope as the rest of the daemon. If the daemon's main
// dispatcher is wedged (e.g. ANR, dead-lock in a JNI native callback,
// long blocking I/O without yielding), the heartbeat stops firing and
// the signal degrades — which is exactly what the dashboard should
// surface.
//
// The probe is a [SignalSource] so it plugs into the same aggregator
// machinery as the other Layer-B signals. It is wired separately from
// `foreground/signals/` because (per ADR-0007) liveness + pipeline
// health are first-class signals, not arbitrary side channels.

package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.services.foreground.signals.SignalSeverity
import com.vyzorix.audiorouter.services.foreground.signals.SignalSource
import com.vyzorix.audiorouter.services.foreground.signals.SignalValue
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Liveness probe. Single-instance per service.
 *
 * Call [start] once at service onCreate; the probe runs until the
 * supplied [scope] cancels.
 */
public class LivenessProbe(
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    public override val id: String = "liveness"

    private val running: AtomicBoolean = AtomicBoolean(false)
    private val lastBeatEpochMs: AtomicLong = AtomicLong(0L)
    private val beats: AtomicLong = AtomicLong(0L)
    private var job: Job? = null

    /** Start the heartbeat. Idempotent. */
    public fun start() {
        if (!running.compareAndSet(false, true)) return
        lastBeatEpochMs.set(clock())
        job = scope.launch(Dispatchers.Default) {
            while (running.get()) {
                lastBeatEpochMs.set(clock())
                beats.incrementAndGet()
                delay(intervalMs)
            }
            DaemonLogger.get().info(TAG, "probe.heartbeat.exited beats=${beats.get()}")
        }
        DaemonLogger.get().info(TAG, "probe.start intervalMs=$intervalMs")
    }

    /** Stop the heartbeat. Idempotent. */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        DaemonLogger.get().info(TAG, "probe.stop beats=${beats.get()}")
    }

    public override fun current(): SignalValue {
        val now = clock()
        val last = lastBeatEpochMs.get()
        if (last == 0L) {
            return SignalValue(
                severity = SignalSeverity.CRIT,
                label = "liveness: not yet started",
                details = "intervalMs=$intervalMs",
                readEpochMs = now,
            )
        }
        val staleness = now - last
        val severity = when {
            staleness <= intervalMs + intervalMs / 2 -> SignalSeverity.OK
            staleness <= intervalMs * 3 -> SignalSeverity.WARN
            else -> SignalSeverity.CRIT
        }
        return SignalValue(
            severity = severity,
            label = "liveness ${staleness}ms since last beat",
            details = "intervalMs=$intervalMs beats=${beats.get()}",
            readEpochMs = now,
        )
    }

    public fun beatCount(): Long = beats.get()
    public fun lastBeatEpochMs(): Long = lastBeatEpochMs.get()

    public companion object {
        public const val DEFAULT_INTERVAL_MS: Long = 5_000L
        private const val TAG: String = "LivenessProbe"
    }
}
