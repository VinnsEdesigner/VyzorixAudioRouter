package com.vyzorix.audiorouter.services.metrics

public class RouteSwitchMetrics {
    private val attempts: MutableList<RouteSwitchSample> = mutableListOf()

    public fun record(from: String, to: String, durationMs: Long, success: Boolean): Unit = synchronized(attempts) {
        attempts += RouteSwitchSample(from, to, durationMs.coerceAtLeast(0L), success)
    }

    public fun successRate(): Double = synchronized(attempts) {
        if (attempts.isEmpty()) 1.0 else attempts.count { it.success }.toDouble() / attempts.size.toDouble()
    }
}

public data class RouteSwitchSample(
    public val from: String,
    public val to: String,
    public val durationMs: Long,
    public val success: Boolean,
)
