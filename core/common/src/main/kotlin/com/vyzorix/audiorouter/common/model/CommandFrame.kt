package com.vyzorix.audiorouter.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared data model for incoming C2 command payloads.
 *
 * Used by both WebSocketFrameHandler (Layer 8) and FcmCommandParser (Layer 8) to
 * pass a uniform structure to CommandHmacValidator. The wire format and the
 * canonical message that backs the [hmac] field are defined in
 * doc/COMMAND_SECURITY.md §2 and §3:
 *
 *   canonical = "{transactionId}|{deviceId}|{action}|{timestampMs}|{nonce}|{params}"
 *   hmac      = hex(HMAC-SHA256(canonical, command_secret))
 *
 * Field names are stable on the wire (camelCase as written).
 */
@Serializable
public data class CommandFrame(
    @SerialName("transactionId") val transactionId: String,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("action") val action: String,
    @SerialName("timestampMs") val timestampMs: Long,
    @SerialName("nonce") val nonce: String,
    /** Raw JSON string. Empty params = "{}" per COMMAND_SECURITY.md §3. */
    @SerialName("params") val params: String,
    @SerialName("hmac") val hmac: String,
)
