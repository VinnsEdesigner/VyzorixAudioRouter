// ZygoteCrashMitigator — defers risky operations during the Nokia C22's
// Zygote-stage initialisation to prevent the well-known "launcher tap →
// system_server crash" race documented in `doc/NOKIA_C22_NOTES.md` §1.
//
// The mitigator surfaces a single decision: *is it safe to perform an
// audio / accessibility init right now?* Layer 4+ bootstrap code asks
// before subscribing accessibility events, requesting MediaProjection, or
// elevating thread priority.
//
// The decision is based on the process uptime — `SystemClock.elapsedRealtime()`
// — relative to a per-profile floor. Profiles (`NokiaC22Profile`,
// `UnknownDeviceProfile`) define the floor.

package com.vyzorix.audiorouter.common.device

import android.os.SystemClock

/**
 * Decides whether enough process-uptime has elapsed for risky init to be
 * safe.
 *
 * The instance reads its threshold from the active [DeviceQuirkProfile] at
 * construction; the profile decides whether the C22's narrow startup race
 * applies (Nokia C22 → defer 4s; unknown devices → defer 1s; resilient
 * silicon could set this to 0).
 */
public class ZygoteCrashMitigator(
    private val profile: DeviceQuirkProfile,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /** Process-relative time at which "long enough" has elapsed. */
    private val safeAtElapsedMs: Long = clock() + profile.zygoteSafeDelayMs

    /** True if the deferral window has elapsed and risky init is safe. */
    public fun isSafeToInitNow(): Boolean {
        return clock() >= safeAtElapsedMs
    }

    /**
     * Milliseconds remaining before [isSafeToInitNow] flips to `true`.
     * Returns 0 once the window has elapsed.
     */
    public fun remainingDelayMs(): Long {
        val remaining = safeAtElapsedMs - clock()
        return if (remaining < 0L) 0L else remaining
    }
}
