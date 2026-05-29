// SafeModeSignal — reports whether the daemon is currently in safe-mode.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 629:
//     core/services/foreground/signals/SafeModeSignal.kt
//       "Reads SafeModeController.isActive() each tick".
//
// Safe-mode is the daemon's degraded-but-stable operating mode: the
// VoIP route still asserts, but capture is paused and the dashboard
// shows a banner. Engaged by [RecoveryCoordinator] when the risk-score
// model declares a `CRITICAL` band.
//
// Layer 5 ships before a dedicated SafeModeController (that class lands
// with the diagnostics stack in Layer 6+). Until then, the daemon
// exposes an in-memory toggle via [SafeModeProbe] that
// RecoveryCoordinator wires through. The signal source is built
// against the probe interface so the eventual controller can drop in
// without re-wiring the dashboard.
//
// Banding policy:
//   - probe returns null → UNKNOWN
//   - probe.isActive() == true  → WARN ("daemon is in safe-mode")
//   - probe.isActive() == false → OK   ("daemon is running normally")

package com.vyzorix.audiorouter.services.foreground.signals

import com.vyzorix.audiorouter.services.logging.DaemonLogger

/** Read-only handle on whatever class controls safe-mode state. */
public interface SafeModeProbe {
    public fun isActive(): Boolean
    public fun lastEngagedReason(): String
}

/** Reads safe-mode state. */
public class SafeModeSignal(
    private val probeProvider: () -> SafeModeProbe?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SignalSource {

    public override val id: String = "safe_mode"

    public override fun current(): SignalValue {
        val probe = try {
            probeProvider()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "safe_mode.provider_threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "safe-mode probe unavailable",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        if (probe == null) {
            return SignalValue.unknown(
                label = "safe-mode controller not wired",
                readEpochMs = clock(),
            )
        }
        val active = try {
            probe.isActive()
        } catch (t: Throwable) {
            DaemonLogger.get().warn(
                TAG,
                "safe_mode.read_threw err=${t.javaClass.simpleName}",
            )
            return SignalValue.unknown(
                label = "safe-mode read failed",
                details = t.javaClass.simpleName,
                readEpochMs = clock(),
            )
        }
        return if (active) {
            SignalValue.warn(
                label = "safe-mode engaged",
                details = "reason=${probe.lastEngagedReason()}",
                readEpochMs = clock(),
            )
        } else {
            SignalValue.ok(label = "safe-mode inactive", readEpochMs = clock())
        }
    }

    public companion object {
        private const val TAG: String = "SafeModeSignal"
    }
}
