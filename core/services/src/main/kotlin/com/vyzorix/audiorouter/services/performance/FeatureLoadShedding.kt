package com.vyzorix.audiorouter.services.performance

public class FeatureLoadShedding { public fun enabledFeatures(cpuLoad: Double): Set<String> = if (cpuLoad >= 0.85) setOf("routing", "capture") else setOf("routing", "capture", "diagnostics", "dashboard") }
