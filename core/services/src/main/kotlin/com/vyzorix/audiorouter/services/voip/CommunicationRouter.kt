// CommunicationRouter — high-level orchestration over the four primitives
// (AudioRouteManager, SilentVoipSession, SpeakerForceEngine, AudioModeKeeper).
//
// This sits one level up from [SpeakerForceManager] and exists mostly to
// give Layer 4+ (capture pipeline) a single ingress for "tell me the
// route is engaged and stable". Layer 3 callers go through
// [SpeakerForceManager] directly; Layer 4+ goes through CommunicationRouter
// so that future complexity (Bluetooth SCO arbitration, A2DP bypass) has
// somewhere to land without rewiring the manager.

package com.vyzorix.audiorouter.services.voip

import com.vyzorix.audiorouter.services.managers.SpeakerForceManager
import com.vyzorix.audiorouter.services.managers.SpeakerForceState

/** External read-only view of the route's stability. */
public data class RouteHealth(
    val speakerForceState: SpeakerForceState,
    val silentAnchorActive: Boolean,
    val anchorFramesWritten: Long,
)

/** High-level read/control surface for the route war. */
public class CommunicationRouter(
    private val speakerForceManager: SpeakerForceManager,
    private val silentVoipSession: SilentVoipSession,
) {

    /** Sample the current route health for the dashboard / Layer-4 consumers. */
    public fun health(): RouteHealth = RouteHealth(
        speakerForceState = speakerForceManager.state.value,
        silentAnchorActive = silentVoipSession.isActive,
        anchorFramesWritten = silentVoipSession.framesWritten,
    )

    /**
     * "Is the route believed to be stable right now?" — what Layer 4+
     * uses to decide whether to start the capture pipe.
     */
    public fun isRouteEngagedAndStable(): Boolean {
        return speakerForceManager.state.value == SpeakerForceState.ENGAGED &&
            silentVoipSession.isActive
    }

    /**
     * Issue a hard reassertion that bypasses the cadence. Used by
     * [RoutePersistenceDaemon] when the watcher detects drift.
     */
    public fun reassertRouteNow() {
        speakerForceManager.forceReassertion()
    }
}
