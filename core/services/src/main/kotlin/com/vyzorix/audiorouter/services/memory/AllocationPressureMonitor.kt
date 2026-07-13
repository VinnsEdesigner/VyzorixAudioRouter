package com.vyzorix.audiorouter.services.memory

public class AllocationPressureMonitor(private val warnDeltaBytes: Long = 4L * 1024L * 1024L) { public fun isSpike(previousBytes: Long, currentBytes: Long): Boolean = currentBytes - previousBytes >= warnDeltaBytes }
