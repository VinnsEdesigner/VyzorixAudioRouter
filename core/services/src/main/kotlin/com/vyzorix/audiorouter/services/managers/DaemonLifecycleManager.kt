// DaemonLifecycleManager — orchestrates the strict start / stop order for
// the daemon's sub-systems.
//
// Per doc/BUILD_ORDER.md §Layer 3, Layer 3's start order is:
//   1. AudioFocusHandler  (request VoIP-mode focus)
//   2. SpeakerForceManager.engage() (engage route war)
//   3. SilentVoipSession.start()    (hold the OS in VoIP dominance)
//   4. RoutePersistenceDaemon.start() (watch for drift)
//
// Layer 4+ inserts a step between (3) and (4) for the capture pipeline.
// Layer 5+ inserts diagnostics before (4). We model these as stub steps so
// later layers can drop their bodies in without re-plumbing the orchestrator.

package com.vyzorix.audiorouter.services.managers

import com.vyzorix.audiorouter.services.audio.AudioFocusHandler
import com.vyzorix.audiorouter.services.audio.FocusEvent
import com.vyzorix.audiorouter.services.audio.RouteEvent
import com.vyzorix.audiorouter.services.audio.AudioRouteWatcher
import com.vyzorix.audiorouter.services.voip.RoutePersistenceDaemon
import com.vyzorix.audiorouter.services.voip.SilentVoipSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** Lifecycle states emitted by [DaemonLifecycleManager.state]. */
public enum class DaemonLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
}

/**
 * Orchestrates start / stop of the Layer-3 audio routing pipeline.
 *
 * The class is **not** thread-safe — it is expected to be called from a
 * single owner (PersistentAudioService.onStartCommand / onDestroy).
 */
public class DaemonLifecycleManager(
    private val scope: CoroutineScope,
    private val focusHandler: AudioFocusHandler,
    private val routeWatcher: AudioRouteWatcher,
    private val speakerForceManager: SpeakerForceManager,
    private val silentVoipSession: SilentVoipSession,
    private val routePersistenceDaemon: RoutePersistenceDaemon,
) {

    private var focusJob: Job? = null
    private var routeJob: Job? = null
    private var persistenceJob: Job? = null

    public var lifecycleState: DaemonLifecycleState = DaemonLifecycleState.STOPPED
        private set

    /** Boot the pipeline. Idempotent: calling while [RUNNING] is a no-op. */
    public fun start() {
        if (lifecycleState == DaemonLifecycleState.RUNNING) return
        lifecycleState = DaemonLifecycleState.STARTING

        // 1. Request audio focus and subscribe so we can pause on real calls.
        focusJob = focusHandler.observe()
            .onEach { event -> onFocusEvent(event) }
            .launchIn(scope)

        // 2. Engage the route war.
        speakerForceManager.engage()

        // 3. Start the silent VoIP anchor so the OS treats us as a live VoIP session.
        scope.launch { silentVoipSession.start() }

        // 4. Watch for route drift and forward into SpeakerForceManager.
        routeJob = routeWatcher.observe()
            .onEach { event -> onRouteEvent(event) }
            .launchIn(scope)

        // 5. Start the persistent route-drift detector (Layer 3 minimal body).
        persistenceJob = scope.launch {
            routePersistenceDaemon.run(speakerForceManager)
        }

        lifecycleState = DaemonLifecycleState.RUNNING
    }

    /** Tear down the pipeline in reverse order. */
    public fun stop() {
        if (lifecycleState == DaemonLifecycleState.STOPPED) return
        lifecycleState = DaemonLifecycleState.STOPPING
        persistenceJob?.cancel()
        routeJob?.cancel()
        focusJob?.cancel()
        silentVoipSession.stop()
        speakerForceManager.disengage()
        persistenceJob = null
        routeJob = null
        focusJob = null
        lifecycleState = DaemonLifecycleState.STOPPED
    }

    /** Cooperative cancellation: called by PersistentAudioService.onDestroy. */
    public fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun onFocusEvent(event: FocusEvent) {
        when (event) {
            FocusEvent.Gained -> speakerForceManager.resume()
            FocusEvent.LostTransient,
            FocusEvent.LostTransientDuck -> speakerForceManager.pauseForFocusLoss()
            FocusEvent.LostPermanent -> {
                speakerForceManager.pauseForFocusLoss()
                // Layer 5+ will fire a recovery notification here.
            }
        }
    }

    private fun onRouteEvent(event: RouteEvent) {
        when (event) {
            is RouteEvent.DevicesRemoved -> speakerForceManager.forceReassertion()
            is RouteEvent.WiredHeadsetPlug -> speakerForceManager.forceReassertion()
            is RouteEvent.DevicesAdded,
            is RouteEvent.InitialDevices -> {
                // No reassertion needed — additions don't dislodge the speaker.
            }
        }
    }
}
