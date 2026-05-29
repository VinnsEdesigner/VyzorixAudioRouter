// CommunicationDeviceSelector — wraps the Android-12+ `setCommunicationDevice`
// path that supersedes `setSpeakerphoneOn` for VoIP routing.
//
// Background (doc/VOIP_ROUTE_FORCE.md §1.2): on A12+, `setSpeakerphoneOn`
// is documented as deprecated and AudioPolicyManager increasingly ignores
// it in favour of `getCommunicationDevice`/`setCommunicationDevice`. The
// daemon writes to BOTH for belt-and-braces, so this selector exists as
// a typed wrapper that gracefully no-ops on older API levels.
//
// On API < 31 the methods don't exist; we degrade to "true" semantics for
// the asserter (since `setSpeakerphoneOn(true)` is still authoritative on
// those releases) and let SpeakerForceEngine carry the load.

package com.vyzorix.audiorouter.services.voip

import android.media.AudioDeviceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import com.vyzorix.audiorouter.services.managers.AudioRouteManager

/** API-31+ wrapper around setCommunicationDevice. Falls back gracefully. */
public open class CommunicationDeviceSelector(
    private val routeManager: AudioRouteManager,
) {

    /**
     * Returns `true` iff the system reports the BUILTIN_SPEAKER as the
     * current communication device. On API < 31 the call is short-circuited
     * to `true` because the relevant API doesn't exist; the engine relies
     * on `isSpeakerphoneOn` in that case.
     */
    public open fun isBuiltinSpeakerActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return isBuiltinSpeakerActiveSPlus()
    }

    /**
     * Force the system communication device to BUILTIN_SPEAKER if such a
     * device is exposed. Returns `true` if the call succeeded, `false` if
     * the selector could not find a matching device or the OS rejected
     * the request.
     */
    public open fun assertBuiltinSpeaker(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return assertBuiltinSpeakerSPlus()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun isBuiltinSpeakerActiveSPlus(): Boolean {
        val current = routeManager.rawAudioManager.communicationDevice ?: return false
        return current.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun assertBuiltinSpeakerSPlus(): Boolean {
        val am = routeManager.rawAudioManager
        // Already correct — leave it.
        val current = am.communicationDevice
        if (current?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            return true
        }
        val target = am.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: return false
        return am.setCommunicationDevice(target)
    }
}
