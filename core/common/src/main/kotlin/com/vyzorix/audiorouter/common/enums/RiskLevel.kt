package com.vyzorix.audiorouter.common.enums

import kotlinx.serialization.Serializable

/**
 * Aggregate risk score buckets emitted by DaemonStatusAggregator (Layer C of
 * the three-layer health stack — see ADR-0007 and DOC_4).
 */
@Serializable
public enum class RiskLevel {
    /** All signals nominal. */
    STABLE,

    /** Minor degradation (e.g. starved capture for less than the cool-down window). */
    ELEVATED,

    /** Persistent symptom — RecoveryCoordinator likely to act soon. */
    HIGH,

    /** Imminent failure — safe-mode or restart in progress. */
    CRITICAL,
}
