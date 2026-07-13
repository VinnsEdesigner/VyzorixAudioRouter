package com.vyzorix.audiorouter.services.diagnostics

/** Chronological runtime timeline used to correlate route, launch, and crash events. */
public class RuntimeEventTimeline(private val collector: LogStreamCollector = LogStreamCollector()) {
    public fun add(type: String, message: String, attrs: Map<String, String> = emptyMap()): Unit {
        collector.record(type, message, attrs)
    }

    public fun add(event: DiagnosticEvent): Unit {
        collector.record(event)
    }

    public fun recent(limit: Int = Int.MAX_VALUE): List<DiagnosticEvent> = collector
        .snapshot()
        .events
        .sortedBy { it.epochMs }
        .takeLast(limit.coerceAtLeast(0))
}
