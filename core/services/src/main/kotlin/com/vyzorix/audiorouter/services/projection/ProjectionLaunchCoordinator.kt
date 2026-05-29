// ProjectionLaunchCoordinator — the single class that decides WHEN to
// launch the MediaProjection trampoline and HOW.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 645:
//     core/services/projection/ProjectionLaunchCoordinator.kt
//       "Verifies screen + lock state before initiating flow".
//
// Composed of the six other projection-package helpers:
//   - [ProjectionLaunchConditions] — preflight (screen/keyguard/channel/POST_NOTIFICATIONS).
//   - [ProjectionRetryPolicy] — throttle + backoff + ban.
//   - [ProjectionVisibilityGuard] — verify foreground-service eligibility.
//   - [ProjectionForegroundEscalator] — pin channel to HIGH for the
//     duration of the re-grant flow.
//   - [FullScreenIntentBridge] — fallback when direct launch is blocked.
//   - [ProjectionActivityMediator] — parse the result broadcast.
//
// Decision tree:
//   1. Check launch conditions.
//      - Not met & only `screen_off`/`keyguard_locked` — fall back to
//        FullScreenIntentBridge (the user will be prompted on unlock).
//      - Not met & `channel_inactive`/`notifications_denied` — abort
//        and surface the failure to the caller.
//   2. Check retry policy.
//      - Throttle → return wait window.
//      - Banned → abort with reason.
//   3. Check visibility guard.
//      - NotificationGone → surface failure so caller re-posts
//        the daemon notification before retrying.
//   4. Escalate channel importance.
//   5. Launch trampoline (direct activity Intent OR FullScreenIntent
//      depending on step 1's fallback decision).
//   6. Wait for the mediator's first emission (with timeout).
//   7. De-escalate channel importance.
//   8. Return the outcome.
//
// The coordinator is a CONCURRENT-SAFE single-instance. Multiple callers
// can request a launch; in-flight requests coalesce via the
// [inFlightAttempt] AtomicReference.

package com.vyzorix.audiorouter.services.projection

import android.content.Context
import android.content.Intent
import com.vyzorix.audiorouter.services.capture.ProjectionPermissionContract
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Outcome of a single [ProjectionLaunchCoordinator.requestLaunch] call. */
public sealed interface ProjectionLaunchResult {
    public data class Granted(public val resultCode: Int, public val data: Intent) : ProjectionLaunchResult
    public data class Denied(public val resultCode: Int) : ProjectionLaunchResult
    public data class Throttled(public val waitMillis: Long, public val reason: String) : ProjectionLaunchResult
    public data class Aborted(public val reason: String, public val labels: List<String> = emptyList()) :
        ProjectionLaunchResult

    public data class TimedOut(public val timeoutMs: Long) : ProjectionLaunchResult
    public data class Failed(public val reason: String, public val cause: Throwable? = null) : ProjectionLaunchResult
}

/** Snapshot of the coordinator's recent decisions. */
public data class ProjectionLaunchCoordinatorSnapshot(
    public val launches: Long,
    public val grants: Long,
    public val denials: Long,
    public val aborts: Long,
    public val throttles: Long,
    public val timeouts: Long,
    public val failures: Long,
    public val lastLaunchEpochMs: Long,
    public val lastResultLabel: String,
)

/**
 * Orchestrates the projection-grant flow. Single-instance.
 *
 * @param trampolineActivityClassName fully-qualified name of the activity
 *   that wraps `MediaProjectionManager.createScreenCaptureIntent()`
 *   (e.g. `com.vyzorix.audiorouter.ProjectionPermissionActivity`).
 */
