// AudioModeKeeper — slow-cadence backstop to SpeakerForceEngine.
//
// SpeakerForceEngine asserts mode + speakerphone every 500ms; that handles
// drift caused by app focus changes / route events. But the Nokia C22 has
// a second class of drift documented in doc/NOKIA_C22_NOTES.md §3.3 — the
// AudioPolicyManager occasionally "forgets" the VoIP mode entirely after
// long sleeps (~5min idle) without firing any callback. The 500ms loop is
// optimised for tight drift and will catch this too, but on the order of
// 500ms, by which point an audible click may have reached the broken
// codec.
//
// AudioModeKeeper exists as a slow secondary loop that runs every
// `modeReconfirmIntervalMs` (10s on Nokia C22) and forces a no-op mode
// rewrite. The cost is negligible (one binder transaction) and the
// resulting AudioPolicyManager refresh closes that hole.

package com.vyzorix.audiorouter.services.voip

import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Long-cadence mode re-confirmer (10s on Nokia C22). */
public open class AudioModeKeeper(
    private val scope: CoroutineScope,
    private val routeManager: AudioRouteManager,
    private val profile: NokiaC22DeviceProfile,
) {

    @Volatile
    private var paused: Boolean = false

    /** Tick count for forensics. */
    @Volatile
    public var refreshCount: Long = 0L
        private set

    public open fun start(): Job = scope.launch {
        val cadenceMs = profile.modeReconfirmIntervalMs
        while (isActive) {
            delay(cadenceMs)
            if (paused) continue
            val mode = routeManager.snapshot().mode
            if (mode != android.media.AudioManager.MODE_IN_COMMUNICATION) {
                routeManager.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION)
            } else {
                // No-op rewrite: AudioPolicyManager refreshes its cache.
                routeManager.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION)
            }
            refreshCount++
        }
    }

    public open fun pause() {
        paused = true
    }

    public open fun resume() {
        paused = false
    }
}
