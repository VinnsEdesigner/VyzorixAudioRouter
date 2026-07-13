package com.vyzorix.audiorouter.services.memory

/** Last-resort memory reducer; native reclaim is injected by audioengine wiring. */
public class EmergencyMemoryReducer(private val nativeReclaimer: () -> Unit = {}) {
    public fun reduce(runGc: Boolean = true): MemoryReductionResult {
        nativeReclaimer()
        if (runGc) System.gc()
        return MemoryReductionResult(nativeReclaimerCalled = true, gcRequested = runGc)
    }
}

public data class MemoryReductionResult(
    public val nativeReclaimerCalled: Boolean,
    public val gcRequested: Boolean,
)
