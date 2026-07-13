package com.vyzorix.audiorouter.services.diagnostics

/** Produces the post-crash text trace consumed by ZIP export and telemetry. */
public class RuntimeTraceAssembler(
    private val correlationEngine: EventCorrelationEngine = EventCorrelationEngine(),
) {
    public fun assemble(events: List<DiagnosticEvent>, traces: List<CrashTrace>): RuntimeTrace {
        val orderedEvents = events.sortedBy { it.epochMs }
        val orderedTraces = traces.sortedBy { it.epochMs }
        val correlations = correlationEngine.correlate(orderedEvents, orderedTraces)
        val text = buildString {
            appendLine("# Vyzorix runtime trace")
            orderedEvents.forEach { appendLine("${it.epochMs} event ${it.type} ${it.message} ${it.attributes}") }
            orderedTraces.forEach { appendLine("${it.epochMs} crash ${it.signature} ${it.stackHead.take(STACK_PREVIEW_CHARS)}") }
            correlations.forEach { appendLine("${it.deltaMs}ms correlation ${it.packageName} -> ${it.crashSignature}") }
        }
        return RuntimeTrace(text, correlations)
    }

    public companion object {
        public const val STACK_PREVIEW_CHARS: Int = 240
    }
}

public data class RuntimeTrace(
    public val text: String,
    public val correlations: List<CorrelatedCrash>,
)
