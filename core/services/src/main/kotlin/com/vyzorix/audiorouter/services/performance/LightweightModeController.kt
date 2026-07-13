package com.vyzorix.audiorouter.services.performance

/** Tracks whether diagnostics should run in minimal mode for route survival. */
public class LightweightModeController {
    @Volatile
    public var enabled: Boolean = false
        private set

    @Volatile
    public var reason: String = "normal"
        private set

    public fun setEnabled(value: Boolean, reason: String = if (value) "resource_pressure" else "normal"): LightweightModeState {
        enabled = value
        this.reason = reason
        return snapshot()
    }

    public fun snapshot(): LightweightModeState = LightweightModeState(enabled, reason)
}

public data class LightweightModeState(
    public val enabled: Boolean,
    public val reason: String,
)
