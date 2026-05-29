package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/**
 * Current state of the speaker-force "route war" against hardware/HAL drift.
 *
 * See VOIP_ROUTE_FORCE.md for the engine that drives transitions.
 */
@Serializable
public enum class RouteState {
    /** Audio is provably reaching the physical speaker. */
    SPEAKER_FORCED,

    /** A real or phantom headset is plugged in; routing is contested. */
    HEADSET_LOCKED,

    /** Force loop is being out-paced by HAL drift; mitigation pending. */
    DRIFTING,

    /** Initial or unknowable state. */
    UNKNOWN,
}
