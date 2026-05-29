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

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceState
import com.vyzorix.audiorouter.services.oem.VendorRouteResetter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/** Single-pass drift-detector logic, isolated for testability. */
public class RoutePersistenceDaemon(
    private val routeManager: AudioRouteManager,
    private val silentVoipSession: SilentVoipSession,
    /** Cadence between drift checks (ms). 250ms balances detection latency vs. binder cost. */
    private val cadenceMs: Long = 250L,
    /**
     * Optional HAL reset routine used when [Drift.HEADSET_HIJACK] persists
     * past the [hijackEscalationStormDurationMs] window. Null on platforms
     * where we don't ship a VendorRouteResetter yet.
     */
    private val vendorRouteResetter: VendorRouteResetter? = null,
    /** Fast-tick cadence while a HEADSET_HIJACK storm is active. */
    private val hijackStormCadenceMs: Long = 100L,
    /** Maximum duration of a HEADSET_HIJACK storm before HAL reset escalation. */
    private val hijackEscalationStormDurationMs: Long = 5_000L,
) {

    /** Tick counter for forensics. */
    @Volatile
    public var ticksRun: Long = 0L
        private set

    /** Count of out-of-band reassertions this run has triggered. */
    @Volatile
    public var driftReassertions: Long = 0L
        private set

    /** Number of HAL-level resets [VendorRouteResetter] has executed. */
    @Volatile
    public var hijackHalResets: Long = 0L
        private set

    /** True while the watchdog is inside a HEADSET_HIJACK escalation storm. */
    @Volatile
    public var inHijackStorm: Boolean = false
        private set

    /**
     * Long-running loop. Returns only when the caller's CoroutineContext
     * is cancelled.
     */
    public suspend fun run(speakerForceManager: SpeakerForceManager) {
        var lastFrames = silentVoipSession.framesWritten
        var lastFramesObservedAtTick = 0L
        var hijackStormStartedAtMs: Long? = null
        while (coroutineContext.isActive) {
            val tickCadenceMs = if (inHijackStorm) hijackStormCadenceMs else cadenceMs
            delay(tickCadenceMs)
            ticksRun++
            if (speakerForceManager.state.value != SpeakerForceState.ENGAGED) {
                // We're either idle (not yet engaged) or paused (real call).
                // In both cases we don't expect drift correction to help.
                inHijackStorm = false
                hijackStormStartedAtMs = null
                continue
            }
            val drift = computeDrift(
                snapshot = routeManager.snapshot(),
                framesWritten = silentVoipSession.framesWritten,
                framesAtPreviousTick = lastFrames,
                ticksSinceLastFrameProgress = ticksRun - lastFramesObservedAtTick,
            )
            when (drift) {
                Drift.NONE -> {
                    // Storm survived the recovery; clean up tracking state.
                    inHijackStorm = false
                    hijackStormStartedAtMs = null
                }
                Drift.HEADSET_HIJACK -> {
                    val nowMs = System.currentTimeMillis()
                    if (!inHijackStorm) {
                        inHijackStorm = true
                        hijackStormStartedAtMs = nowMs
                        DaemonLogger.get().warn(
                            TAG,
                            "hijack.storm.start cadenceMs=$hijackStormCadenceMs",
                        )
                    }
                    speakerForceManager.forceReassertion()
                    driftReassertions++
                    val stormStarted = hijackStormStartedAtMs
                    if (stormStarted != null &&
                        nowMs - stormStarted >= hijackEscalationStormDurationMs
                    ) {
                        // 5 seconds of forceReassertion didn't clear it. Kick the HAL.
                        val outcome = vendorRouteResetter?.resetRoute()
                        if (outcome != null) {
                            hijackHalResets++
                            DaemonLogger.get().warn(
                                TAG,
                                "hijack.escalate halResets=$hijackHalResets outcome=$outcome",
                            )
                        }
                        // Reset the storm window so we can escalate again if needed.
                        hijackStormStartedAtMs = nowMs
                    }
                }
                else -> {
                    speakerForceManager.forceReassertion()
                    driftReassertions++
                    DaemonLogger.get().info(
                        TAG,
                        "drift.detected kind=$drift reassertions=$driftReassertions",
                    )
                }
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

    private companion object {
        const val TAG: String = "RoutePersistenceDaemon"
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
