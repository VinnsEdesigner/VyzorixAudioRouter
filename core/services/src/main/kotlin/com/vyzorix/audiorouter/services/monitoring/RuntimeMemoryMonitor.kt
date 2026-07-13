package com.vyzorix.audiorouter.services.monitoring

public data class RuntimeMemorySample(public val availableMb: Int, public val totalMb: Int)
public class RuntimeMemoryMonitor(private val criticalPercent: Int = 10) { public fun critical(sample: RuntimeMemorySample): Boolean = sample.totalMb > 0 && sample.availableMb * 100 / sample.totalMb <= criticalPercent }
