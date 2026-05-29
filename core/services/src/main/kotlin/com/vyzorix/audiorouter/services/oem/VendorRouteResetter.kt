// VendorRouteResetter — HAL-level "kick the route" recovery routine.
//
// Per doc/VyzorixAudioRouter_RepoTree.md line 614 + NOKIA_C22_NOTES.md §4 the
// AudioPolicyManager on the C22 occasionally locks itself in a state where
// even setMode + setSpeakerphoneOn round-trips don't move the active route.
// The pattern reproduces under exactly two conditions:
//
//   1. Headphone unplugged while MODE_IN_COMMUNICATION is engaged — APM
//      sometimes refuses to vacate the headset device until it sees a real
//      "transition" event.
//   2. After ~6h of continuous MODE_IN_COMMUNICATION — APM's internal
//      device-state cache develops "phantom output" entries.
//
// The vendor mitigation (documented in the AOSP Unisoc tree) is to cycle the
// audio mode through {NORMAL → RINGTONE → IN_CALL → IN_COMMUNICATION}. Each
// transition forces AudioPolicyManager to re-probe its device routing
// tables, which is what we need.
//
// This class only fires when:
//   - RoutePersistenceDaemon classifies drift as HEADSET_HIJACK and the
//     5-second 100ms reassertion storm hasn't recovered, OR
//   - SpeakerForceEngine asks for an explicit reset via
//     CommunicationRouter.escalateRecovery().
//
// We don't run this on every tick because the mode cycle takes ~200ms and
// produces a brief audible click on devices where MODE_RINGTONE actually
// activates the speaker (which is rare on the C22 but possible).

package com.vyzorix.audiorouter.services.oem

import android.media.AudioManager
import com.vyzorix.audiorouter.services.managers.AudioRouteManager

/** HAL-level route reset routines that escalate above normal SpeakerForceEngine reassertion. */
public open class VendorRouteResetter(
    private val routeManager: AudioRouteManager,
    private val profile: NokiaC22DeviceProfile,
) {

    /** Outcome of a reset attempt. */
    public enum class Outcome {
        /** Reset completed; AudioManager now reports the desired state. */
        ROUTE_RECOVERED,

        /** Reset completed but AudioManager still reports the wrong state. */
        ROUTE_STILL_DRIFTING,

        /** Reset bailed before completion (e.g. interrupted). */
        ABORTED,
    }

    /**
     * Cycle through the canonical Unisoc mode sequence to force APM to
     * re-probe its routing tables. Blocking — must be called off the main
     * thread.
     */
    public open fun resetRoute(): Outcome {
        val gap = profile.modeSwitchSilenceGapMs.coerceAtLeast(MIN_GAP_MS)
        try {
            // Step 1: leave MODE_IN_COMMUNICATION outright.
            routeManager.setMode(AudioManager.MODE_NORMAL)
            sleep(gap)
            // Step 2: ringtone transition — forces device-list re-probe.
            routeManager.setMode(AudioManager.MODE_RINGTONE)
            sleep(gap)
            // Step 3: IN_CALL transition — APM treats this as a "real call"
            // arrival event and unconditionally re-evaluates routing.
            routeManager.setMode(AudioManager.MODE_IN_CALL)
            sleep(gap)
            // Step 4: settle back into IN_COMMUNICATION and re-assert speakerphone.
            routeManager.engageVoipSpeakerRoute(modeSwitchGapMs = gap)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Outcome.ABORTED
        }
        val snapshot = routeManager.snapshot()
        return if (snapshot.mode == AudioManager.MODE_IN_COMMUNICATION && snapshot.isSpeakerphoneOn) {
            Outcome.ROUTE_RECOVERED
        } else {
            Outcome.ROUTE_STILL_DRIFTING
        }
    }

    /** Seam for tests. */
    protected open fun sleep(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }

    public companion object {
        /** Floor on per-step delay so transitions don't fire faster than HAL can keep up. */
        public const val MIN_GAP_MS: Long = 40L
    }
}