public class ProjectionLaunchCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val trampolineActivityClassName: String,
    private val launchConditions: ProjectionLaunchConditions,
    private val retryPolicy: ProjectionRetryPolicy,
    private val visibilityGuard: ProjectionVisibilityGuard,
    private val foregroundEscalator: ProjectionForegroundEscalator,
    private val fullScreenIntentBridge: FullScreenIntentBridge,
    private val mediator: ProjectionActivityMediator,
    private val launchTimeoutMs: Long = DEFAULT_LAUNCH_TIMEOUT_MS,
    private val fallbackDelayMs: Long = DEFAULT_FALLBACK_DELAY_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val launches: AtomicLong = AtomicLong(0L)
    private val grants: AtomicLong = AtomicLong(0L)
    private val denials: AtomicLong = AtomicLong(0L)
    private val aborts: AtomicLong = AtomicLong(0L)
    private val throttles: AtomicLong = AtomicLong(0L)
    private val timeouts: AtomicLong = AtomicLong(0L)
    private val failures: AtomicLong = AtomicLong(0L)
    private val lastLaunchEpochMs: AtomicLong = AtomicLong(0L)
    private val lastResultLabel: AtomicReference<String> = AtomicReference("init")
    private val inFlightAttempt: AtomicReference<CompletableDeferred<ProjectionLaunchResult>?> =
        AtomicReference(null)

    /**
     * Request a projection-grant launch. Returns when the trampoline has
     * settled (granted/denied/throttled/aborted). If a launch is already
     * in flight the call attaches to the existing future.
     */
    public suspend fun requestLaunch(
        triggerOrigin: String = ProjectionPermissionContract.ORIGIN_MANUAL,
    ): ProjectionLaunchResult {
        val deferred = CompletableDeferred<ProjectionLaunchResult>()
        val attached = inFlightAttempt.compareAndSet(null, deferred)
        if (!attached) {
            val existing = inFlightAttempt.get()
            if (existing != null) {
                DaemonLogger.get().info(TAG, "coordinator.attach origin=$triggerOrigin")
                return existing.await()
            }
        }
        val launchJob: Job = scope.launch {
            try {
                val outcome = runLaunchFlow(triggerOrigin)
                deferred.complete(outcome)
            } catch (t: Throwable) {
                DaemonLogger.get().error(
                    TAG,
                    "coordinator.flow_threw err=${t.javaClass.simpleName} msg=${t.message}",
                )
                failures.incrementAndGet()
                lastResultLabel.set("failed_flow_threw")
                deferred.complete(ProjectionLaunchResult.Failed("flow_threw", t))
            } finally {
                inFlightAttempt.compareAndSet(deferred, null)
            }
        }
        launchJob.invokeOnCompletion {
            inFlightAttempt.compareAndSet(deferred, null)
        }
        return deferred.await()
    }

    /** Diagnostic snapshot. */
    public fun snapshot(): ProjectionLaunchCoordinatorSnapshot =
        ProjectionLaunchCoordinatorSnapshot(
            launches = launches.get(),
            grants = grants.get(),
            denials = denials.get(),
            aborts = aborts.get(),
            throttles = throttles.get(),
            timeouts = timeouts.get(),
            failures = failures.get(),
            lastLaunchEpochMs = lastLaunchEpochMs.get(),
            lastResultLabel = lastResultLabel.get(),
        )

    private suspend fun runLaunchFlow(triggerOrigin: String): ProjectionLaunchResult {
        launches.incrementAndGet()
        lastLaunchEpochMs.set(clock())
        DaemonLogger.get().info(TAG, "coordinator.flow.start origin=$triggerOrigin")

        // 1. Preflight.
        val condition = launchConditions.evaluate()
        val useFullScreenIntent: Boolean = when (condition) {
            is ProjectionLaunchCondition.Ready -> false
            is ProjectionLaunchCondition.Blocked -> {
                val onlyLockState = condition.labels.all { it == ProjectionLaunchConditions.LABEL_SCREEN_OFF ||
                    it == ProjectionLaunchConditions.LABEL_KEYGUARD_LOCKED }
                if (onlyLockState) {
                    true
                } else {
                    aborts.incrementAndGet()
                    lastResultLabel.set("aborted_preflight")
                    DaemonLogger.get().warn(
                        TAG,
                        "coordinator.flow.aborted_preflight labels=${condition.labels.joinToString(",")}",
                    )
                    return ProjectionLaunchResult.Aborted(
                        reason = "preflight_failed",
                        labels = condition.labels,
                    )
                }
            }
        }

        // 2. Retry policy.
        val decision = retryPolicy.tryAcquireLaunchSlot()
        when (decision) {
            is RetryDecision.Allow -> Unit
            is RetryDecision.Throttle -> {
                throttles.incrementAndGet()
                lastResultLabel.set("throttled_${decision.reason}")
                return ProjectionLaunchResult.Throttled(decision.waitMillis, decision.reason)
            }
            is RetryDecision.Banned -> {
                aborts.incrementAndGet()
                lastResultLabel.set("banned_${decision.reason}")
                return ProjectionLaunchResult.Aborted(decision.reason)
            }
        }

        // 3. Visibility guard.
        val visibility = visibilityGuard.check()
        if (visibility is VisibilityCheckResult.NotificationGone) {
            aborts.incrementAndGet()
            lastResultLabel.set("aborted_${visibility.reason}")
            DaemonLogger.get().warn(
                TAG,
                "coordinator.flow.notification_gone reason=${visibility.reason}",
            )
            return ProjectionLaunchResult.Aborted(visibility.reason)
        }

        // 4. Escalate channel.
        foregroundEscalator.escalate()
        try {
            // 5. Launch trampoline.
            if (useFullScreenIntent) {
                fullScreenIntentBridge.post(triggerOrigin)
                // Give the FSI a moment to surface before we begin
                // listening (avoids a race on lockscreen).
                delay(fallbackDelayMs)
            } else {
                launchTrampolineDirect(triggerOrigin)
            }

            // 6. Wait for outcome.
            val outcome = withTimeoutOrNull(launchTimeoutMs) {
                mediator.observe().first { outcome ->
                    when (outcome) {
                        is ProjectionAttemptOutcome.Granted -> outcome.triggerOrigin == triggerOrigin
                        is ProjectionAttemptOutcome.Denied -> outcome.triggerOrigin == triggerOrigin
                        is ProjectionAttemptOutcome.Failed -> outcome.triggerOrigin == triggerOrigin
                    }
                }
            }
            fullScreenIntentBridge.cancel()
            return when (outcome) {
                null -> {
                    timeouts.incrementAndGet()
                    lastResultLabel.set("timed_out")
                    retryPolicy.recordDenial("timeout")
                    DaemonLogger.get().warn(TAG, "coordinator.flow.timed_out timeoutMs=$launchTimeoutMs")
                    ProjectionLaunchResult.TimedOut(launchTimeoutMs)
                }
                is ProjectionAttemptOutcome.Granted -> {
                    grants.incrementAndGet()
                    lastResultLabel.set("granted")
                    retryPolicy.recordGrant()
                    ProjectionLaunchResult.Granted(outcome.resultCode, outcome.data)
                }
                is ProjectionAttemptOutcome.Denied -> {
                    denials.incrementAndGet()
                    lastResultLabel.set("denied")
                    retryPolicy.recordDenial("user_denied")
                    ProjectionLaunchResult.Denied(outcome.resultCode)
                }
                is ProjectionAttemptOutcome.Failed -> {
                    failures.incrementAndGet()
                    lastResultLabel.set("failed:${outcome.error}")
                    retryPolicy.recordDenial("activity_${outcome.error}")
                    ProjectionLaunchResult.Failed(outcome.error)
                }
            }
        } finally {
            foregroundEscalator.deescalate()
        }
    }

    private fun launchTrampolineDirect(triggerOrigin: String) {
        val intent = Intent().apply {
            setClassName(context.packageName, trampolineActivityClassName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN, triggerOrigin)
        }
        try {
            context.startActivity(intent)
            DaemonLogger.get().info(
                TAG,
                "coordinator.launch.direct origin=$triggerOrigin activity=$trampolineActivityClassName",
            )
        } catch (t: Throwable) {
            DaemonLogger.get().error(
                TAG,
                "coordinator.launch.direct_threw err=${t.javaClass.simpleName} msg=${t.message}",
            )
            // Fall back to the FSI path on direct-launch failure.
            fullScreenIntentBridge.post(triggerOrigin)
        }
    }

    public companion object {
        public const val DEFAULT_LAUNCH_TIMEOUT_MS: Long = 45_000L
        public const val DEFAULT_FALLBACK_DELAY_MS: Long = 250L
        private const val TAG: String = "ProjectionLaunchCoordinator"
    }
}
