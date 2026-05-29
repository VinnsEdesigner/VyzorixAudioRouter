// ProjectionRetryPolicy — throttles projection-grant retries so the
// daemon never tight-loops the trampoline.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 649:
//     core/services/projection/ProjectionRetryPolicy.kt
//       "Throttles requests; prevents layout loops under stress".
//
// Risk model: the trampoline activity is launched via a system Intent
// flagged FLAG_ACTIVITY_NEW_TASK + FLAG_ACTIVITY_CLEAR_TASK. If the user
// repeatedly denies the consent dialog (or the OS denies for us — e.g.
// missing notification permission on A13+) we could end up flashing the
// dialog at the user 10x/second. The policy enforces:
//
//   - Cooldown between consecutive launches (default 2s).
//   - Maximum consecutive launches inside the rolling window
//     (default 5 launches in 60s).
//   - Backoff multiplier after each rejection (default 2x per denial up
//     to [maxBackoffMs]).
//
// State machine is purely atomic. Tests inject a fake clock.

package com.vyzorix.audiorouter.services.projection

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [ProjectionRetryPolicy.tryAcquireLaunchSlot]. */
public sealed interface RetryDecision {
    public object Allow : RetryDecision
    public data class Throttle(
        public val waitMillis: Long,
        public val reason: String,
    ) : RetryDecision

    public data class Banned(public val reason: String) : RetryDecision
}

/** Diagnostic snapshot for the dashboard. */
public data class ProjectionRetryPolicySnapshot(
    public val attempts: Long,
    public val allowed: Long,
    public val throttled: Long,
    public val banned: Long,
    public val consecutiveDenials: Int,
    public val currentBackoffMs: Long,
    public val lastDecisionEpochMs: Long,
    public val lastDecisionLabel: String,
)

/**
 * Stateful retry policy. Single-instance per coordinator.
 *
 * Thread-safety: all bookkeeping is atomic; the only contended writes
 * are the consecutive-denials counter and the backoff value, which use
 * `getAndSet`.
 */
