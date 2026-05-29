package com.vyzorix.audiorouter.common.model

import com.vyzorix.audiorouter.common.enums.RouteState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Current snapshot of audio routing (mode, attached devices, last enforcement tick). */
@Serializable
public data class AudioRouteState(
    @SerialName("route") val route: RouteState,
    @SerialName("audio_mode") val audioMode: String,
    @SerialName("speaker_on") val speakerOn: Boolean,
    @SerialName("headset_attached") val headsetAttached: Boolean,
    @SerialName("attached_devices") val attachedDevices: List<String> = emptyList(),
    @SerialName("last_enforced_at_ms") val lastEnforcedAtMs: Long,
)
