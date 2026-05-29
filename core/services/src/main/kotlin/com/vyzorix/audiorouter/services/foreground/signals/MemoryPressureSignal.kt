// MemoryPressureSignal — reads ActivityManager.MemoryInfo and bands the
// available memory into UNKNOWN/OK/WARN/CRIT.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 625:
//     core/services/foreground/signals/MemoryPressureSignal.kt
//       "Reads ActivityManager.MemoryInfo + ComponentCallbacks2 levels;
//        absorbs former ProcessHealthMonitor's memory-tracking duties".
//
// Reads two surfaces:
//   1. ActivityManager.MemoryInfo (system-wide) — gives `availMem`,
//      `threshold`, `lowMemory`. We compute the ratio
//      `availMem / threshold` as a proxy for "headroom before the OS
//      starts killing".
//   2. ComponentCallbacks2.onTrimMemory level (process-local) — pushed
//      into this class by [PersistentAudioService.onTrimMemory]. The
//      level becomes part of the band decision.
//
// Banding policy (Nokia C22 has 2 GiB RAM, so we're aggressive):
//   - availMem > 2× threshold && trim ≤ TRIM_MEMORY_BACKGROUND → OK
//   - availMem > 1× threshold && trim ≤ TRIM_MEMORY_MODERATE   → OK
//   - availMem in (0.5×, 1×] threshold OR trim == RUNNING_LOW → WARN
//   - lowMemory flag set OR trim == RUNNING_CRITICAL              → CRIT
//
// The signal does NOT take action — that's RecoveryCoordinator's job.

package com.vyzorix.audiorouter.services.foreground.signals

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads memory state. Stateless query each call; the only stored state
 * is the most recently observed `onTrimMemory` level, pushed by the
 * service.
 */
public class MemoryPressureSignal(
    private val activityManager: ActivityManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    public constructor(context: Context) : this(
        activityManager = context.getSystemService(
            Context.ACTIVITY_SERVICE,
        ) as ActivityManager,
    )

    public override val id: String = "memory_pressure"

    private val lastTrimLevel: AtomicInteger = AtomicInteger(ComponentCallbacks2.TRIM_MEMORY_COMPLETE.inv())
    private val lastAvailMemBytes: AtomicLong = AtomicLong(0L)
    private val lastThresholdBytes: AtomicLong = AtomicLong(0L)
    private val lowMemoryReports: AtomicLong = AtomicLong(0L)

    /** Push the most recent onTrimMemory level. Called from the service. */
    public fun onTrimMemory(level: Int) {
        lastTrimLevel.set(level)
        DaemonLogger.get().info(TAG, "memory.trim level=$level")
    }

    public override fun current(): SignalValue {
        val info = ActivityManager.MemoryInfo()
        val read = try {
            activityManager.getMemoryInfo(info)
            true
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "memory.read.threw err=${t.javaClass.simpleName}",
            )
            false
        }
        if (!read) {
            return SignalValue.unknown(
                label = "memory unavailable",
                readEpochMs = clock(),
            )
        }
        lastAvailMemBytes.set(info.availMem)
        lastThresholdBytes.set(info.threshold)
        if (info.lowMemory) {
            lowMemoryReports.incrementAndGet()
        }
        val trim = lastTrimLevel.get()
        val availMb = info.availMem / MB
        val thresholdMb = info.threshold / MB
        val ratio = if (info.threshold > 0L) info.availMem.toDouble() / info.threshold.toDouble() else 1.0
        val severity = when {
            info.lowMemory -> SignalSeverity.CRIT
            trim == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> SignalSeverity.CRIT
            ratio <= 0.5 -> SignalSeverity.CRIT
            trim == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> SignalSeverity.WARN
            ratio <= 1.0 -> SignalSeverity.WARN
            trim == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> SignalSeverity.WARN
            else -> SignalSeverity.OK
        }
        return SignalValue(
            severity = severity,
            label = "${availMb}MB free (thr ${thresholdMb}MB)",
            details = "trim=${trimLabel(trim)} lowMem=${info.lowMemory} totalMem=${info.totalMem / MB}MB",
            readEpochMs = clock(),
        )
    }

    private fun trimLabel(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        else -> "none"
    }

    public companion object {
        private const val MB: Long = 1024L * 1024L
        private const val TAG: String = "MemoryPressureSignal"
    }
}
