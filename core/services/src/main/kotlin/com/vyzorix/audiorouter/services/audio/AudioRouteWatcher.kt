// AudioRouteWatcher — observes physical headphone-jack events and the
// system's `AudioDeviceCallback` and republishes them as a Flow of
// [RouteEvent] for the rest of the daemon to consume.
//
// Layer 3 wiring: [RoutePersistenceDaemon] subscribes to this Flow and
// triggers the SpeakerForceEngine reassertion whenever the OS announces a
// route change. In Layer 4 the same Flow feeds the capture-pipeline
// rebinder.
//
// Threading: callbacks are scheduled on `AudioManager`'s default handler
// (main looper); we deliberately do NOT supply our own handler so that
// device-callback ordering matches the system's broadcast ordering on the
// Nokia C22 (empirically: a fresh handler can fire `onAudioDevicesAdded`
// before `ACTION_HEADSET_PLUG` settles, which leads to a brief race in the
// reassertion loop).

package com.vyzorix.audiorouter.services.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A single route-change observation surfaced by [AudioRouteWatcher.observe]. */
public sealed interface RouteEvent {
    /** A new output device became available (e.g. headphones plugged in). */
    public data class DevicesAdded(val added: List<AudioDeviceInfo>) : RouteEvent

    /** A previously-available output device went away. */
    public data class DevicesRemoved(val removed: List<AudioDeviceInfo>) : RouteEvent

    /** ACTION_HEADSET_PLUG broadcast — wired-jack state changed. */
    public data class WiredHeadsetPlug(val state: WiredHeadsetState, val hasMicrophone: Boolean) : RouteEvent

    /** Initial state emitted when a subscriber starts observing. */
    public data class InitialDevices(val devices: List<AudioDeviceInfo>) : RouteEvent
}

/** Wired-headset plug state. */
public enum class WiredHeadsetState {
    UNPLUGGED,
    PLUGGED,
    UNKNOWN,
}

/** Observes the daemon's audio-routing inputs. */
public class AudioRouteWatcher(
    private val context: Context,
    private val audioManager: AudioManager = context.getSystemService(
        Context.AUDIO_SERVICE,
    ) as AudioManager,
) {

    /**
     * A cold [Flow] that emits a [RouteEvent.InitialDevices] on subscription
     * and then every [AudioDeviceCallback] / `ACTION_HEADSET_PLUG` event for
     * the lifetime of the subscription.
     *
     * Use `flowOn(Dispatchers.Default)` if downstream processing is heavy;
     * the callbacks themselves run on the main looper as noted above.
     */
    public fun observe(): Flow<RouteEvent> = callbackFlow {
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(RouteEvent.DevicesAdded(addedDevices.toList()))
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(RouteEvent.DevicesRemoved(removedDevices.toList()))
            }
        }
        val plugReceiver = object : BroadcastReceiver() {
            override fun onReceive(_context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_HEADSET_PLUG) return
                val state = when (intent.getIntExtra("state", -1)) {
                    0 -> WiredHeadsetState.UNPLUGGED
                    1 -> WiredHeadsetState.PLUGGED
                    else -> WiredHeadsetState.UNKNOWN
                }
                val hasMic = intent.getIntExtra("microphone", 0) == 1
                trySend(RouteEvent.WiredHeadsetPlug(state, hasMic))
            }
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, /* handler = */ null)
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(plugReceiver, IntentFilter(Intent.ACTION_HEADSET_PLUG))

        // Seed the subscriber with the current state.
        val initial = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        trySend(RouteEvent.InitialDevices(initial))

        awaitClose {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            runCatching { context.unregisterReceiver(plugReceiver) }
        }
    }

    /** One-shot read of the current output device list. */
    public fun currentOutputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    /**
     * Convenience helper that returns true iff a built-in speaker is
     * present in the active output device list. Used by the route-assertion
     * loop as a quick "did the speaker disappear?" check.
     */
    public fun isBuiltInSpeakerActive(): Boolean = currentOutputDevices().any {
        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }

    /** Convenience: is a wired (or USB) headset / headphone currently active? */
    public fun isWiredHeadsetActive(): Boolean = currentOutputDevices().any {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
    }
}
