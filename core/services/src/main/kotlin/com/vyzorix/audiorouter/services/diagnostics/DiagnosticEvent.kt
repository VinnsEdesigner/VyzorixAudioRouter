package com.vyzorix.audiorouter.services.diagnostics

/**
 * Type-safe event passed through the Layer 6 black-box pipeline.
 *
 * The event is intentionally JVM-only and string-serialisable so it can be
 * written during crash handling without touching Android framework APIs that
 * may already be unstable after a system_server failure.
 */
public data class DiagnosticEvent(
    public val type: String,
    public val message: String,
    public val epochMs: Long = System.currentTimeMillis(),
    public val attributes: Map<String, String> = emptyMap(),
) {
    public fun attribute(key: String): String? = attributes[key]
}
