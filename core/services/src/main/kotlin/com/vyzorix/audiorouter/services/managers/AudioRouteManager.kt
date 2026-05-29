// AudioRouteManager — single ownership point for the daemon's interaction
// with android.media.AudioManager.
//
// Why a manager class rather than direct AudioManager calls scattered
// across the daemon:
//   1. The AudioManager API has subtle thread-safety expectations (its
//      internal binder transactions are MainThread-safe but its
//      AudioPolicyManager round-trips happen synchronously). Funnel
//      everything through one class so we can attach logging and
//      adaptive backoff without auditing every call site.
//   2. The Nokia C22 has a quirk where setSpeakerphoneOn followed by a
//      mode change within ~50ms silently no-ops (see DOC_3 §4.2). The
//      ordering policy lives here, not in N callers.
//
// This class does NOT implement the route-assertion *loop* — that's
// SpeakerForceEngine. AudioRouteManager is just the typed interface to
// the OS that SpeakerForceEngine drives.

package com.vyzorix.audiorouter.services.managers

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Snapshot of the relevant slice of AudioManager state at a moment in time. */
public data class AudioRouteSnapshot(
    val mode: Int,
    val isSpeakerphoneOn: Boolean,
    val isBluetoothScoOn: Boolean,
    val isWiredHeadsetPresent: Boolean,
    val builtInSpeakerPresent: Boolean,
    val activeOutputs: List<AudioDeviceInfo>,
)

/**
 * Typed accessor over `AudioManager` for the daemon's route-war code.
 *
 * `@Suppress("DEPRECATION")` is intentional and load-bearing: the
 * `isSpeakerphoneOn` / `setSpeakerphoneOn` getters and setters are
 * deprecated on A12+ in favor of `setCommunicationDevice`, but the route
 * war (doc/VOIP_ROUTE_FORCE.md §1.2) writes to BOTH paths simultaneously
 * — AudioPolicyManager on the Nokia C22 still consults
 * `isSpeakerphoneOn` for the active route. We layer the new API on top
 * via `CommunicationDeviceSelector`.
 */
@Suppress("DEPRECATION")
public open class AudioRouteManager(
    context: Context,
    audioManager: AudioManager? = null,
) {

    private val audioManager: AudioManager = audioManager
        ?: context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Read-only access to the underlying AudioManager (tests / Layer 4+). */
    public val rawAudioManager: AudioManager get() = audioManager

    /** Snapshot the current AudioManager state in one atomic read pass. */
    public open fun snapshot(): AudioRouteSnapshot {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        return AudioRouteSnapshot(
            mode = audioManager.mode,
            isSpeakerphoneOn = audioManager.isSpeakerphoneOn,
            isBluetoothScoOn = audioManager.isBluetoothScoOn,
            isWiredHeadsetPresent = outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            },
            builtInSpeakerPresent = outputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER },
            activeOutputs = outputs,
        )
    }

    /** Switch the system audio mode. Logs the previous value for forensics. */
    public open fun setMode(targetMode: Int) {
        if (audioManager.mode != targetMode) {
            audioManager.mode = targetMode
        }
    }

    /**
     * Toggle speakerphone with optional silence gap.
     *
     * The [silenceGapMs] is honored on devices that need a pause between
     * mode-change and speakerphone-toggle (see
     * `oem/NokiaC22DeviceProfile.modeSwitchSilenceGapMs`). A zero gap means
     * no pause — the call returns synchronously.
     */
    public open fun setSpeakerphoneOn(on: Boolean, silenceGapMs: Long = 0L) {
        if (silenceGapMs > 0L) {
            // Synchronous sleep is acceptable here because the caller
            // (SpeakerForceEngine) is already on a non-main coroutine and
            // the gap is ≤ a few ms.
            try {
                Thread.sleep(silenceGapMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (audioManager.isSpeakerphoneOn != on) {
            audioManager.isSpeakerphoneOn = on
        }
    }

    /** Convenience: enable VoIP-mode + force-speaker in the canonical order. */
    public open fun engageVoipSpeakerRoute(modeSwitchGapMs: Long = 0L) {
        setMode(AudioManager.MODE_IN_COMMUNICATION)
        setSpeakerphoneOn(on = true, silenceGapMs = modeSwitchGapMs)
    }

    /** Inverse of [engageVoipSpeakerRoute] — restore MODE_NORMAL. */
    public open fun disengageVoipSpeakerRoute() {
        setSpeakerphoneOn(on = false, silenceGapMs = 0L)
        setMode(AudioManager.MODE_NORMAL)
    }
}
