package com.vyzorix.audiorouter.services.memory

public data class NativeHeapSample(public val liveBytes: Long, public val peakBytes: Long, public val epochMs: Long = System.currentTimeMillis())
public class NativeHeapWatcher(private val warnBytes: Long = 16L * 1024L * 1024L) { public fun isLeaking(sample: NativeHeapSample): Boolean = sample.liveBytes > warnBytes }
