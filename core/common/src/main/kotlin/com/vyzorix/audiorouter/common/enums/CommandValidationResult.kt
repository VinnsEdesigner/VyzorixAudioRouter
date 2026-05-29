package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/**
 * Outcome of validating an incoming CommandFrame in CommandHmacValidator (Layer 8).
 * Defined here in Layer 0 so dashboards, telemetry, and rejection-path code can
 * reference the result type without dragging in the validator implementation.
 */
@Serializable
public enum class CommandValidationResult {
    VALID,
    INVALID_SIGNATURE,
    EXPIRED_TIMESTAMP,
    REPLAYED_NONCE,
    MALFORMED_FRAME,
    UNKNOWN_DEVICE,
}
