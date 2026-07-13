package com.vyzorix.audiorouter.services.metrics

import com.vyzorix.audiorouter.common.enums.CrashType

public class CrashMetrics {
    private val crashes: MutableList<CrashMetricSample> = mutableListOf()

    public fun record(type: CrashType, epochMs: Long = System.currentTimeMillis()): Unit = synchronized(crashes) {
        crashes += CrashMetricSample(type, epochMs)
    }

    public fun countSince(epochMs: Long): Int = synchronized(crashes) { crashes.count { it.epochMs >= epochMs } }
}

public data class CrashMetricSample(
    public val type: CrashType,
    public val epochMs: Long,
)
