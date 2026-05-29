package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sample from the thermal monitor — see DOC_6 for the polling cadence and zone selection.
 * Layer 0 just defines the shape; reading the thermal_zone sysfs nodes happens in Layer 6.
 */
@Serializable
public data class ThermalState(
    @SerialName("zone") val zone: String,
    @SerialName("temperature_c") val temperatureC: Float,
    @SerialName("throttling") val throttling: Boolean,
    @SerialName("sampled_at_ms") val sampledAtMs: Long,
)
