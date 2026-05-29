package com.vyzorix.audiorouter.common.extensions

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * `AudioManager` helpers used by the speaker-force engine and the route
 * recorders.
 *
 * Every method here is *read-only* — they observe routing state but never
 * mutate it. Mutation lives in Layer 3's `SpeakerForceEngine` so the
 * write surface stays auditable.
 */

/**
 * Returns `true` when the system reports at least one route currently
 * pointing at a built-in speaker. Used by `SpeakerForceEngine` to short-
 * circuit `setSpeakerphoneOn(true)` calls when nothing needs to change.
 */
public fun AudioManager.isSpeakerActive(): Boolean {
    val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
    return devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}

/**
 * Returns `true` when a wired or Bluetooth headset is currently plugged
 * into the system's output device list (real or phantom). The
 * `RouteHistoryRecorder` uses this to tag transitions with the right
 * [com.vyzorix.audiorouter.data.entity.RouteTransitionReason].
 */
public fun AudioManager.isHeadsetPlugged(): Boolean {
    val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
    return devices.any { info ->
        info.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            info.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            info.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            info.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            info.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}

/**
 * Returns the human-readable name of the current `AudioManager.mode`
 * (e.g. `"NORMAL"`, `"IN_COMMUNICATION"`, `"RINGTONE"`). Used by the
 * diagnostic overlay and by the route forensics reporter.
 */
public fun AudioManager.getCurrentModeName(): String = when (mode) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
    AudioManager.MODE_CALL_REDIRECT -> "CALL_REDIRECT"
    AudioManager.MODE_COMMUNICATION_REDIRECT -> "COMMUNICATION_REDIRECT"
    else -> "UNKNOWN($mode)"
}

/**
 * Returns the first built-in speaker output device, or `null` if the
 * platform doesn't report one. `SpeakerForceEngine.setPreferredDevice`
 * uses this to pin routing to the physical speaker on devices where
 * `setSpeakerphoneOn(true)` is honored only intermittently (Nokia C22).
 */
public fun AudioManager.findBuiltInSpeaker(): AudioDeviceInfo? {
    val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return null
    return devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
}
