package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/** State of the MediaProjection-driven playback capture pipeline. */
@Serializable
public enum class CaptureState {
    /** Frames are flowing into the native ring buffer at the expected rate. */
    ACTIVE,

    /** Capture is alive but starved — no frames inside the last 2 s window. */
    STARVED,

    /** OS / policy is currently blocking capture (focus loss, foreground app overlay, etc). */
    BLOCKED,

    /** MediaProjection token was revoked by the system; needs re-acquisition. */
    REVOKED,

    /** Daemon is intentionally idle (no playback expected). */
    IDLE,
}
