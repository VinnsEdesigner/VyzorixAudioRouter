// SpeakerForceManager — single source of truth for "is the route war
// engaged right now?".
//
// Anyone in the daemon that needs to ask "should I be in VoIP-mode?" or
// "is the silent anchor running?" routes through this class. Internally
// it owns the SpeakerForceEngine + AudioModeKeeper instances and toggles
// them in lock-step.
//
// Threading: external API is thread-safe (StateFlow + atomic flag); the
// internal coroutines are launched on the supplied CoroutineScope which
// is expected to be tied to PersistentAudioService's lifecycle.

package com.vyzorix.audiorouter.services.managers

import com.vyzorix.audiorouter.common.enums.DaemonState
import com.vyzorix.audiorouter.common.enums.RouteState
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import com.vyzorix.audiorouter.services.oem.NokiaC22DeviceProfile
import com.vyzorix.audiorouter.services.state.DaemonStateRecorder
import com.vyzorix.audiorouter.services.voip.AudioModeKeeper
import com.vyzorix.audiorouter.services.voip.SpeakerForceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** External state of the route-war machinery. */
public enum class SpeakerForceState {
    IDLE,
    ENGAGED,
    PAUSED_FOR_FOCUS,
}

/**
 * Owns the lifecycle of [SpeakerForceEngine] + [AudioModeKeeper]. The
 * service-side bootstrap calls [engage] when the daemon is ready and
 * [pauseForFocusLoss] / [resume] in response to focus events; everything
 * else is internal to this class.
 */
public class SpeakerForceManager(
    private val scope: CoroutineScope,
    private val routeManager: AudioRouteManager,
    private val profile: NokiaC22DeviceProfile,
    private val wakeLockGuard: WakeLockGuard? = null,
    private val stateRecorder: DaemonStateRecorder? = null,
    private val engineFactory: (
        scope: CoroutineScope,
        routeManager: AudioRouteManager,
        profile: NokiaC22DeviceProfile,
    ) -> SpeakerForceEngine = { s, r, p -> SpeakerForceEngine(s, r, p) },
    private val keeperFactory: (
        scope: CoroutineScope,
        routeManager: AudioRouteManager,
        profile: NokiaC22DeviceProfile,
    ) -> AudioModeKeeper = { s, r, p -> AudioModeKeeper(s, r, p) },
) {

    private val _state = MutableStateFlow(SpeakerForceState.IDLE)
    public val state: StateFlow<SpeakerForceState> = _state.asStateFlow()

    private var engine: SpeakerForceEngine? = null
    private var keeper: AudioModeKeeper? = null
    private var engineJob: Job? = null
    private var keeperJob: Job? = null

    /**
     * Engage the route war. Idempotent — calling while already engaged is
     * a no-op. Calling while [PAUSED_FOR_FOCUS] resumes the loops.
     */
    public fun engage() {
        when (_state.value) {
            SpeakerForceState.ENGAGED -> return
            SpeakerForceState.PAUSED_FOR_FOCUS -> {
                resume()
                return
            }
            SpeakerForceState.IDLE -> {
                val newEngine = engineFactory(scope, routeManager, profile)
                val newKeeper = keeperFactory(scope, routeManager, profile)
                engine = newEngine
                keeper = newKeeper
                engineJob = newEngine.start()
                keeperJob = newKeeper.start()
                wakeLockGuard?.acquire()
                _state.value = SpeakerForceState.ENGAGED
                stateRecorder?.record(
                    daemonState = DaemonState.RUNNING,
                    routeState = RouteState.SPEAKER_FORCED,
                )
                DaemonLogger.get().info(TAG, "engage state=ENGAGED wakelock=${wakeLockGuard?.isHeld() == true}")
            }
        }
    }

    /** Disengage entirely (test teardown / shutdown). */
    public fun disengage() {
        engineJob?.cancel()
        keeperJob?.cancel()
        engineJob = null
        keeperJob = null
        engine = null
        keeper = null
        routeManager.disengageVoipSpeakerRoute()
        wakeLockGuard?.release()
        _state.value = SpeakerForceState.IDLE
        stateRecorder?.record(
            daemonState = DaemonState.STOPPED,
            routeState = RouteState.UNKNOWN,
        )
        DaemonLogger.get().info(TAG, "disengage state=IDLE")
    }

    /**
     * Pause the assertion loops during a focus-loss window (e.g. a real
     * phone call). The loops do not actually unwind — they just stop
     * touching AudioManager until [resume] is called.
     */
    public fun pauseForFocusLoss() {
        if (_state.value != SpeakerForceState.ENGAGED) return
        engine?.pause()
        keeper?.pause()
        _state.value = SpeakerForceState.PAUSED_FOR_FOCUS
        stateRecorder?.record(
            daemonState = DaemonState.RECOVERING,
            routeState = RouteState.UNKNOWN,
        )
        DaemonLogger.get().info(TAG, "pauseForFocusLoss state=PAUSED_FOR_FOCUS")
    }

    /** Resume from [pauseForFocusLoss]. Immediately re-asserts the route. */
    public fun resume() {
        if (_state.value != SpeakerForceState.PAUSED_FOR_FOCUS) return
        engine?.resume()
        keeper?.resume()
        _state.value = SpeakerForceState.ENGAGED
        stateRecorder?.record(
            daemonState = DaemonState.RUNNING,
            routeState = RouteState.SPEAKER_FORCED,
        )
        DaemonLogger.get().info(TAG, "resume state=ENGAGED")
    }

    /**
     * Manual reassertion poke — called by RoutePersistenceDaemon when it
     * detects a drift back to the broken headset codec.
     */
    public fun forceReassertion() {
        engine?.forceReassertNow()
    }

    private companion object {
        const val TAG: String = "SpeakerForceManager"
    }
}
