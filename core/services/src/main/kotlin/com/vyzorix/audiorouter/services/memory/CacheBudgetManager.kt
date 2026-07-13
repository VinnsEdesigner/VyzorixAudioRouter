package com.vyzorix.audiorouter.services.memory

/** Converts low-RAM mode into concrete byte/count budgets for Layer 6. */
public class CacheBudgetManager {
    public fun budget(mode: LowRamMode, profile: MemoryProfile): CacheBudget = when (mode) {
        LowRamMode.Normal -> CacheBudget(
            logEntries = profile.diagnosticQueueLimit,
            nativeBufferBytes = 4 * MIB,
            traceLoggingEnabled = true,
            nonEssentialObserversEnabled = true,
        )
        LowRamMode.Conservative -> CacheBudget(
            logEntries = minOf(profile.diagnosticQueueLimit, 512),
            nativeBufferBytes = 2 * MIB,
            traceLoggingEnabled = false,
            nonEssentialObserversEnabled = true,
        )
        LowRamMode.Critical -> CacheBudget(
            logEntries = minOf(profile.diagnosticQueueLimit, 128),
            nativeBufferBytes = MIB,
            traceLoggingEnabled = false,
            nonEssentialObserversEnabled = false,
        )
    }

    public companion object {
        public const val MIB: Int = 1024 * 1024
    }
}

public data class CacheBudget(
    public val logEntries: Int,
    public val nativeBufferBytes: Int,
    public val traceLoggingEnabled: Boolean,
    public val nonEssentialObserversEnabled: Boolean,
)
