// RecoveryCoordinator — Layer A of the ADR-0007 three-layer health
// stack. The ONE class allowed to issue restart / safe-mode / fallback
// decisions.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md lines 612–615:
//     core/services/foreground/RecoveryCoordinator.kt
//       "Layer A (ADR-0007): the ONE class that issues restart /
//        safe-mode / fallback decisions. Subscribes to DaemonStatus,
//        absorbs the policy logic from SoftRebootPredictor +
//        CrashLoopProtector. Executes StartupBackoffScheduler. No other
//        class restarts services directly."
//
// Responsibilities:
//   1. Subscribe to DaemonStatusAggregator.statusFlow.
//   2. Compute a risk score (0–100) on each tick. Score derives from
//      (a) the aggregated [RiskLevel], (b) crash-loop history,
//      (c) soft-reboot tracker history.
//   3. Decide and execute a `RecoveryDecision`:
//        - NoAction
//        - RestartPipeline (issued via the callback)
//        - EnterSafeMode (engaged via SafeModeProbe — the daemon's
//          internal toggle)
//        - ExitSafeMode
//        - StopForGood (after CRASH_LOOP_LIMIT)
//   4. Honour cooldowns: no two restarts within COOLDOWN_MS; exponential
//      backoff up to MAX_BACKOFF_MS after each failure.
//   5. Provide the SafeModeProbe surface used by SafeModeSignal.
//
// IMPORTANT: this class does NOT call `Service.stopSelf` or
// `Activity.recreate` directly. It surfaces decisions to the
// [RecoveryCallback] supplied at construction; the service wires the
// callback into its own lifecycle.

package com.vyzorix.audiorouter.services.foreground

import com.vyzorix.audiorouter.common.enums.RiskLevel
import com.vyzorix.audiorouter.services.foreground.signals.SafeModeProbe
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Callback surface the service exposes to the coordinator. */
public interface RecoveryCallback {
    /** Restart the audio pipeline (release + reacquire route + capture). */
    public fun restartPipeline(reason: String)

    /** Stop the service for good — used after CRASH_LOOP_LIMIT. */
    public fun stopForGood(reason: String)

    /** Notify the service that safe-mode just engaged/disengaged. */
    public fun onSafeModeChanged(active: Boolean, reason: String)
}

/** Recovery decision emitted on each tick. */
public sealed interface RecoveryDecision {
    public object NoAction : RecoveryDecision
    public data class RestartPipeline(public val reason: String) : RecoveryDecision
    public data class EnterSafeMode(public val reason: String) : RecoveryDecision
    public data class ExitSafeMode(public val reason: String) : RecoveryDecision
    public data class StopForGood(public val reason: String) : RecoveryDecision
}

/** Diagnostic snapshot. */
public data class RecoveryCoordinatorSnapshot(
    public val ticksObserved: Long,
    public val restartCount: Long,
    public val safeModeEngagements: Long,
    public val lastDecisionLabel: String,
    public val lastDecisionEpochMs: Long,
    public val currentBackoffMs: Long,
    public val safeModeActive: Boolean,
    public val safeModeReason: String,
    public val lastRestartEpochMs: Long,
)

/**
 * Coordinator. Single-instance per service. Acts as the
 * [SafeModeProbe] used by SafeModeSignal.
 */
