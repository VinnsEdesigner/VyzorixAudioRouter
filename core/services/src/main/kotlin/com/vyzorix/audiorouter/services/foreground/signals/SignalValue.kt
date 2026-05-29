// SignalValue — common typed value emitted by every Layer-B signal in
// the ADR-0007 three-layer health stack.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 624:
//     core/services/foreground/signals/SignalValue.kt
//       "Common sealed type for a signal's current value + timestamp".
//
// Why a single value type (not per-signal sealed classes):
//   - DaemonStatusAggregator collects N signals on every 10s tick and
//     needs to compose them into a single `DaemonStatus` snapshot. If
//     each signal returned its own bespoke type the aggregator would
//     fan out into a switch tree.
//   - The dashboard renders signals into RemoteViews row-by-row; one
//     uniform type maps cleanly into `setText` calls.
//   - RecoveryCoordinator's risk model is a function of severity bands
//     (UNKNOWN/OK/WARN/CRIT). The bands are first-class enum members.
//
// Severity bands:
//   UNKNOWN: the owning layer isn't built (Layer 5 ships Projection /
//            WebSocket signals before Layers 4-trampoline / 8 land) OR
//            the system service threw on the read.
//   OK:      signal is in the green zone.
//   WARN:    signal is degraded but recoverable.
//   CRIT:    signal indicates a recoverable-only-by-restart failure.
//
// The class is intentionally a `data class` rather than a sealed
// hierarchy — the four bands are an enum on the same struct, which
// keeps `equals` / `copy` / serialisation trivially derived.

package com.vyzorix.audiorouter.services.foreground.signals

/** Severity bands for [SignalValue.severity]. */
public enum class SignalSeverity {
    UNKNOWN,
    OK,
    WARN,
    CRIT,
}

/**
 * One signal's current observation.
 *
 * @property severity coarse band; drives the dashboard colour + the
 *   RecoveryCoordinator's risk model.
 * @property label short human-readable summary (shown on the
 *   dashboard's diagnostic row).
 * @property details optional longer description for the
 *   diagnostics-tier scroll view.
 * @property readEpochMs wall-clock time of the read, used by the
 *   aggregator to detect stale signals.
 */
public data class SignalValue(
    public val severity: SignalSeverity,
    public val label: String,
    public val details: String,
    public val readEpochMs: Long,
) {

    /** True iff the signal is in OK or UNKNOWN band. */
    public fun isHealthy(): Boolean = severity == SignalSeverity.OK || severity == SignalSeverity.UNKNOWN

    /** True iff the signal is in WARN or CRIT band. */
    public fun isDegraded(): Boolean = severity == SignalSeverity.WARN || severity == SignalSeverity.CRIT

    public companion object {

        public fun unknown(
            label: String = "unknown",
            details: String = "",
            readEpochMs: Long = System.currentTimeMillis(),
        ): SignalValue = SignalValue(
            severity = SignalSeverity.UNKNOWN,
            label = label,
            details = details,
            readEpochMs = readEpochMs,
        )

        public fun ok(
            label: String,
            details: String = "",
            readEpochMs: Long = System.currentTimeMillis(),
        ): SignalValue = SignalValue(
            severity = SignalSeverity.OK,
            label = label,
            details = details,
            readEpochMs = readEpochMs,
        )

        public fun warn(
            label: String,
            details: String = "",
            readEpochMs: Long = System.currentTimeMillis(),
        ): SignalValue = SignalValue(
            severity = SignalSeverity.WARN,
            label = label,
            details = details,
            readEpochMs = readEpochMs,
        )

        public fun crit(
            label: String,
            details: String = "",
            readEpochMs: Long = System.currentTimeMillis(),
        ): SignalValue = SignalValue(
            severity = SignalSeverity.CRIT,
            label = label,
            details = details,
            readEpochMs = readEpochMs,
        )
    }
}

/**
 * Common interface implemented by every signal source. The aggregator
 * collects each via [current] on every 10s tick.
 *
 * Implementations must be safe to call from any thread and must NOT
 * throw — they should swallow exceptions internally and return
 * `SignalValue.unknown(label = "...")` on failure.
 */
public interface SignalSource {

    /** Stable identifier shown on the dashboard (e.g. `"thermal"`). */
    public val id: String

    /** Read the current value. Must be non-blocking. */
    public fun current(): SignalValue
}
