package com.vyzorix.audiorouter.services.diagnostics

/** Matches recent app/window events against crash traces to explain flash failures. */
public class EventCorrelationEngine(private val windowMs: Long = DEFAULT_CORRELATION_WINDOW_MS) {
    public fun correlate(events: List<DiagnosticEvent>, traces: List<CrashTrace>): List<CorrelatedCrash> = traces.flatMap { trace ->
        events
            .filter { event -> isLaunchLike(event) && kotlin.math.abs(trace.epochMs - event.epochMs) <= windowMs }
            .map { event ->
                CorrelatedCrash(
                    packageName = event.attribute("package").orEmpty(),
                    crashSignature = trace.signature,
                    eventType = event.type,
                    deltaMs = trace.epochMs - event.epochMs,
                )
            }
    }

    private fun isLaunchLike(event: DiagnosticEvent): Boolean = event.type == "app_foreground" || event.type == "window_flash_crash"

    public companion object {
        public const val DEFAULT_CORRELATION_WINDOW_MS: Long = 500L
    }
}

public data class CorrelatedCrash(
    public val packageName: String,
    public val crashSignature: String,
    public val eventType: String,
    public val deltaMs: Long,
)
