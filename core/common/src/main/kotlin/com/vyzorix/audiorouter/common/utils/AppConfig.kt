// AppConfig — runtime configuration façade for the daemon.
//
// Stores feature flags and tunable thresholds in a single, snapshot-immutable
// structure so Layer 3+ services can read without worrying about concurrent
// mutation. Updates are applied by replacing the whole snapshot atomically.
//
// `AppConfig` is intentionally distinct from `constants.AppConstants`:
//
//   * `AppConstants`  — compile-time, never changes after build.
//   * `AppConfig`     — runtime mutable, fed by `RuntimeFlagsStore` (Layer 1)
//                       and surfaced to consumers in Layer 3+.

package com.vyzorix.audiorouter.common.utils

import java.util.concurrent.atomic.AtomicReference

/** Immutable snapshot of all runtime-tunable knobs. */
public data class AppConfigSnapshot(
    /** Maximum re-tries when AudioRecord starvation is observed. */
    public val audioRestartBudget: Int = DEFAULT_AUDIO_RESTART_BUDGET,
    /** Periodicity (ms) of the SpeakerForceEngine route assertion. */
    public val routeAssertIntervalMs: Long = DEFAULT_ROUTE_ASSERT_INTERVAL_MS,
    /** When set, telemetry events are buffered to disk before upload. */
    public val telemetryBufferingEnabled: Boolean = true,
    /** When set, the daemon will accept update checks over a metered network. */
    public val allowMeteredUpdates: Boolean = false,
    /**
     * Free-form feature flags from the server / `RuntimeFlagsStore`. Layer 6+
     * dashboards write here; Layer 3+ services read.
     */
    public val featureFlags: Map<String, Boolean> = emptyMap(),
) {
    /**
     * Look up an arbitrary flag with a fallback when the key is absent. Use
     * this in preference to mutating the snapshot via copy() in hot code.
     */
    public fun flag(name: String, default: Boolean = false): Boolean =
        featureFlags.getOrDefault(name, default)

    public companion object {
        public const val DEFAULT_AUDIO_RESTART_BUDGET: Int = 5
        public const val DEFAULT_ROUTE_ASSERT_INTERVAL_MS: Long = 500L
    }
}

/**
 * Process-wide singleton holding the current [AppConfigSnapshot]. Reads are
 * lock-free (`AtomicReference.get`). Updates are `compareAndSet`-based so
 * the dashboard cannot lose writes if it races with the daemon.
 */
public object AppConfig {

    private val snapshot: AtomicReference<AppConfigSnapshot> =
        AtomicReference(AppConfigSnapshot())

    public fun current(): AppConfigSnapshot = snapshot.get()

    /** Replace the entire snapshot atomically. Returns the previous value. */
    public fun set(next: AppConfigSnapshot): AppConfigSnapshot = snapshot.getAndSet(next)

    /** Apply a transformation atomically; safe under concurrent updates. */
    public fun update(transform: (AppConfigSnapshot) -> AppConfigSnapshot): AppConfigSnapshot {
        while (true) {
            val previous = snapshot.get()
            val next = transform(previous)
            if (snapshot.compareAndSet(previous, next)) {
                return next
            }
        }
    }

    /** Convenience for tests; prefer [set] in production code. */
    internal fun resetForTests() {
        snapshot.set(AppConfigSnapshot())
    }
}
