// ThermalSignal — bands the device thermal state into UNKNOWN/OK/WARN/CRIT.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 626:
//     core/services/foreground/signals/ThermalSignal.kt
//       "Thin wrapper over DeviceThermalMonitor producing a SignalValue".
//
// Layer 5 ships before a dedicated DeviceThermalMonitor exists (that
// class lands with the diagnostics stack in Layer 6+). We read directly
// from `PowerManager.currentThermalStatus` on A29+, which is the same
// source DeviceThermalMonitor will wrap. The signal-source contract
// stays identical when that class drops in.
//
// Banding (per Android THERMAL_STATUS_* spec):
//   - NONE / LIGHT       → OK
//   - MODERATE           → WARN
//   - SEVERE             → WARN
//   - CRITICAL / EMERGENCY / SHUTDOWN → CRIT
//
// Threading: `getCurrentThermalStatus` is synchronous and main-thread-safe.

package com.vyzorix.audiorouter.services.foreground.signals

import android.content.Context
import android.os.PowerManager
import com.vyzorix.audiorouter.services.logging.DaemonLogger

/**
 * Reads thermal state. Stateless — no internal counters required for
 * the contract; bands map deterministically from
 * `PowerManager.currentThermalStatus`.
 */
public class ThermalSignal(
    private val powerManager: PowerManager,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    public constructor(context: Context) : this(
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager,
    )

    public override val id: String = "thermal"

    public override fun current(): SignalValue {
        // Always read thermal status since minSdk is 33 (Q=29).
        val status = try {
            powerManager.currentThermalStatus
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "thermal.read.threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "thermal unavailable",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        val severity = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> SignalSeverity.OK
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE -> SignalSeverity.WARN
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> SignalSeverity.CRIT
            else -> SignalSeverity.UNKNOWN
        }
        return SignalValue(
            severity = severity,
            label = statusLabel(status),
            details = "status_code=$status",
            readEpochMs = clock(),
        )
    }

    private fun statusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN_$status"
    }

    public companion object {
        private const val TAG: String = "ThermalSignal"
    }
}
