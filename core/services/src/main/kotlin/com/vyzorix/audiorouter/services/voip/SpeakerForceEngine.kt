// SpeakerForceEngine — "The Enforcer" from doc/VOIP_ROUTE_FORCE.md §2.
//
// Runs a coroutine loop on the supplied scope, re-asserting
// `MODE_IN_COMMUNICATION` + `setSpeakerphoneOn(true)` on a profile-driven
// cadence (Nokia C22 → 500ms, see doc/NOKIA_C22_NOTES.md §3.1 — the
// cadence is calibrated to the C22's policy-drift period).
//
// On Android 12+ (API 31+), the engine also drives
// `CommunicationDeviceSelector.assertBuiltinSpeaker()` because
// `setSpeakerphoneOn` is deprecated on those API levels and the
// `setCommunicationDevice` path is what AudioPolicyManager actually
// consults.
//
// **What this class is NOT:** it doesn't generate audio. The silent
// anchor that holds the OS in VoIP dominance is owned by
// [SilentVoipSession] / [VoipAudioAnchor]. SpeakerForceEngine only writes
// to AudioManager.

package com.vyzorix.audiorouter.services.voip

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import com.vyzorix.audiorouter.services.managers.AudioRouteManager
import com.vyzorix.audiorouter.services.oem.NokiaAudioWorkarounds
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import com.vyzorix.audiorouter.services.oem.OemEnforcementResult
import com.vyzorix.audiorouter.services.oem.UnisocPlatformTweaks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.yield

/** The 500 ms route-assertion loop. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public open class SpeakerForceEngine(
    private val scope: CoroutineScope,
    private val routeManager: AudioRouteManager,
    private val profile: NokiaC22DeviceProfile,
    private val communicationDeviceSelector: CommunicationDeviceSelector =
        CommunicationDeviceSelector(routeManager),
    private val nokiaWorkarounds: NokiaAudioWorkarounds =
        NokiaAudioWorkarounds(routeManager, profile),
    private val unisocTweaks: UnisocPlatformTweaks = UnisocPlatformTweaks(profile),
) {

    @Volatile
    private var paused: Boolean = false

    /**
     * Receiving on this channel forces an immediate route reassertion
     * (used by [RoutePersistenceDaemon] when it detects drift mid-tick).
     */
    private val reassertSignals: Channel<Unit> = Channel(capacity = Channel.UNLIMITED)

    /** Total reassertion count (forensics; surfaced by the dashboard in Layer 5+). */
    @Volatile
    public var reassertionCount: Long = 0L
        private set

    /** Number of ticks that observed no drift. */
    @Volatile
    public var quietTickCount: Long = 0L
        private set

    /** Number of OEM-workaround reassertions that completed only after retries. */
    @Volatile
    public var oemRetryCount: Long = 0L
        private set

    /** Number of OEM-workaround reassertions that failed every retry. */
    @Volatile
    public var oemFailureCount: Long = 0L
        private set

    /** Last reassertion timestamp (`System.nanoTime()` granularity). */
    @Volatile
    public var lastReassertionNanos: Long = 0L
        private set

    /** Launch the loop on [scope]; returns the [Job] so the caller can cancel. */
    public open fun start(): Job = scope.launch {
        // Phase 1 (Escalation per doc/VOIP_ROUTE_FORCE.md §3.2): set the
        // route once eagerly before the loop so the first audio tick has
        // somewhere to go. We go through the OEM workaround path even on the
        // first assertion because the Nokia §3 silent-drop bug shows up on
        // the very first post-boot setSpeakerphoneOn(true) call.
        applyRouteAssertion(reason = "initial")
        communicationDeviceSelector.assertBuiltinSpeaker()

        // SCHED_FIFO might have silently fallen back to SCHED_OTHER; the
        // UnisocPlatformTweaks helper bumps the cadence down accordingly so
        // we don't starve the system at the un-elevated priority.
        val cadenceMs = unisocTweaks.fallbackTickCadenceMs(profile.routeAssertCadenceMs)
        DaemonLogger.get().info(TAG, "engine.start cadenceMs=$cadenceMs throttled=${cadenceMs != profile.routeAssertCadenceMs}")
        while (isActive) {
            if (paused) {
                yield()
                delay(cadenceMs)
                continue
            }
            // Wait for either the next tick OR an explicit reassertion poke.
            select<Unit> {
                onTimeout(cadenceMs) { tick() }
                reassertSignals.onReceive { forceReassert() }
            }
        }
    }

    /** Pause the loop (focus loss). */
    public open fun pause() {
        paused = true
    }

    /** Resume the loop (focus gain). Triggers an immediate reassertion. */
    public open fun resume() {
        paused = false
        forceReassertNow()
    }

    /**
     * Trigger an immediate reassertion from outside the engine
     * (e.g. RoutePersistenceDaemon notices the speaker disappeared).
     *
     * Safe to call from any thread / coroutine.
     */
    public open fun forceReassertNow() {
        reassertSignals.trySend(Unit)
    }

    /** Internal tick: read state, reassert iff something drifted. */
    private fun tick() {
        val snapshot = routeManager.snapshot()
        val needsMode = snapshot.mode != android.media.AudioManager.MODE_IN_COMMUNICATION
        val needsSpeaker = !snapshot.isSpeakerphoneOn
        val needsBuiltinDevice = !communicationDeviceSelector.isBuiltinSpeakerActive()

        if (needsMode || needsSpeaker || needsBuiltinDevice) {
            forceReassert()
        } else {
            quietTickCount++
        }
    }

    /** Internal reassertion path — used by both tick() and forceReassert(). */
    private fun forceReassert() {
        applyRouteAssertion(reason = "drift")
        communicationDeviceSelector.assertBuiltinSpeaker()
    }

    /** Run the route assertion through the OEM workaround path. */
    private fun applyRouteAssertion(reason: String) {
        when (val outcome = nokiaWorkarounds.assertVoipSpeakerRoute()) {
            OemEnforcementResult.APPLIED_DIRECTLY -> Unit
            OemEnforcementResult.APPLIED_AFTER_RETRY -> {
                oemRetryCount++
                DaemonLogger.get().info(
                    TAG,
                    "oem.retry.success reason=$reason retries=$oemRetryCount outcome=$outcome",
                )
            }
            OemEnforcementResult.FAILED -> {
                oemFailureCount++
                DaemonLogger.get().warn(
                    TAG,
                    "oem.retry.failed reason=$reason failures=$oemFailureCount outcome=$outcome",
                )
            }
        }
        recordReassertion()
    }

    private fun recordReassertion() {
        reassertionCount++
        lastReassertionNanos = System.nanoTime()
    }

    private companion object {
        const val TAG: String = "SpeakerForceEngine"
    }
}
