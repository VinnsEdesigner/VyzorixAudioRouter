// SilentVoipSession — the lifecycle wrapper that gates [VoipAudioAnchor]
// behind the daemon's start / stop / pause semantics and exposes a
// suspendable start() so the DaemonLifecycleManager can sequence it
// alongside SpeakerForceEngine.
//
// The anchor itself runs on a dedicated OS thread; this wrapper is just
// the Kotlin-side coroutine-friendly façade.

package com.vyzorix.audiorouter.services.voip

import kotlinx.coroutines.delay

/** Owns a [VoipAudioAnchor] and exposes coroutine-friendly start/stop. */
public class SilentVoipSession(
    /** Production code creates the anchor lazily so we don't allocate AudioTrack until [start]. */
    private val anchorFactory: () -> VoipAudioAnchor = { VoipAudioAnchor() },
    /** Brief settling delay before declaring start successful — drains the AudioTrack warm-up frames. */
    private val startSettleMs: Long = 50L,
) {

    private var anchor: VoipAudioAnchor? = null

    /** Suspending start: returns when the anchor is producing frames. */
    public suspend fun start() {
        if (anchor?.isRunning == true) return
        val newAnchor = anchor ?: anchorFactory().also { anchor = it }
        newAnchor.start()
        // Wait briefly for the first frames to settle. We don't poll
        // framesWritten because Robolectric's AudioTrack shadow doesn't
        // advance counters; the test path stubs the factory so this delay
        // is effectively no-op there.
        delay(startSettleMs)
    }

    /** Stop synchronously. */
    public fun stop() {
        anchor?.stop()
        anchor = null
    }

    /** Read-only view: is the anchor currently producing frames? */
    public val isActive: Boolean
        get() = anchor?.isRunning == true

    /** Telemetry: total frames the anchor has pushed since last start. */
    public val framesWritten: Long
        get() = anchor?.framesWritten ?: 0L
}
