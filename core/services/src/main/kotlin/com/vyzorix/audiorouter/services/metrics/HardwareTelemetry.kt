package com.vyzorix.audiorouter.services.metrics

public data class HardwareTelemetry(
    public val cpuLoad: Double,
    public val memoryAvailableMb: Int,
    public val thermalCelsius: Float,
    public val epochMs: Long = System.currentTimeMillis(),
)

public class HardwareTelemetryRecorder {
    private val samples: MutableList<HardwareTelemetry> = mutableListOf()

    public fun record(sample: HardwareTelemetry): Unit = synchronized(samples) { samples += sample }
    public fun recent(limit: Int = 64): List<HardwareTelemetry> = synchronized(samples) { samples.takeLast(limit) }
}
