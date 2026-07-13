package com.vyzorix.audiorouter.services.crash

import com.vyzorix.audiorouter.services.diagnostics.DiagnosticEvent

/** Forensic-only soft reboot detector based on uptime moving backwards. */
public class SoftRebootTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    public fun observe(previousUptimeMs: Long?, currentUptimeMs: Long): SoftRebootObservation {
        val rebooted = previousUptimeMs != null && currentUptimeMs + UPTIME_BACKWARDS_TOLERANCE_MS < previousUptimeMs
        return SoftRebootObservation(
            rebooted = rebooted,
            previousUptimeMs = previousUptimeMs,
            currentUptimeMs = currentUptimeMs,
            epochMs = clock(),
        )
    }

    public companion object {
        public const val UPTIME_BACKWARDS_TOLERANCE_MS: Long = 1_000L
    }
}

public data class SoftRebootObservation(
    public val rebooted: Boolean,
    public val previousUptimeMs: Long?,
    public val currentUptimeMs: Long,
    public val epochMs: Long,
) {
    public fun toEvent(): DiagnosticEvent = DiagnosticEvent(
        type = if (rebooted) "soft_reboot" else "uptime_sample",
        message = if (rebooted) "system uptime moved backwards" else "system uptime sampled",
        epochMs = epochMs,
        attributes = mapOf(
            "previousUptimeMs" to previousUptimeMs.toString(),
            "currentUptimeMs" to currentUptimeMs.toString(),
        ),
    )
}
