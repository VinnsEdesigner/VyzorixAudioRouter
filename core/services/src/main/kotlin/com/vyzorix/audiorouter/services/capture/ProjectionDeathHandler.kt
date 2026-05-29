// ProjectionDeathHandler — dedicated listener for the involuntary
// `MediaProjection.Callback.onStop()` callback.
//
// Per doc/MEDIA_PROJECTION_FLOW.md §Mitigation 3 this class is INTENTIONALLY
// distinct from [ProjectionTokenManager]:
//   - ProjectionTokenManager tracks general token lifecycle (grant /
//     refresh / explicit revoke). Used by every caller that wants to know
//     "do we currently have a token?".
//   - ProjectionDeathHandler ONLY hears the single involuntary onStop
//     signal. A bug here cannot corrupt the normal token bookkeeping.
//
// Response sequence on onStop():
//   1. Log a `projection.died` event to DaemonLogger (always; cheap +
//      survives the soak test export).
//   2. Notify the token manager so token-driven state machines react.
//   3. Pause IdleCaptureController (it is invalid to keep silence-detecting
//      on a dead projection).
//   4. Trigger the UI recovery daemon to re-launch the trampoline.
//   5. If three re-grants fail in 60s, escalate to "communication mode
//      fallback" — the daemon abandons projection-based capture and
//      falls back to pure VoIP-mode speaker forcing (still preserves
//      the headset-codec bypass, just no per-app system audio).
//
// Per doc/DOC_3_AUDIO_PIPELINE_AND_VOIP_EXEMPTIONS.md §6.10 (informally,
// since DOC_3 §6 lists 9 capture files but BUILD_ORDER + RepoTree both
// add ProjectionDeathHandler + IdleCaptureController on top — the file
// is part of the canonical capture/ tree).

package com.vyzorix.audiorouter.services.capture

import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Outcome of [ProjectionDeathHandler.recordRecoveryAttempt]. */
public enum class RecoveryDecision {
    /** Re-launch the trampoline; we have not hit the failure threshold. */
    RELAUNCH_TRAMPOLINE,

    /**
     * Three re-grants failed in 60s. Stop trying — fall back to pure
     * VoIP-mode speaker forcing per MEDIA_PROJECTION_FLOW.md §Mitigation 3.
     */
    FALLBACK_VOIP_ONLY,
}

/** Optional callback fanout for higher-layer recovery wiring. */
public interface ProjectionDeathListener {
    /** Notified once per onStop(). */
    public fun onProjectionDied()

    /** Notified when [recordRecoveryAttempt] returns [RecoveryDecision.FALLBACK_VOIP_ONLY]. */
    public fun onRecoveryGaveUp() {}
}

/**
 * Handler for the `MediaProjection.Callback.onStop()` callback.
 *
 * This is a passive listener — it does NOT register itself anywhere; the
 * [MediaProjectionSession] forwards onStop() to [onProjectionStopped].
 */
public class ProjectionDeathHandler(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val failureThresholdCount: Int = DEFAULT_FAILURE_THRESHOLD_COUNT,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
) {

    private val tokenManagerRef: AtomicReference<ProjectionTokenManager?> = AtomicReference(null)
    private val idleControllerRef: AtomicReference<IdleCaptureController?> = AtomicReference(null)
    private val listenerRef: AtomicReference<ProjectionDeathListener?> = AtomicReference(null)
    private val totalDeaths: AtomicInteger = AtomicInteger(0)
    private val recentFailureEpochs: ArrayDeque<Long> = ArrayDeque()

    /** Number of onStop() callbacks observed since process start. */
    public val totalProjectionDeaths: Int
        get() = totalDeaths.get()

    /**
     * Bind dependencies. We use a setter (rather than constructor) because
     * the listener typically depends on classes that themselves depend on
     * this handler (cyclic graph at service construction time).
     */
    public fun bind(
        tokenManager: ProjectionTokenManager,
        idleController: IdleCaptureController? = null,
        listener: ProjectionDeathListener? = null,
    ) {
        tokenManagerRef.set(tokenManager)
        idleControllerRef.set(idleController)
        listenerRef.set(listener)
    }

    /**
     * Called by [MediaProjectionSession] when the framework fires onStop().
     * Bookkeeping only — does NOT directly re-launch the trampoline;
     * higher layers respond to the Revoked event on
     * [ProjectionTokenManager.events] and decide via
     * [recordRecoveryAttempt] whether to escalate.
     */
    public fun onProjectionStopped() {
        totalDeaths.incrementAndGet()
        val now = clock()
        DaemonLogger.get().warn(
            TAG,
            "projection.died deaths=${totalDeaths.get()} epochMs=$now",
        )
        tokenManagerRef.get()?.recordRevoke(reason = "media_projection_on_stop")
        idleControllerRef.get()?.pause(reason = "projection_died")
        listenerRef.get()?.onProjectionDied()
    }

    /**
     * Caller (typically the lifecycle controller) records a recovery
     * attempt outcome. We update the rolling failure window and decide
     * whether to keep retrying or fall back.
     */
    public fun recordRecoveryAttempt(success: Boolean): RecoveryDecision {
        val now = clock()
        synchronized(recentFailureEpochs) {
            // Drop entries outside the window.
            while (true) {
                val head = recentFailureEpochs.peekFirst() ?: break
                if ((now - head) <= failureWindowMs) break
                recentFailureEpochs.pollFirst()
            }
            if (!success) {
                recentFailureEpochs.addLast(now)
            }
            if (recentFailureEpochs.size >= failureThresholdCount) {
                DaemonLogger.get().warn(
                    TAG,
                    "projection.recovery.giving_up failures=${recentFailureEpochs.size} windowMs=$failureWindowMs",
                )
                listenerRef.get()?.onRecoveryGaveUp()
                return RecoveryDecision.FALLBACK_VOIP_ONLY
            }
        }
        DaemonLogger.get().info(
            TAG,
            "projection.recovery.attempt success=$success failuresInWindow=${recentFailureEpochs.size}",
        )
        return RecoveryDecision.RELAUNCH_TRAMPOLINE
    }

    public companion object {
        public const val DEFAULT_FAILURE_THRESHOLD_COUNT: Int = 3
        public const val DEFAULT_FAILURE_WINDOW_MS: Long = 60_000L
        private const val TAG: String = "ProjectionDeathHandler"
    }
}
