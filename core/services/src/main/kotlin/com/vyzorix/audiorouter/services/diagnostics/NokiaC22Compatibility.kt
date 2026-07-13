package com.vyzorix.audiorouter.services.diagnostics

public data class DiagnosticThresholds(public val logFlushMs: Long, public val maxTimelineEvents: Int, public val enableExpensiveObservers: Boolean)
public class NokiaC22Compatibility { public fun thresholds(lowRam: Boolean): DiagnosticThresholds = if (lowRam) DiagnosticThresholds(10_000L, 256, false) else DiagnosticThresholds(2_000L, 1024, true) }
