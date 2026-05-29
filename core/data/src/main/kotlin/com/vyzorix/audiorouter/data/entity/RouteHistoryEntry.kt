package com.vyzorix.audiorouter.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audio route transition recorded by Layer 3's `RouteHistoryRecorder`.
 *
 * Used by `RouteForensicsReporter` (Layer 6) to reconstruct the timeline
 * leading up to a `RouteState.DRIFTING` or `RouteState.HEADSET_LOCKED`
 * incident. See `doc/SYSTEM_MAP.md` §4.3 and `doc/VOIP_ROUTE_FORCE.md`.
 *
 * Two indices:
 *   - [transitionEpochMs] for the common "last N seconds" recovery query.
 *   - [fromRoute] + [toRoute] for the rarer "all phantom-headset hops"
 *     forensics query that Layer 6 issues during crash report assembly.
 */
@Entity(
    tableName = "route_history",
    indices = [
        Index(value = ["transitionEpochMs"]),
        Index(value = ["fromRoute", "toRoute"]),
    ],
)
public data class RouteHistoryEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "transitionEpochMs")
    val transitionEpochMs: Long,

    /** The route the system was on immediately before this transition. */
    @ColumnInfo(name = "fromRoute")
    val fromRoute: AudioRouteKind,

    /** The route the system landed on. */
    @ColumnInfo(name = "toRoute")
    val toRoute: AudioRouteKind,

    /** What caused the transition (force loop, HAL drift, plug event, etc). */
    @ColumnInfo(name = "reason")
    val reason: RouteTransitionReason,

    /**
     * `AudioDeviceInfo.id` of the device that was selected post-transition,
     * or `null` if no concrete device is bound (e.g. transition to `UNKNOWN`).
     * Persisted as-is — the value is opaque outside Layer 3 and is only
     * used for cross-referencing logcat dumps after the fact.
     */
    @ColumnInfo(name = "audioDeviceId")
    val audioDeviceId: Int?,

    /**
     * Free-form trace marker (call-site id, e.g. `"SpeakerForceEngine.tick"`).
     * Capped to 64 chars by the recorder.
     */
    @ColumnInfo(name = "originMarker")
    val originMarker: String,
)

/**
 * Coarse-grained audio routing destinations as seen by
 * `RouteHistoryRecorder`. Intentionally narrower than the full set of
 * Android `AudioDeviceInfo` types: the daemon only differentiates the
 * routes that matter for the route-war heuristics.
 */
public enum class AudioRouteKind {
    SPEAKER,
    EARPIECE,
    WIRED_HEADSET,
    BLUETOOTH_HEADSET,
    USB_AUDIO,
    UNKNOWN,
}

/**
 * Why a particular transition happened. Useful for distinguishing
 * "user plugged in a headset" from "HAL silently retracted speaker route".
 */
public enum class RouteTransitionReason {
    FORCE_LOOP_REASSERT,
    HAL_DRIFT,
    USER_PLUG_EVENT,
    BLUETOOTH_CONNECT,
    BLUETOOTH_DISCONNECT,
    PHANTOM_HEADSET_DETECTED,
    SYSTEM_BROADCAST,
    INITIAL_BOOT,
    UNKNOWN,
}
