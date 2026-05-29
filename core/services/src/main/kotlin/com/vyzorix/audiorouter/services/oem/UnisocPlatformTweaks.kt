// UnisocPlatformTweaks — Unisoc SC9863A-specific timing and thread tuning.
//
// Per doc/VyzorixAudioRouter_RepoTree.md line 612 + NOKIA_C22_NOTES.md §2 the
// Unisoc SC9863A SoC (used by the Nokia C22) has two relevant peculiarities
// the route war must accommodate:
//
//   §2.1 — Tinyalsa configuration gap (~30ms):
//     After AudioPolicyManager flips the active route, tinyalsa needs a few
//     dozen milliseconds before subsequent setMode / setSpeakerphoneOn calls
//     are honored. Hitting it within that window silently no-ops the request.
//     The exact gap is reported by DeviceQuirkProfile.alsaTimingGapMs and
//     surfaced here as [postRouteFlipDelayMs] so callers don't reach into the
//     profile directly.
//
//   §2.3 — Silent SCHED_FIFO downgrade:
//     The kernel allows sched_setscheduler(SCHED_FIFO) to succeed but the
//     scheduler-state read-back returns SCHED_OTHER. This is detected in
//     core/audioengine/cpp/thread_priority_guard.cpp; we read its result via
//     NativeAudioBridge.elevatePriority() and surface a tweak that throttles
//     SpeakerForceEngine's cadence down when the elevation silently fails
//     (so we don't starve the rest of the system).
//
// This class is a *data* helper — it doesn't actually call AudioManager. The
// reason: the SC9863A tweaks live behind these flags + delays, and the
// callers (NokiaAudioWorkarounds, SpeakerForceEngine, RoutePersistenceDaemon)
// already own the AudioManager surface. Keeping the platform-tweak code in
// one tiny class makes it trivial to swap in a different profile for the
// (future) Layer 6 Qualcomm/MediaTek loaner test devices.

package com.vyzorix.audiorouter.services.oem

/**
 * Compile-time-constant flag surface for the Unisoc SC9863A platform.
 *
 * The values are derived from the active [NokiaC22DeviceProfile], which in
 * turn delegates to [com.vyzorix.audiorouter.common.device.DeviceQuirkProfile].
 * The indirection exists so the engine can be tested without touching
 * `Build.MANUFACTURER`/`Build.MODEL`.
 */
public open class UnisocPlatformTweaks(
    private val profile: NokiaC22DeviceProfile,
) {

    /**
     * Profile-driven minimum delay between an AudioPolicyManager route flip
     * and a follow-up setMode / setSpeakerphoneOn call. Zero on devices
     * without the §2.1 quirk.
     */
    public open val postRouteFlipDelayMs: Long
        get() = profile.modeSwitchSilenceGapMs

    /**
     * If `true`, the daemon must reduce SpeakerForceEngine's tick cadence
     * when SCHED_FIFO elevation silently falls back to SCHED_OTHER. The
     * default mapping is "Unisoc SoCs require the throttle" — a more granular
     * read-back is owned by the audioengine's NativeAudioBridge.
     */
    public open val requiresPriorityFallbackThrottle: Boolean
        get() = profile.requiresSchedFifoFallbackHandling

    /**
     * Throttled tick cadence (ms) when SCHED_FIFO elevation silently failed.
     * Returns the profile-default cadence on platforms that don't need
     * throttling, so callers can use the same value unconditionally.
     */
    public open fun fallbackTickCadenceMs(defaultCadenceMs: Long): Long {
        return if (requiresPriorityFallbackThrottle) {
            // Double the cadence so the SCHED_OTHER thread is less aggressive
            // toward the rest of the system. Halving the rate of reassertion
            // is acceptable because the steady state is "no drift" — the
            // engine only does work on drift.
            (defaultCadenceMs * 2).coerceAtMost(MAX_FALLBACK_CADENCE_MS)
        } else {
            defaultCadenceMs
        }
    }

    public companion object {
        /**
         * Upper bound on the SCHED_OTHER fallback cadence. We never go slower
         * than this because we still need to react to drift within a human
         * timescale.
         */
        public const val MAX_FALLBACK_CADENCE_MS: Long = 2_000L
    }
}
