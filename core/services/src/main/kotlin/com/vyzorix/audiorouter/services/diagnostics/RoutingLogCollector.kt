package com.vyzorix.audiorouter.services.diagnostics

/** Records route transitions in the same timeline as crashes and app launches. */
public class RoutingLogCollector(private val timeline: RuntimeEventTimeline = RuntimeEventTimeline()) {
    public fun routeChanged(from: String, to: String, reason: String, success: Boolean = true): Unit {
        timeline.add(
            type = "route_change",
            message = "$from->$to",
            attrs = mapOf(
                "from" to from,
                "to" to to,
                "reason" to reason,
                "success" to success.toString(),
            ),
        )
    }

    public fun recent(limit: Int = Int.MAX_VALUE): List<DiagnosticEvent> = timeline.recent(limit)
}
