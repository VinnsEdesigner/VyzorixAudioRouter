package com.vyzorix.audiorouter.services.metrics

public class AudioLatencyMetrics(private val maxSamples: Int = 128) {
    private val samples: MutableList<Long> = mutableListOf()

    public fun record(latencyMs: Long): Unit = synchronized(samples) {
        samples += latencyMs.coerceAtLeast(0L)
        while (samples.size > maxSamples) samples.removeAt(0)
    }

    public fun snapshot(): LatencySnapshot = synchronized(samples) {
        if (samples.isEmpty()) LatencySnapshot(0L, 0L, 0) else LatencySnapshot(
            averageMs = samples.sum() / samples.size,
            maxMs = samples.maxOrNull() ?: 0L,
            sampleCount = samples.size,
        )
    }
}

public data class LatencySnapshot(
    public val averageMs: Long,
    public val maxMs: Long,
    public val sampleCount: Int,
)
