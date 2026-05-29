package com.vyzorix.audiorouter.common.model

import com.vyzorix.audiorouter.common.enums.CaptureState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Lightweight view of the capture pipeline's current health. */
@Serializable
public data class CaptureStatus(
    @SerialName("state") val state: CaptureState,
    @SerialName("frames_per_second") val framesPerSecond: Int,
    @SerialName("buffer_fill_pct") val bufferFillPct: Int,
    @SerialName("last_frame_at_ms") val lastFrameAtMs: Long?,
    @SerialName("starved_streak_ms") val starvedStreakMs: Long = 0L,
)