public class RecoveryCoordinator(
    private val scope: CoroutineScope,
    private val aggregator: DaemonStatusAggregator,
    private val callback: RecoveryCallback,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val crashLoopLimit: Int = DEFAULT_CRASH_LOOP_LIMIT,
    private val crashLoopWindowMs: Long = DEFAULT_CRASH_LOOP_WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SafeModeProbe {

    private val ticksObserved: AtomicLong = AtomicLong(0L)
    private val restartCount: AtomicLong = AtomicLong(0L)
    private val safeModeEngagements: AtomicLong = AtomicLong(0L)
    private val lastDecisionLabel: AtomicReference<String> = AtomicReference("init")
    private val lastDecisionEpochMs: AtomicLong = AtomicLong(0L)
    private val currentBackoffMs: AtomicLong = AtomicLong(0L)
    private val safeModeActive: AtomicBoolean = AtomicBoolean(false)
    private val safeModeReason: AtomicReference<String> = AtomicReference("init")
    private val lastRestartEpochMs: AtomicLong = AtomicLong(0L)
    private val consecutiveCritTicks: AtomicInteger = AtomicInteger(0)
    private val restartHistory: ArrayDeque<Long> = ArrayDeque()
    private val historyLock: Any = Any()
    private val running: AtomicBoolean = AtomicBoolean(false)
    private var job: Job? = null

    /** Start subscribing. Idempotent. */
    public fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.Default) {
            DaemonLogger.get().info(TAG, "coordinator.start")
            aggregator.statusFlow.collectLatest { tick ->
                ticksObserved.incrementAndGet()
                val decision = decide(tick.status.riskLevel)
                lastDecisionLabel.set(decision::class.simpleName ?: "unknown")
                lastDecisionEpochMs.set(clock())
                execute(decision)
            }
        }
    }

    /** Stop subscribing. */
    public fun stop() {
        if (!running.compareAndSet(true, false)) return
        job?.cancel()
        job = null
    }

    /** Manual restart request (from the dashboard's RestartPipelineAction). */
    public fun requestRestart(reason: String) {
        val now = clock()
        if (now - lastRestartEpochMs.get() < cooldownMs + currentBackoffMs.get()) {
            DaemonLogger.get().warn(
                TAG,
                "coordinator.manual_restart.cooldown reason=$reason backoffMs=${currentBackoffMs.get()}",
            )
            return
        }
        executeRestart(reason)
    }

    /** Manual safe-mode engagement. */
    public fun engageSafeMode(reason: String) {
        if (safeModeActive.compareAndSet(false, true)) {
            safeModeReason.set(reason)
            safeModeEngagements.incrementAndGet()
            DaemonLogger.get().warn(TAG, "coordinator.safe_mode.engaged reason=$reason")
            callback.onSafeModeChanged(active = true, reason = reason)
        }
    }

    /** Manual safe-mode disengagement. */
    public fun disengageSafeMode(reason: String) {
        if (safeModeActive.compareAndSet(true, false)) {
            safeModeReason.set(reason)
            DaemonLogger.get().info(TAG, "coordinator.safe_mode.disengaged reason=$reason")
            callback.onSafeModeChanged(active = false, reason = reason)
        }
    }

    /** Decide what to do given the latest risk band. */
    public fun decide(riskLevel: RiskLevel): RecoveryDecision {
        return when (riskLevel) {
            RiskLevel.STABLE -> {
                consecutiveCritTicks.set(0)
                if (safeModeActive.get()) {
                    RecoveryDecision.ExitSafeMode("risk_stable")
                } else {
                    RecoveryDecision.NoAction
                }
            }
            RiskLevel.ELEVATED -> {
                consecutiveCritTicks.set(0)
                RecoveryDecision.NoAction
            }
            RiskLevel.HIGH -> {
                consecutiveCritTicks.set(0)
                RecoveryDecision.NoAction
            }
            RiskLevel.CRITICAL -> {
                val streak = consecutiveCritTicks.incrementAndGet()
                if (streak >= CRIT_STREAK_BEFORE_RESTART) {
                    val recentRestarts = synchronized(historyLock) {
                        purgeOldRestarts()
                        restartHistory.size
                    }
                    if (recentRestarts >= crashLoopLimit) {
                        RecoveryDecision.StopForGood(
                            "crash_loop_limit_${crashLoopLimit}_in_${crashLoopWindowMs}ms",
                        )
                    } else if (safeModeActive.get()) {
                        RecoveryDecision.NoAction
                    } else {
                        RecoveryDecision.RestartPipeline("crit_streak_$streak")
                    }
                } else {
                    RecoveryDecision.NoAction
                }
            }
        }
    }

    private fun execute(decision: RecoveryDecision) {
        when (decision) {
            is RecoveryDecision.NoAction -> Unit
            is RecoveryDecision.RestartPipeline -> {
                val now = clock()
                val sinceLast = now - lastRestartEpochMs.get()
                val backoff = currentBackoffMs.get()
                if (sinceLast < cooldownMs + backoff) {
                    DaemonLogger.get().info(
                        TAG,
                        "coordinator.skip.cooldown sinceLast=$sinceLast backoff=$backoff",
                    )
                    return
                }
                executeRestart(decision.reason)
            }
            is RecoveryDecision.EnterSafeMode -> engageSafeMode(decision.reason)
            is RecoveryDecision.ExitSafeMode -> disengageSafeMode(decision.reason)
            is RecoveryDecision.StopForGood -> {
                DaemonLogger.get().error(TAG, "coordinator.stop_for_good reason=${decision.reason}")
                callback.stopForGood(decision.reason)
            }
        }
    }

    private fun executeRestart(reason: String) {
        val now = clock()
        lastRestartEpochMs.set(now)
        restartCount.incrementAndGet()
        synchronized(historyLock) {
            restartHistory.addLast(now)
            purgeOldRestarts()
        }
        val priorBackoff = currentBackoffMs.get()
        val nextBackoff = if (priorBackoff == 0L) {
            INITIAL_BACKOFF_MS
        } else {
            (priorBackoff * BACKOFF_MULTIPLIER).coerceAtMost(maxBackoffMs)
        }
        currentBackoffMs.set(nextBackoff)
        DaemonLogger.get().warn(
            TAG,
            "coordinator.restart reason=$reason backoffMs=$nextBackoff total=${restartCount.get()}",
        )
        try {
            callback.restartPipeline(reason)
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "coordinator.restart.callback_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
        }
    }

    private fun purgeOldRestarts() {
        val cutoff = clock() - crashLoopWindowMs
        while (restartHistory.isNotEmpty() && restartHistory.first() < cutoff) {
            restartHistory.removeFirst()
        }
    }

    /** Reset backoff after a successful uptime window (called externally). */
    public fun noteHealthyUptime() {
        currentBackoffMs.set(0L)
    }

    public override fun isActive(): Boolean = safeModeActive.get()
    public override fun lastEngagedReason(): String = safeModeReason.get()

    /** Diagnostic snapshot. */
    public fun snapshot(): RecoveryCoordinatorSnapshot = RecoveryCoordinatorSnapshot(
        ticksObserved = ticksObserved.get(),
        restartCount = restartCount.get(),
        safeModeEngagements = safeModeEngagements.get(),
        lastDecisionLabel = lastDecisionLabel.get(),
        lastDecisionEpochMs = lastDecisionEpochMs.get(),
        currentBackoffMs = currentBackoffMs.get(),
        safeModeActive = safeModeActive.get(),
        safeModeReason = safeModeReason.get(),
        lastRestartEpochMs = lastRestartEpochMs.get(),
    )

    public companion object {
        public const val DEFAULT_COOLDOWN_MS: Long = 30_000L
        public const val DEFAULT_MAX_BACKOFF_MS: Long = 5L * 60L * 1_000L
        public const val DEFAULT_CRASH_LOOP_LIMIT: Int = 5
        public const val DEFAULT_CRASH_LOOP_WINDOW_MS: Long = 10L * 60L * 1_000L
        public const val INITIAL_BACKOFF_MS: Long = 5_000L
        public const val BACKOFF_MULTIPLIER: Long = 2L
        public const val CRIT_STREAK_BEFORE_RESTART: Int = 3
        private const val TAG: String = "RecoveryCoordinator"
    }
}
