// NokiaC22DeviceProfile — services-side wrapper around the Layer-0 device
// profile so route-war machinery can read the C22-relevant knobs through a
// single type-safe accessor instead of pawing through `DeviceQuirkProfile`
// fields every call.
//
// This is intentionally NOT a fork of the profile data; we read straight from
// `DeviceQuirkRegistry.forDevice(...)` (Layer 0) and expose only what services
// callers need. See ADR-0008 for the rationale (single source of truth).

package com.vyzorix.audiorouter.services.oem

import android.os.Build
import com.vyzorix.audiorouter.common.device.AudioModeQuirk
import com.vyzorix.audiorouter.common.device.DeviceQuirkProfile
import com.vyzorix.audiorouter.common.device.DeviceQuirkRegistry

/**
 * Services-side façade over [DeviceQuirkProfile].
 *
 * Layer 3 + 4 audio code reads route-war tuning constants through this class
 * rather than hard-coding "if (isNokiaC22) ..." conditionals. The underlying
 * profile is resolved exactly once per process (lazily); subsequent reads are
 * pure data access.
 */
public class NokiaC22DeviceProfile internal constructor(
    private val profile: DeviceQuirkProfile,
) {

    /** Cadence of the SpeakerForceEngine route-assertion loop. */
    public val routeAssertCadenceMs: Long
        get() = when {
            AudioModeQuirk.PHANTOM_HEADSET_AT_BOOT in profile.audioModeQuirks -> 500L
            else -> 1_000L
        }

    /**
     * Interval at which [AudioModeKeeper-equivalent] callers should
     * re-confirm `AudioManager.mode == MODE_IN_COMMUNICATION` even when the
     * speakerphone flag is intact.
     */
    public val modeReconfirmIntervalMs: Long
        get() = if (AudioModeQuirk.NEEDS_MODE_SWITCH_GAP in profile.audioModeQuirks) {
            10_000L
        } else {
            30_000L
        }

    /**
     * Silence gap (ms) the route-war loop inserts before re-asserting
     * `setSpeakerphoneOn(true)` on quirky HALs that deadlock on rapid mode
     * transitions.
     */
    public val modeSwitchSilenceGapMs: Long
        get() = if (AudioModeQuirk.NEEDS_MODE_SWITCH_GAP in profile.audioModeQuirks) {
            profile.alsaTimingGapMs.toLong()
        } else {
            0L
        }

    /** Whether the device exhibits the "phantom wired headset" lock at boot. */
    public val hasPhantomHeadsetAtBoot: Boolean
        get() = AudioModeQuirk.PHANTOM_HEADSET_AT_BOOT in profile.audioModeQuirks

    /** Whether the device's Bluetooth SCO toggle is too flaky to use. */
    public val isBluetoothScoUnreliable: Boolean
        get() = AudioModeQuirk.UNRELIABLE_BLUETOOTH_SCO in profile.audioModeQuirks

    /** Direct access to the underlying [DeviceQuirkProfile] for callers that need every knob. */
    public val rawProfile: DeviceQuirkProfile
        get() = profile

    public companion object {

        @Volatile private var cached: NokiaC22DeviceProfile? = null

        /**
         * Returns the process-wide profile instance, resolving it from
         * `Build.MANUFACTURER` / `Build.MODEL` on first call. Subsequent
         * calls return the cached value.
         */
        public fun current(): NokiaC22DeviceProfile {
            return cached ?: synchronized(this) {
                cached ?: NokiaC22DeviceProfile(
                    DeviceQuirkRegistry.forDevice(
                        manufacturer = Build.MANUFACTURER ?: "",
                        model = Build.MODEL ?: "",
                    ),
                ).also { cached = it }
            }
        }

        /** Test-only seam: supply an explicit profile (e.g. UnknownDeviceProfile). */
        public fun forTesting(profile: DeviceQuirkProfile): NokiaC22DeviceProfile =
            NokiaC22DeviceProfile(profile)
    }
}
