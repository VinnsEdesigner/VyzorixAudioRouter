package com.vyzorix.audiorouter.services.metrics

public class CapturePerformanceTracker {
    private val samples: MutableList<CapturePerformanceSample> = mutableListOf()

    public fun record(framesRead: Long, framesDropped: Long, jitterMs: Long): Unit = synchronized(samples) {
        samples += CapturePerformanceSample(framesRead, framesDropped, jitterMs)
    }

    public fun dropRate(): Double = synchronized(samples) {
        val read = samples.sumOf { it.framesRead }
        val dropped = samples.sumOf { it.framesDropped }
        if (read + dropped == 0L) 0.0 else dropped.toDouble() / (read + dropped).toDouble()
    }
}

public data class CapturePerformanceSample(
    public val framesRead: Long,
    public val framesDropped: Long,
    public val jitterMs: Long,
)
