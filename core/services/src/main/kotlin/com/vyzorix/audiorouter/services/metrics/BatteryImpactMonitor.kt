package com.vyzorix.audiorouter.services.metrics

public class BatteryImpactMonitor {
    public fun estimateDrainPerHour(start: BatterySample, end: BatterySample): Double {
        val hours = (end.epochMs - start.epochMs).coerceAtLeast(1L).toDouble() / 3_600_000.0
        return (start.percent - end.percent).coerceAtLeast(0).toDouble() / hours
    }
}

public data class BatterySample(
    public val percent: Int,
    public val epochMs: Long,
)
