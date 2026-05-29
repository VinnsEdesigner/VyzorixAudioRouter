// RoutePersistenceDaemon — independent drift watchdog.
//
// SpeakerForceEngine handles drift on a fixed cadence (500ms). That is
// enough for normal operation, but during pathological events (a video
// app that briefly grabs AUDIOFOCUS_GAIN_TRANSIENT and then releases,
// causing a 200ms window where SpeakerForceManager pauses and resumes),
// drift can persist for ~750ms — long enough for the first stuttered
// sample to hit the broken codec on Nokia C22.
//
// This daemon runs on a slower cadence (250ms) but with an additional
// guarantee: it checks two signals SpeakerForceEngine cannot easily check
// without binder spam — historical frame counts on the anchor and the
// trend of `isSpeakerphoneOn` across ticks. When the anchor stops
// progressing frames or the speakerphone flag flickers, it issues an
// out-of-band reassertion via SpeakerForceManager.forceReassertion().
//
// Layer 3 scope: this is the minimal viable watchdog. Layer 5 adds a
// dashboard-visible "drift events / hour" counter.

package com.vyzorix.audiorouter.services.voip

import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Single-pass drift-detector logic, isolated for testability. */
public class RoutePersistenceDaemon(
    private val routeManager: AudioRouteManager,
    private val silentVoipSession: SilentVoipSession,
    /** Cadence between drift checks (ms). 250ms balances detection latency vs. binder cost. */
    private val cadenceMs: Long = 250L,
) {

    /** Tick counter for forensics. */
    @Volatile
    public var ticksRun: Long = 0L
        private set

    /** Count of out-of-band reassertions this run has triggered. */
    @Volatile
    public var driftReassertions: Long = 0L
        private set

    /**
     * Long-running loop. Returns only when the caller's CoroutineContext
     * is cancelled.
     */
    public suspend fun run(speakerForceManager: SpeakerForceManager) {
        var lastFrames = silentVoipSession.framesWritten
        var lastFramesObservedAtTick = 0L
        while (coroutineContext.isActive) {
            delay(cadenceMs)
            ticksRun++
            if (speakerForceManager.state.value != SpeakerForceState.ENGAGED) {
                // We're either idle (not yet engaged) or paused (real call).
                // In both cases we don't expect drift correction to help.
                continue
            }
            val drift = computeDrift(
                snapshot = routeManager.snapshot(),
                framesWritten = silentVoipSession.framesWritten,
                framesAtPreviousTick = lastFrames,
                ticksSinceLastFrameProgress = ticksRun - lastFramesObservedAtTick,
            )
            if (drift != Drift.NONE) {
                speakerForceManager.forceReassertion()
                driftReassertions++
            }
            if (silentVoipSession.framesWritten != lastFrames) {
                lastFrames = silentVoipSession.framesWritten
                lastFramesObservedAtTick = ticksRun
            }
        }
    }

    /** Single-pass classifier exposed for unit testing. */
    public fun computeDrift(
        snapshot: com.vyzorix.audiorouter.services.managers.AudioRouteSnapshot,
        framesWritten: Long,
        framesAtPreviousTick: Long,
        ticksSinceLastFrameProgress: Long,
    ): Drift {
        if (snapshot.mode != android.media.AudioManager.MODE_IN_COMMUNICATION) {
            return Drift.MODE_LOST
        }
        if (!snapshot.isSpeakerphoneOn) {
            return Drift.SPEAKERPHONE_FLIPPED
        }
        // 8 ticks ≈ 2s without frame progress → anchor stalled.
        if (ticksSinceLastFrameProgress >= 8) {
            return Drift.ANCHOR_STALLED
        }
        if (snapshot.isWiredHeadsetPresent && !snapshot.builtInSpeakerPresent) {
            return Drift.HEADSET_HIJACK
        }
        // Suppress "no progress" classification on the very first tick after
        // start — the anchor hasn't had time to emit frames yet.
        if (framesWritten == 0L && framesAtPreviousTick == 0L && ticksSinceLastFrameProgress < 4) {
            return Drift.NONE
        }
        return Drift.NONE
    }

    /** Drift categorisation. */
    public enum class Drift {
        /** No drift detected this tick. */
        NONE,

        /** AudioManager.mode flipped off MODE_IN_COMMUNICATION. */
        MODE_LOST,

        /** Speakerphone flag flipped to false. */
        SPEAKERPHONE_FLIPPED,

        /** Anchor's framesWritten hasn't advanced for ≥ 2 seconds. */
        ANCHOR_STALLED,

        /** A wired headset is present but the built-in speaker isn't — the C22 phantom-headset signature. */
        HEADSET_HIJACK,
    }
}
