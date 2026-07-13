package com.vyzorix.audiorouter.services.memory

/** Interprets onTrimMemory levels and emits one deterministic reduction plan. */
public class ServiceTrimCoordinator(
    private val controller: LowRamModeController = LowRamModeController(),
    private val budgets: CacheBudgetManager = CacheBudgetManager(),
    private val reducer: EmergencyMemoryReducer = EmergencyMemoryReducer(),
) {
    public fun onTrimMemory(level: Int, profile: MemoryProfile): TrimDecision {
        val mode = controller.apply(profile, level)
        val budget = budgets.budget(mode, profile)
        val emergencyRun = mode == LowRamMode.Critical
        if (emergencyRun) reducer.reduce(runGc = true)
        return TrimDecision(
            trimLevel = level,
            mode = mode,
            budget = budget,
            emergencyReducerRan = emergencyRun,
        )
    }
}

public data class TrimDecision(
    public val trimLevel: Int,
    public val mode: LowRamMode,
    public val budget: CacheBudget,
    public val emergencyReducerRan: Boolean,
)