public class ProjectionRetryPolicy(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxLaunchesPerWindow: Int = DEFAULT_MAX_LAUNCHES_PER_WINDOW,
    private val rollingWindowMs: Long = DEFAULT_ROLLING_WINDOW_MS,
    private val initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS,
    private val backoffMultiplier: Long = DEFAULT_BACKOFF_MULTIPLIER,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val banAfterConsecutiveDenials: Int = DEFAULT_BAN_AFTER_CONSECUTIVE_DENIALS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val attempts: AtomicLong = AtomicLong(0L)
    private val allowed: AtomicLong = AtomicLong(0L)
    private val throttled: AtomicLong = AtomicLong(0L)
    private val banned: AtomicLong = AtomicLong(0L)
    private val lastLaunchEpochMs: AtomicLong = AtomicLong(0L)
    private val launchesInWindow: AtomicInteger = AtomicInteger(0)
    private val windowStartEpochMs: AtomicLong = AtomicLong(0L)
    private val consecutiveDenials: AtomicInteger = AtomicInteger(0)
    private val currentBackoffMs: AtomicLong = AtomicLong(initialBackoffMs)
    private val lastDecisionLabel: AtomicReference<String> = AtomicReference("init")
    private val lastDecisionEpochMs: AtomicLong = AtomicLong(0L)

    /**
     * Ask the policy whether the caller may launch the trampoline now.
     * Updates internal bookkeeping as a side effect.
     */
    public fun tryAcquireLaunchSlot(): RetryDecision {
        attempts.incrementAndGet()
        val now = clock()
        lastDecisionEpochMs.set(now)

        // Hard ban after too many consecutive denials.
        if (consecutiveDenials.get() >= banAfterConsecutiveDenials) {
            banned.incrementAndGet()
            lastDecisionLabel.set("banned")
            DaemonLogger.get().warn(
                TAG,
                "retry.banned consecutiveDenials=${consecutiveDenials.get()} threshold=$banAfterConsecutiveDenials",
            )
            return RetryDecision.Banned("too_many_consecutive_denials")
        }

        // Window roll-over.
        val windowStart = windowStartEpochMs.get()
        val windowAge = if (windowStart == 0L) Long.MAX_VALUE else now - windowStart
        if (windowAge > rollingWindowMs) {
            windowStartEpochMs.set(now)
            launchesInWindow.set(0)
        }

        // Rolling-window cap.
        if (launchesInWindow.get() >= maxLaunchesPerWindow) {
            throttled.incrementAndGet()
            val resetIn = rollingWindowMs - (now - windowStartEpochMs.get()).coerceAtLeast(0L)
            lastDecisionLabel.set("throttled_window")
            DaemonLogger.get().info(
                TAG,
                "retry.throttled.window launches=${launchesInWindow.get()} max=$maxLaunchesPerWindow waitMs=$resetIn",
            )
            return RetryDecision.Throttle(waitMillis = resetIn.coerceAtLeast(0L), reason = "rolling_window_full")
        }

        // Cooldown since the previous launch.
        val sinceLast = now - lastLaunchEpochMs.get()
        val effectiveCooldown = maxOf(cooldownMs, currentBackoffMs.get())
        if (lastLaunchEpochMs.get() != 0L && sinceLast < effectiveCooldown) {
            throttled.incrementAndGet()
            val wait = effectiveCooldown - sinceLast
            lastDecisionLabel.set("throttled_cooldown")
            DaemonLogger.get().info(
                TAG,
                "retry.throttled.cooldown sinceMs=$sinceLast cooldownMs=$effectiveCooldown waitMs=$wait",
            )
            return RetryDecision.Throttle(waitMillis = wait, reason = "cooldown_active")
        }

        // Approved.
        allowed.incrementAndGet()
        lastLaunchEpochMs.set(now)
        launchesInWindow.incrementAndGet()
        lastDecisionLabel.set("allow")
        DaemonLogger.get().info(
            TAG,
            "retry.allowed launchesInWindow=${launchesInWindow.get()} backoffMs=${currentBackoffMs.get()}",
        )
        return RetryDecision.Allow
    }

    /** Caller reports a successful grant — resets the backoff/denials. */
    public fun recordGrant() {
        consecutiveDenials.set(0)
        currentBackoffMs.set(initialBackoffMs)
        DaemonLogger.get().info(TAG, "retry.record_grant")
    }

    /** Caller reports a rejection — bumps the backoff. */
    public fun recordDenial(reason: String) {
        val newDenials = consecutiveDenials.incrementAndGet()
        val previous = currentBackoffMs.get()
        val next = (previous * backoffMultiplier).coerceAtMost(maxBackoffMs)
        currentBackoffMs.set(next)
        DaemonLogger.get().info(
            TAG,
            "retry.record_denial reason=$reason consecutive=$newDenials backoffMs=$next",
        )
    }

    /** Reset all counters. */
    public fun reset() {
        attempts.set(0L)
        allowed.set(0L)
        throttled.set(0L)
        banned.set(0L)
        lastLaunchEpochMs.set(0L)
        launchesInWindow.set(0)
        windowStartEpochMs.set(0L)
        consecutiveDenials.set(0)
        currentBackoffMs.set(initialBackoffMs)
        lastDecisionLabel.set("reset")
        lastDecisionEpochMs.set(0L)
    }

    /** Diagnostic snapshot for the dashboard. */
    public fun snapshot(): ProjectionRetryPolicySnapshot =
        ProjectionRetryPolicySnapshot(
            attempts = attempts.get(),
            allowed = allowed.get(),
            throttled = throttled.get(),
            banned = banned.get(),
            consecutiveDenials = consecutiveDenials.get(),
            currentBackoffMs = currentBackoffMs.get(),
            lastDecisionEpochMs = lastDecisionEpochMs.get(),
            lastDecisionLabel = lastDecisionLabel.get(),
        )

    public companion object {
        public const val DEFAULT_COOLDOWN_MS: Long = 2_000L
        public const val DEFAULT_MAX_LAUNCHES_PER_WINDOW: Int = 5
        public const val DEFAULT_ROLLING_WINDOW_MS: Long = 60_000L
        public const val DEFAULT_INITIAL_BACKOFF_MS: Long = 500L
        public const val DEFAULT_BACKOFF_MULTIPLIER: Long = 2L
        public const val DEFAULT_MAX_BACKOFF_MS: Long = 30_000L
        public const val DEFAULT_BAN_AFTER_CONSECUTIVE_DENIALS: Int = 10
        private const val TAG: String = "ProjectionRetryPolicy"
    }
}
