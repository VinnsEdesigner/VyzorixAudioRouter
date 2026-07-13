package com.vyzorix.audiorouter.services.performance

public class AdaptiveSamplingController(private val normalMs: Long = 500L, private val loadedMs: Long = 2_000L) { public fun interval(cpuLoad: Double): Long = if (cpuLoad >= 0.80) loadedMs else normalMs }
