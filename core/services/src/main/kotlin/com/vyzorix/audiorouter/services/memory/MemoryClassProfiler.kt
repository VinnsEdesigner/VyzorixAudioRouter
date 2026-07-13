package com.vyzorix.audiorouter.services.memory

/** Low-RAM classification derived from ActivityManager values supplied by wiring code. */
public class MemoryClassProfiler {
    public fun classify(memoryClassMb: Int, largeMemoryClassMb: Int, lowRamDevice: Boolean): MemoryProfile {
        val constrained = lowRamDevice || memoryClassMb <= LOW_RAM_MEMORY_CLASS_MB
        return MemoryProfile(
            memoryClassMb = memoryClassMb,
            largeMemoryClassMb = largeMemoryClassMb,
            lowRam = constrained,
            diagnosticQueueLimit = if (constrained) LOW_RAM_LOG_LIMIT else NORMAL_LOG_LIMIT,
        )
    }

    public companion object {
        public const val LOW_RAM_MEMORY_CLASS_MB: Int = 256
        public const val LOW_RAM_LOG_LIMIT: Int = 256
        public const val NORMAL_LOG_LIMIT: Int = 1024
    }
}

public data class MemoryProfile(
    public val memoryClassMb: Int,
    public val largeMemoryClassMb: Int,
    public val lowRam: Boolean,
    public val diagnosticQueueLimit: Int,
)
