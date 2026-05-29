package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Outcome of executing a [CommandFrame]. Sent back to the server over WSS by
 * RemoteCommandResultDispatcher (Layer 8) once the device-side action returns.
 */
@Serializable
public data class CommandResult(
    @SerialName("transactionId") val transactionId: String,
    @SerialName("success") val success: Boolean,
    @SerialName("message") val message: String? = null,
    @SerialName("executed_at_ms") val executedAtMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
)
