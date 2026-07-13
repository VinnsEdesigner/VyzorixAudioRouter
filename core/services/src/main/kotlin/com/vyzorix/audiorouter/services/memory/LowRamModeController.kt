package com.vyzorix.audiorouter.services.memory

/** Maintains the current resource-degradation mode selected by trim events. */
public class LowRamModeController {
    @Volatile
    public var mode: LowRamMode = LowRamMode.Normal
        private set

    public fun apply(profile: MemoryProfile, trimLevel: Int): LowRamMode {
        mode = when {
            trimLevel >= TRIM_CRITICAL -> LowRamMode.Critical
            profile.lowRam || trimLevel >= TRIM_MODERATE -> LowRamMode.Conservative
            else -> LowRamMode.Normal
        }
        return mode
    }

    public companion object {
        public const val TRIM_MODERATE: Int = 60
        public const val TRIM_CRITICAL: Int = 80
    }
}

public enum class LowRamMode {
    Normal,
    Conservative,
    Critical,
}
