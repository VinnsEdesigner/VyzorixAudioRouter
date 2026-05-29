// NokiaAudioWorkarounds — retry/backoff routines for Nokia-specific background
// restrictions on AudioManager interactions.
//
// Per doc/VyzorixAudioRouter_RepoTree.md line 611 + NOKIA_C22_NOTES.md §3 the
// Nokia C22 (and several other HMD-built Evenwell-skinned devices) silently
// drop AudioManager.setMode() / setSpeakerphoneOn() calls when the daemon is
// running from a background foreground service that was started before the
// user's first foreground interaction this boot. The drop is silent — the
// setter returns normally but the underlying APM state machine doesn't update.
//
// The mitigation is a "wait + observe + retry" loop:
//   1. Call AudioRouteManager.setMode / setSpeakerphoneOn.
//   2. Sleep a profile-driven gap (alsaTimingGapMs).
//   3. Re-read the AudioManager state.
//   4. If state did not change, retry up to N times with exponential backoff.
//   5. If still not changed, surface an OemEnforcementResult.FAILED so the
//      caller can escalate (e.g. RouterPersistenceDaemon -> VendorRouteResetter).
//
// This class is small on purpose — the route-war loop hits it on every drift,
// so allocation churn is kept to zero and all branches are tail-callable.

package com.vyzorix.audiorouter.services.oem

import android.media.AudioManager
import com.vyzorix.audiorouter.services.managers.AudioRouteManager

/** Outcome of a single workaround invocation. */
public enum class OemEnforcementResult {
    /** AudioManager state matches the request on the first attempt. */
    APPLIED_DIRECTLY,

    /** A retry was required but the state ultimately matches the request. */
    APPLIED_AFTER_RETRY,

    /** Every retry exhausted; AudioManager remained in the wrong state. */
    FAILED,
}

/**
 * Nokia-/Evenwell-specific retry routines for setMode + setSpeakerphoneOn.
 *
 * The class is open so test code can override the [sleep] seam without
 * touching real-time scheduling. All other state is contained in
 * [AudioRouteManager] so no in-class fields are needed.
 */
public open class NokiaAudioWorkarounds(
    private val routeManager: AudioRouteManager,
    private val profile: NokiaC22DeviceProfile,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
) {

    /**
     * Force AudioManager into MODE_IN_COMMUNICATION + speakerphone=true with
     * the OEM retry/backoff applied.
     */
    public open fun assertVoipSpeakerRoute(): OemEnforcementResult {
        // Callers (SpeakerForceEngine.tick) only invoke this when they have
        // already decided a reassertion is needed (drift detected or first
        // engagement), so we do NOT early-return on "snapshot already matches".
        // Doing so would mask the Nokia silent-drop bug on the very first
        // post-boot setSpeakerphoneOn(true) call — the snapshot can lie.
        routeManager.engageVoipSpeakerRoute(modeSwitchGapMs = profile.modeSwitchSilenceGapMs)
        if (matchesTarget(routeManager.snapshot())) {
            return OemEnforcementResult.APPLIED_DIRECTLY
        }
        // Retries with exponential backoff seeded by the profile's alsa timing.
        var delayMs = profile.modeSwitchSilenceGapMs.coerceAtLeast(MIN_RETRY_DELAY_MS)
        for (attempt in 1..maxRetries) {
            sleep(delayMs)
            // Cycle through MODE_NORMAL -> MODE_IN_COMMUNICATION so the APM
            // sees a transition (some Evenwell builds ignore "set to current
            // value" calls).
            routeManager.setMode(AudioManager.MODE_NORMAL)
            sleep(delayMs)
            routeManager.engageVoipSpeakerRoute(modeSwitchGapMs = profile.modeSwitchSilenceGapMs)
            if (matchesTarget(routeManager.snapshot())) {
                return OemEnforcementResult.APPLIED_AFTER_RETRY
            }
            delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        }
        return OemEnforcementResult.FAILED
    }

    private fun matchesTarget(snapshot: com.vyzorix.audiorouter.services.managers.AudioRouteSnapshot): Boolean {
        return snapshot.mode == AudioManager.MODE_IN_COMMUNICATION && snapshot.isSpeakerphoneOn
    }

    /** Seam for tests — defaults to [Thread.sleep]. */
    protected open fun sleep(ms: Long) {
        if (ms <= 0L) return
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    public companion object {
        public const val DEFAULT_MAX_RETRIES: Int = 3
        public const val MIN_RETRY_DELAY_MS: Long = 20L
        public const val MAX_RETRY_DELAY_MS: Long = 200L
    }
}
