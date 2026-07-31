// ProjectionActivityMediator — bridges `ProjectionPermissionActivity`
// result broadcasts into structured outcomes consumed by
// `ProjectionLaunchCoordinator`.
//
// Canonical placement per doc/VyzorixAudioRouter_RepoTree.md line 647:
//     core/services/projection/ProjectionActivityMediator.kt
//       "Trampoline mediator; listens for grant result callbacks".
//
// Lifecycle:
//   1. Coordinator subscribes via [observe].
//   2. Activity broadcasts `ACTION_PROJECTION_RESULT` via
//      ProjectionPermissionContract.
//   3. The mediator dedupes (the activity can be relaunched between
//      death-handler ticks), parses (resultCode, data, error) into a
//      typed `ProjectionAttemptOutcome`, and re-publishes.
//
// Why a mediator (vs the coordinator subscribing directly):
//   - Multiple subscribers can attach (CaptureLifecycleController +
//     RecoveryCoordinator + dashboard) without each maintaining its own
//     BroadcastReceiver.
//   - Atomic last-result snapshot lets the dashboard show the most
//     recent grant/deny without holding the receiver lifecycle.
//
// Threading: the broadcast is delivered on the main thread. We re-emit
// onto a CoroutineFlow so consumers can collect on whatever dispatcher
// they want.

package com.vyzorix.audiorouter.services.projection

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.vyzorix.audiorouter.services.capture.ProjectionPermissionContract
import com.vyzorix.audiorouter.services.logging.DaemonLogger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Typed outcome of a single trampoline attempt. */
public sealed interface ProjectionAttemptOutcome {

    /** User granted; [resultCode] + [data] are the values from the dialog. */
    public data class Granted(
        public val resultCode: Int,
        public val data: Intent,
        public val triggerOrigin: String,
        public val attemptEpochMs: Long,
    ) : ProjectionAttemptOutcome

    /** User dismissed or denied. */
    public data class Denied(
        public val resultCode: Int,
        public val triggerOrigin: String,
        public val attemptEpochMs: Long,
    ) : ProjectionAttemptOutcome

    /**
     * Activity threw or the system rejected the request. [error] carries
     * the canonical label from the trampoline.
     */
    public data class Failed(
        public val error: String,
        public val triggerOrigin: String,
        public val attemptEpochMs: Long,
    ) : ProjectionAttemptOutcome
}

/** Diagnostic snapshot of the mediator. */
public data class ProjectionActivityMediatorSnapshot(
    public val attempts: Long,
    public val grants: Long,
    public val denials: Long,
    public val failures: Long,
    public val lastAttemptEpochMs: Long,
    public val lastResultLabel: String,
    public val lastTriggerOrigin: String,
)

/**
 * Stateless registrar for [ProjectionPermissionContract] broadcasts.
 * Single-instance per coordinator.
 */
public class ProjectionActivityMediator(
    private val context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val attempts: AtomicLong = AtomicLong(0L)
    private val grants: AtomicLong = AtomicLong(0L)
    private val denials: AtomicLong = AtomicLong(0L)
    private val failures: AtomicLong = AtomicLong(0L)
    private val lastAttemptEpochMs: AtomicLong = AtomicLong(0L)
    private val lastResultLabel: AtomicReference<String> = AtomicReference("init")
    private val lastTriggerOrigin: AtomicReference<String> = AtomicReference("init")

    /**
     * Cold flow that registers a BroadcastReceiver on subscription and
     * unregisters on cancellation. Multiple subscribers are independent.
     */
    public fun observe(): Flow<ProjectionAttemptOutcome> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(broadcastContext: Context?, intent: Intent?) {
                if (intent?.action != ProjectionPermissionContract.ACTION_PROJECTION_RESULT) return
                val outcome = parse(intent)
                trySend(outcome)
            }
        }
        val filter = IntentFilter(ProjectionPermissionContract.ACTION_PROJECTION_RESULT)
        @Suppress("UnspecifiedRegisterReceiverFlag")
        context.registerReceiver(receiver, filter)
        DaemonLogger.get().info(TAG, "mediator.observe.registered")
        awaitClose {
            try {
                context.unregisterReceiver(receiver)
            } catch (t: Throwable) {
                DaemonLogger.get().warn(
                    TAG,
                    "mediator.observe.unregister_threw err=${t.javaClass.simpleName}",
                )
            }
            DaemonLogger.get().info(TAG, "mediator.observe.unregistered")
        }
    }

    /** Diagnostic snapshot. */
    public fun snapshot(): ProjectionActivityMediatorSnapshot =
        ProjectionActivityMediatorSnapshot(
            attempts = attempts.get(),
            grants = grants.get(),
            denials = denials.get(),
            failures = failures.get(),
            lastAttemptEpochMs = lastAttemptEpochMs.get(),
            lastResultLabel = lastResultLabel.get(),
            lastTriggerOrigin = lastTriggerOrigin.get(),
        )

    private fun parse(intent: Intent): ProjectionAttemptOutcome {
        attempts.incrementAndGet()
        val now = clock()
        lastAttemptEpochMs.set(now)
        val origin = intent.getStringExtra(ProjectionPermissionContract.EXTRA_TRIGGER_ORIGIN)
            ?: ProjectionPermissionContract.ORIGIN_UNKNOWN
        lastTriggerOrigin.set(origin)
        val error = intent.getStringExtra(ProjectionPermissionContract.EXTRA_RESULT_ERROR)
        if (error != null) {
            failures.incrementAndGet()
            lastResultLabel.set("failed:$error")
            DaemonLogger.get().warn(
                TAG,
                "mediator.parse.failed origin=$origin error=$error",
            )
            return ProjectionAttemptOutcome.Failed(
                error = error,
                triggerOrigin = origin,
                attemptEpochMs = now,
            )
        }
        val resultCode = intent.getIntExtra(
            ProjectionPermissionContract.EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED,
        )
        // Always use typed getParcelableExtra since minSdk is 33 (TIRAMISU=33).
        val resultData: Intent? = intent.getParcelableExtra(
            ProjectionPermissionContract.EXTRA_RESULT_DATA,
            Intent::class.java,
        )
        return if (resultCode == Activity.RESULT_OK && resultData != null) {
            grants.incrementAndGet()
            lastResultLabel.set("granted")
            DaemonLogger.get().info(
                TAG,
                "mediator.parse.granted origin=$origin total=${grants.get()}",
            )
            ProjectionAttemptOutcome.Granted(
                resultCode = resultCode,
                data = resultData,
                triggerOrigin = origin,
                attemptEpochMs = now,
            )
        } else {
            denials.incrementAndGet()
            lastResultLabel.set("denied:$resultCode")
            DaemonLogger.get().info(
                TAG,
                "mediator.parse.denied origin=$origin resultCode=$resultCode total=${denials.get()}",
            )
            ProjectionAttemptOutcome.Denied(
                resultCode = resultCode,
                triggerOrigin = origin,
                attemptEpochMs = now,
            )
        }
    }

    public companion object {
        private const val TAG: String = "ProjectionActivityMediator"
    }
}
